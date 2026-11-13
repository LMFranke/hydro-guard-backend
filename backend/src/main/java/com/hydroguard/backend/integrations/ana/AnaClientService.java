package com.hydroguard.backend.integrations.ana;

import com.hydroguard.backend.dtos.SystemHealthDTO;
import com.hydroguard.backend.dtos.SystemHealthDTO.ServiceStatus;
import com.hydroguard.backend.integrations.ana.dto.AnaAuthResponseDTO;
import com.hydroguard.backend.integrations.ana.dto.AnaInventoryResponseDTO;
import com.hydroguard.backend.integrations.ana.dto.AnaInventoryResponseDTO.AnaStationInventoryItemDTO;
import com.hydroguard.backend.integrations.ana.dto.AnaTelemetryResponseDTO;
import com.hydroguard.backend.models.City;
import com.hydroguard.backend.models.MonitoringStation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Service
public class AnaClientService {

    // Seleciona um pool maior de candidatas reais para que o TelemetryService possa pular
    // sensores que não transmitiram hoje e ainda assim entregar 8 Fluv + 4 Pluv com dados reais
    private static final int CANDIDATE_FLUVIOMETRIC_LIMIT = 18;
    private static final int CANDIDATE_PLUVIOMETRIC_LIMIT = 8;
    private static final double BASIN_SEARCH_RADIUS_KM = 35.0;
    private static final long MIN_REQUEST_INTERVAL_MS = 200;

    private final RestClient restClient;
    private final String baseUrl;
    private final String identifier;
    private final String password;

    private volatile String cachedToken;
    private volatile Instant tokenExpiration;
    private final ReentrantLock authLock = new ReentrantLock();
    private final ReentrantLock inventoryLock = new ReentrantLock();
    private final ReentrantLock paceLock = new ReentrantLock();
    private volatile long lastRequestTimestampMs = 0;

    private volatile Instant circuitOpenUntil = Instant.EPOCH;

    private final Map<String, CachedStateInventory> stateInventoryCache = new ConcurrentHashMap<>();
    private record CachedStateInventory(List<AnaStationInventoryItemDTO> items, Instant expiresAt) {}

    private volatile ServiceStatus lastKnownAnaStatus = ServiceStatus.ONLINE;
    private volatile Long lastLatencyMs = null;
    private volatile Instant lastSuccessAt = null;
    private volatile Instant lastProbeAt = null;
    private volatile String lastStatusMessage = "Integração ANA operacional.";
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);

    public AnaClientService(
            @Value("${api.ana.base-url}") String baseUrl,
            @Value("${api.ana.identifier}") String identifier,
            @Value("${api.ana.password}") String password
    ) {
        this.baseUrl = baseUrl;
        this.identifier = identifier;
        this.password = password;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(8));
        // Timeout de leitura de 20s porque o catálogo de estações (/HidroInventarioEstacoes/v1) é extenso
        requestFactory.setReadTimeout(Duration.ofSeconds(20));

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.USER_AGENT, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) HydroGuard/1.0")
                .defaultHeader(HttpHeaders.ACCEPT, "application/json")
                .build();
    }

    public boolean isCircuitOpen() {
        return Instant.now().isBefore(circuitOpenUntil);
    }

    /**
     * Garante que chamadas concorrentes (ex: Cache Warmer + Usuário clicando no Front-end)
     * respeitem um intervalo mínimo entre requisições para nunca tomar bloqueio de IP na ANA.
     */
    private void enforceRequestPacing() {
        paceLock.lock();
        try {
            long now = System.currentTimeMillis();
            long elapsed = now - lastRequestTimestampMs;
            if (elapsed < MIN_REQUEST_INTERVAL_MS) {
                Thread.sleep(MIN_REQUEST_INTERVAL_MS - elapsed);
            }
            lastRequestTimestampMs = System.currentTimeMillis();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } finally {
            paceLock.unlock();
        }
    }

    public String getValidToken() {
        if (isTokenValid()) {
            return cachedToken;
        }

        if (isCircuitOpen()) {
            throw new RuntimeException("ANA API Circuit Breaker is OPEN (Fast-Fail)");
        }

        authLock.lock();
        try {
            if (isTokenValid()) {
                return cachedToken;
            }

            if (isCircuitOpen()) {
                throw new RuntimeException("ANA API Circuit Breaker is OPEN (Fast-Fail)");
            }

            enforceRequestPacing();
            long startNanos = System.nanoTime();
            AnaAuthResponseDTO response = restClient.get()
                    .uri("/OAUth/v1")
                    .header("Identificador", identifier)
                    .header("Senha", password)
                    .retrieve()
                    .body(AnaAuthResponseDTO.class);

            long elapsedMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();

            if (response == null || response.items() == null || response.items().tokenautenticacao() == null) {
                openCircuit("Resposta de autenticação inválida da ANA.");
                throw new RuntimeException("Failed to authenticate with ANA API");
            }

            this.cachedToken = response.items().tokenautenticacao();
            this.tokenExpiration = Instant.now().plus(55, ChronoUnit.MINUTES);
            recordSuccess(elapsedMs);
            log.info("Successfully obtained new ANA API token ({} ms)", elapsedMs);

            return this.cachedToken;
        } catch (Exception e) {
            if (!isCircuitOpen()) {
                openCircuit("Webservice da ANA indisponível (Timeout na autenticação).");
            }
            throw e;
        } finally {
            authLock.unlock();
        }
    }

    public AnaTelemetryResponseDTO getStationTelemetry(String stationCode, LocalDate searchDate, String rangeInterval) {
        if (isCircuitOpen()) {
            throw new RuntimeException("ANA Circuit Breaker OPEN - skipping station " + stationCode);
        }

        enforceRequestPacing();
        long startNanos = System.nanoTime();
        try {
            String token = getValidToken();

            URI uri = UriComponentsBuilder.fromUriString(baseUrl + "/HidroinfoanaSerieTelemetricaDetalhada/v1")
                    .queryParam("Código da Estação", stationCode)
                    .queryParam("Tipo Filtro Data", "DATA_LEITURA")
                    .queryParam("Data de Busca (yyyy-MM-dd)", searchDate.toString())
                    .queryParam("Range Intervalo de busca", rangeInterval)
                    .encode(StandardCharsets.UTF_8)
                    .build()
                    .toUri();

            AnaTelemetryResponseDTO response = restClient.get()
                    .uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .retrieve()
                    .body(AnaTelemetryResponseDTO.class);

            long elapsedMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
            recordSuccess(elapsedMs);
            return response;
        } catch (Exception e) {
            recordFailure("Falha na leitura da estação " + stationCode);
            throw e;
        }
    }

    /**
     * Pré-carrega o inventário estadual da ANA antes de sincronizar os municípios.
     */
    public void preloadStateInventory(String stateUf) {
        getOperatingTelemetricStationsByState(stateUf);
    }

    /**
     * Descobre as estações telemétricas reais da ANA para o município e bacia (raio de 35 km),
     * ordenadas por distância geográfica a partir do centro da cidade.
     */
    public List<MonitoringStation> discoverStationsForCity(City city) {
        String stateUf = (city.getState() != null && !city.getState().isBlank())
                ? city.getState().trim().toUpperCase()
                : "SC";

        List<AnaStationInventoryItemDTO> stateStations = getOperatingTelemetricStationsByState(stateUf);
        if (stateStations.isEmpty()) {
            return Collections.emptyList();
        }

        String normalizedCityName = normalizeText(city.getName());

        // 1. Estações telemétricas registradas dentro do próprio município
        List<AnaStationInventoryItemDTO> exactCityMatches = stateStations.stream()
                .filter(item -> normalizeText(item.municipalityName()).equals(normalizedCityName))
                .sorted(Comparator.comparing(
                        (AnaStationInventoryItemDTO item) -> item.lastUpdate() != null ? item.lastUpdate() : "",
                        Comparator.reverseOrder()
                ))
                .toList();

        // 2. Estações telemétricas na bacia hidrográfica num raio de 35 km (ordenadas pela menor distância)
        List<AnaStationInventoryItemDTO> radiusMatches = Collections.emptyList();
        if (city.getMapLat() != null && city.getMapLng() != null) {
            radiusMatches = stateStations.stream()
                    .filter(item -> !normalizeText(item.municipalityName()).equals(normalizedCityName))
                    .filter(item -> {
                        Double lat = parseCoord(item.latitude());
                        Double lng = parseCoord(item.longitude());
                        if (lat == null || lng == null) return false;
                        return calculateDistanceKm(city.getMapLat(), city.getMapLng(), lat, lng) <= BASIN_SEARCH_RADIUS_KM;
                    })
                    .sorted(Comparator.comparingDouble((AnaStationInventoryItemDTO item) -> {
                        Double lat = parseCoord(item.latitude());
                        Double lng = parseCoord(item.longitude());
                        return calculateDistanceKm(city.getMapLat(), city.getMapLng(), lat, lng);
                    }))
                    .toList();
        }

        List<AnaStationInventoryItemDTO> combinedCandidates = new ArrayList<>(exactCityMatches.size() + radiusMatches.size());
        combinedCandidates.addAll(exactCityMatches);
        combinedCandidates.addAll(radiusMatches);

        Map<String, MonitoringStation> selectedStations = new LinkedHashMap<>();
        Set<String> seenNamesAndType = new HashSet<>();
        int fluviometricCount = 0;
        int pluviometricCount = 0;

        for (AnaStationInventoryItemDTO item : combinedCandidates) {
            String code = item.stationCode().trim();
            if (selectedStations.containsKey(code)) continue;

            boolean isFluviometric = item.stationType() != null && normalizeText(item.stationType()).startsWith("FLUVIO");
            String typeKey = isFluviometric ? "FLUV" : "PLUV";
            String dedupNameKey = typeKey + ":" + normalizeText(item.stationName());

            if (seenNamesAndType.contains(dedupNameKey)) {
                continue;
            }

            if (isFluviometric && fluviometricCount < CANDIDATE_FLUVIOMETRIC_LIMIT) {
                selectedStations.put(code, mapInventoryItemToStation(item, city, normalizedCityName));
                seenNamesAndType.add(dedupNameKey);
                fluviometricCount++;
            } else if (!isFluviometric && pluviometricCount < CANDIDATE_PLUVIOMETRIC_LIMIT) {
                selectedStations.put(code, mapInventoryItemToStation(item, city, normalizedCityName));
                seenNamesAndType.add(dedupNameKey);
                pluviometricCount++;
            }

            if (fluviometricCount >= CANDIDATE_FLUVIOMETRIC_LIMIT && pluviometricCount >= CANDIDATE_PLUVIOMETRIC_LIMIT) {
                break;
            }
        }

        List<MonitoringStation> finalCandidates = applyCoordinateDeoverlapping(new ArrayList<>(selectedStations.values()));

        log.info("[ANA Discovery] Cidade '{}': {} candidatas telemétricas reais encontradas no raio de {}km",
                city.getName(), finalCandidates.size(), BASIN_SEARCH_RADIUS_KM);

        return finalCandidates;
    }

    public List<AnaStationInventoryItemDTO> discoverActiveItajaiStations() {
        return getOperatingTelemetricStationsByState("SC").stream()
                .filter(item -> "83".equals(item.subBasinCode()))
                .toList();
    }

    /**
     * Consulta o endpoint oficial /HidroInventarioEstacoes/v1 da ANA com lock de exclusão mútua
     * (garantindo que apenas 1 thread baixe o catálogo estadual) e filtra estritamente
     * estações Telemétricas ("1") e Operando ("1").
     */
    private List<AnaStationInventoryItemDTO> getOperatingTelemetricStationsByState(String stateUf) {
        CachedStateInventory cached = stateInventoryCache.get(stateUf);
        if (cached != null && Instant.now().isBefore(cached.expiresAt())) {
            return cached.items();
        }

        inventoryLock.lock();
        try {
            cached = stateInventoryCache.get(stateUf);
            if (cached != null && Instant.now().isBefore(cached.expiresAt())) {
                return cached.items();
            }

            for (int attempt = 1; attempt <= 2; attempt++) {
                try {
                    String token = getValidToken();
                    enforceRequestPacing();

                    URI uri = UriComponentsBuilder.fromUriString(baseUrl + "/HidroInventarioEstacoes/v1")
                            .queryParam("Unidade Federativa", stateUf)
                            .queryParam("Código da Bacia", "8")
                            .encode(StandardCharsets.UTF_8)
                            .build()
                            .toUri();

                    long startNanos = System.nanoTime();
                    var response = restClient.get()
                            .uri(uri)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .retrieve()
                            .body(AnaInventoryResponseDTO.class);

                    long elapsedMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();

                    if (response != null && response.items() != null && !response.items().isEmpty()) {
                        // Filtra ESTRITAMENTE estações telemétricas ativas (estações manuais dão erro na API de série horária)
                        List<AnaStationInventoryItemDTO> activeTelemetric = response.items().stream()
                                .filter(item -> item.stationCode() != null && !item.stationCode().isBlank())
                                .filter(item -> "1".equals(item.operating()))
                                .filter(item -> "1".equals(item.isTelemetric()))
                                .filter(item -> item.telemetricEndDate() == null || item.telemetricEndDate().isBlank())
                                .filter(item -> parseCoord(item.latitude()) != null && parseCoord(item.longitude()) != null)
                                .toList();

                        recordSuccess(elapsedMs);
                        log.info("[ANA Inventory] {} estações telemétricas reais carregadas da ANA para {} ({} ms)",
                                activeTelemetric.size(), stateUf, elapsedMs);

                        stateInventoryCache.put(
                                stateUf,
                                new CachedStateInventory(activeTelemetric, Instant.now().plus(6, ChronoUnit.HOURS))
                        );
                        return activeTelemetric;
                    }
                } catch (Exception e) {
                    log.warn("[ANA Inventory] Tentativa {}/2 falhou ao buscar inventário de {}: {}",
                            attempt, stateUf, e.getMessage());
                    if (attempt == 1) {
                        try {
                            Thread.sleep(1500);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }

            return cached != null ? cached.items() : Collections.emptyList();
        } finally {
            inventoryLock.unlock();
        }
    }

    private MonitoringStation mapInventoryItemToStation(
            AnaStationInventoryItemDTO item,
            City city,
            String normalizedTargetCityName
    ) {
        boolean isFluviometric = item.stationType() != null
                && normalizeText(item.stationType()).startsWith("FLUVIO");

        String rawStationName = item.stationName() != null
                ? item.stationName().trim()
                : "Estação " + item.stationCode();

        String stationMunicipality = item.municipalityName() != null ? item.municipalityName().trim() : "";
        boolean isFromNeighborCity = !stationMunicipality.isEmpty()
                && !normalizeText(stationMunicipality).equals(normalizedTargetCityName);

        String displayName = (isFromNeighborCity && !normalizeText(rawStationName).contains(normalizeText(stationMunicipality)))
                ? rawStationName + " (" + stationMunicipality + ")"
                : rawStationName;

        String riverName = (item.riverName() != null && !item.riverName().isBlank())
                ? item.riverName().trim()
                : "N/A";

        return MonitoringStation.builder()
                .stationCode(item.stationCode().trim())
                .name(displayName)
                .stationType(isFluviometric ? "Fluviometrica" : "Pluviometrica")
                .riverName(riverName)
                .riverCode(item.riverCode())
                .operatorAcronym(item.operatorAcronym())
                .latitude(parseCoord(item.latitude()))
                .longitude(parseCoord(item.longitude()))
                .altitude(parseCoord(item.altitude()))
                .thresholdWarning(isFluviometric ? 450.0 : 40.0)
                .thresholdAlert(isFluviometric ? 650.0 : 70.0)
                .thresholdCritical(isFluviometric ? 850.0 : 100.0)
                .city(city)
                .build();
    }

    private List<MonitoringStation> applyCoordinateDeoverlapping(List<MonitoringStation> stations) {
        for (int i = 0; i < stations.size(); i++) {
            MonitoringStation current = stations.get(i);
            if (current.getLatitude() == null || current.getLongitude() == null) continue;

            int overlapCount = 0;
            for (int j = 0; j < i; j++) {
                MonitoringStation prev = stations.get(j);
                if (prev.getLatitude() == null || prev.getLongitude() == null) continue;

                double latDiff = Math.abs(current.getLatitude() - prev.getLatitude());
                double lngDiff = Math.abs(current.getLongitude() - prev.getLongitude());
                if (latDiff < 0.0010 && lngDiff < 0.0010) {
                    overlapCount++;
                }
            }

            if (overlapCount > 0) {
                double angle = overlapCount * (Math.PI / 3.0);
                double radiusOffset = 0.0022 * ((overlapCount + 1) / 2);
                current.setLatitude(Math.round((current.getLatitude() + radiusOffset * Math.cos(angle)) * 1000000.0) / 1000000.0);
                current.setLongitude(Math.round((current.getLongitude() + radiusOffset * Math.sin(angle)) * 1000000.0) / 1000000.0);
            }
        }
        return stations;
    }

    private static String normalizeText(String input) {
        if (input == null) return "";
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFD);
        return normalized.replaceAll("\\p{M}", "").trim().toUpperCase();
    }

    private static Double parseCoord(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Double.parseDouble(raw.trim().replace(",", "."));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static double calculateDistanceKm(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

    public SystemHealthDTO checkAnaHealth() {
        Instant now = Instant.now();
        boolean needsActiveProbe = !isCircuitOpen() && (
                this.lastKnownAnaStatus != ServiceStatus.ONLINE
                        || lastProbeAt == null
                        || now.isAfter(lastProbeAt.plusSeconds(60))
        );

        if (needsActiveProbe) {
            probeAnaAuthenticationLive();
        }

        return new SystemHealthDTO(
                ServiceStatus.ONLINE,
                this.lastKnownAnaStatus,
                this.lastLatencyMs,
                Instant.now(),
                this.lastSuccessAt,
                this.lastStatusMessage
        );
    }

    private void probeAnaAuthenticationLive() {
        if (!authLock.tryLock()) {
            return;
        }
        try {
            lastProbeAt = Instant.now();
            enforceRequestPacing();
            long startNanos = System.nanoTime();
            AnaAuthResponseDTO response = restClient.get()
                    .uri("/OAUth/v1")
                    .header("Identificador", identifier)
                    .header("Senha", password)
                    .retrieve()
                    .body(AnaAuthResponseDTO.class);

            long elapsedMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();

            if (response != null && response.items() != null && response.items().tokenautenticacao() != null) {
                this.cachedToken = response.items().tokenautenticacao();
                this.tokenExpiration = Instant.now().plus(55, ChronoUnit.MINUTES);
                recordSuccess(elapsedMs);
            } else {
                openCircuit("Webservice da ANA respondeu sem token válido.");
            }
        } catch (Exception e) {
            log.warn("ANA live health probe failed: {}", e.getMessage());
            openCircuit("Webservice da ANA sem resposta no momento.");
        } finally {
            authLock.unlock();
        }
    }

    private void openCircuit(String reason) {
        this.circuitOpenUntil = Instant.now().plusSeconds(25);
        this.lastProbeAt = Instant.now();
        this.consecutiveFailures.incrementAndGet();
        this.lastKnownAnaStatus = ServiceStatus.OFFLINE;
        this.lastStatusMessage = reason;
        log.warn("ANA Circuit Breaker OPENED for 25s. Reason: {}", reason);
    }

    private void recordSuccess(long latencyMs) {
        this.circuitOpenUntil = Instant.EPOCH;
        this.lastLatencyMs = latencyMs;
        this.lastSuccessAt = Instant.now();
        this.lastProbeAt = Instant.now();
        int failuresReset = this.consecutiveFailures.getAndSet(0);

        if (latencyMs > 8000) {
            this.lastKnownAnaStatus = ServiceStatus.INSTABLE;
            this.lastStatusMessage = "API da ANA respondendo com alta latência (" + latencyMs + " ms).";
        } else {
            this.lastKnownAnaStatus = ServiceStatus.ONLINE;
            this.lastStatusMessage = failuresReset > 0
                    ? "Conexão com a ANA restabelecida."
                    : "Telemetria ANA operacional.";
        }
    }

    private void recordFailure(String reason) {
        this.lastProbeAt = Instant.now();
        int failures = this.consecutiveFailures.incrementAndGet();

        if (failures >= 8) {
            openCircuit(reason);
        } else if (failures >= 4) {
            this.lastKnownAnaStatus = ServiceStatus.INSTABLE;
            this.lastStatusMessage = "Oscilação em algumas estações da ANA (" + reason + ").";
        }
    }

    private boolean isTokenValid() {
        return cachedToken != null && tokenExpiration != null && Instant.now().isBefore(tokenExpiration);
    }
}