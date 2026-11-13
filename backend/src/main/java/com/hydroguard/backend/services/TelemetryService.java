package com.hydroguard.backend.services;

import com.hydroguard.backend.dtos.StationTelemetryDTO;
import com.hydroguard.backend.dtos.StationTelemetryDTO.TelemetryReadingDTO;
import com.hydroguard.backend.integrations.ana.AnaClientService;
import com.hydroguard.backend.integrations.ana.dto.AnaTelemetryResponseDTO;
import com.hydroguard.backend.integrations.ana.dto.AnaTelemetryResponseDTO.AnaTelemetryItemDTO;
import com.hydroguard.backend.models.City;
import com.hydroguard.backend.models.MonitoringStation;
import com.hydroguard.backend.repositories.CityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Service
@RequiredArgsConstructor
public class TelemetryService {

    private static final long CACHE_TTL_MINUTES = 10;
    private static final long EMPTY_STATION_TTL_MINUTES = 30;
    private static final int MAX_ACTIVE_FLUVIOMETRIC_PER_CITY = 8;
    private static final int MAX_ACTIVE_PLUVIOMETRIC_PER_CITY = 4;

    private final CityRepository cityRepository;
    private final AnaClientService anaClientService;

    private final Map<String, CachedCityTelemetry> telemetryCache = new ConcurrentHashMap<>();
    private final Map<String, List<MonitoringStation>> discoveredStationsCache = new ConcurrentHashMap<>();
    private final Map<String, CachedStationReading> singleStationCache = new ConcurrentHashMap<>();
    private final Map<String, Instant> emptyStationCache = new ConcurrentHashMap<>();
    private final Map<String, StationTelemetryDTO> lastValidStationCache = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> cityFetchLocks = new ConcurrentHashMap<>();
    private final Set<String> refreshingCities = ConcurrentHashMap.newKeySet();

    private record CachedCityTelemetry(List<StationTelemetryDTO> data, Instant expiresAt) {}
    private record CachedStationReading(StationTelemetryDTO dto, Instant expiresAt) {}

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        CompletableFuture.runAsync(this::syncAllCitiesTelemetryInBackground);
    }

    @Scheduled(fixedDelay = 5 * 60 * 1000, initialDelay = 5 * 60 * 1000)
    public void scheduledTelemetrySync() {
        syncAllCitiesTelemetryInBackground();
    }

    private void syncAllCitiesTelemetryInBackground() {
        List<City> cities = cityRepository.findAll();
        log.info("[Cache Warmer] Pré-carregando inventário da ANA e sincronizando {} cidade(s)...", cities.size());

        // Baixa o inventário estadual de SC uma única vez antes de iterar pelas cidades
        anaClientService.preloadStateInventory("SC");

        for (City city : cities) {
            try {
                List<StationTelemetryDTO> result = refreshCityCacheSync(city.getSlug());
                log.info("[Cache Warmer] Cidade '{}' sincronizada com {} estações reais ativas.",
                        city.getSlug(), result.size());
            } catch (Exception e) {
                log.warn("[Cache Warmer] Erro ao sincronizar cidade '{}': {}", city.getSlug(), e.getMessage());
            }
        }

        log.info("[Cache Warmer] Sincronização em background concluída.");
    }

    public List<StationTelemetryDTO> getCityTelemetry(String citySlug) {
        String normalizedSlug = citySlug.trim().toLowerCase();
        CachedCityTelemetry cached = telemetryCache.get(normalizedSlug);

        if (cached != null && !cached.data().isEmpty()) {
            if (Instant.now().isAfter(cached.expiresAt()) && refreshingCities.add(normalizedSlug)) {
                CompletableFuture.runAsync(() -> {
                    try {
                        refreshCityCacheSync(normalizedSlug);
                    } finally {
                        refreshingCities.remove(normalizedSlug);
                    }
                });
            }
            return cached.data();
        }

        return refreshCityCacheSync(normalizedSlug);
    }

    private List<StationTelemetryDTO> refreshCityCacheSync(String citySlug) {
        ReentrantLock cityLock = cityFetchLocks.computeIfAbsent(citySlug, k -> new ReentrantLock());
        cityLock.lock();
        try {
            CachedCityTelemetry existing = telemetryCache.get(citySlug);
            if (existing != null && !existing.data().isEmpty() && Instant.now().isBefore(existing.expiresAt())) {
                return existing.data();
            }

            List<MonitoringStation> candidateStations = resolveCandidateStationsForCity(citySlug);
            LocalDate today = LocalDate.now();
            List<StationTelemetryDTO> activeData = new ArrayList<>();
            int activeFluviometric = 0;
            int activePluviometric = 0;

            for (MonitoringStation station : candidateStations) {
                boolean isFluviometric = "Fluviometrica".equalsIgnoreCase(station.getStationType());

                if (isFluviometric && activeFluviometric >= MAX_ACTIVE_FLUVIOMETRIC_PER_CITY) {
                    continue;
                }
                if (!isFluviometric && activePluviometric >= MAX_ACTIVE_PLUVIOMETRIC_PER_CITY) {
                    continue;
                }

                // Se esta estação já foi testada recentemente por outra cidade e estava sem leituras hoje na ANA, pula em 0ms
                if (isStationKnownEmpty(station.getStationCode())) {
                    continue;
                }

                StationTelemetryDTO dto = fetchRealStationTelemetry(station, today);
                if (dto != null) {
                    activeData.add(dto);
                    if (isFluviometric) {
                        activeFluviometric++;
                    } else {
                        activePluviometric++;
                    }
                }

                if (activeFluviometric >= MAX_ACTIVE_FLUVIOMETRIC_PER_CITY
                        && activePluviometric >= MAX_ACTIVE_PLUVIOMETRIC_PER_CITY) {
                    break;
                }
            }

            if (!activeData.isEmpty()) {
                telemetryCache.put(
                        citySlug,
                        new CachedCityTelemetry(activeData, Instant.now().plus(CACHE_TTL_MINUTES, ChronoUnit.MINUTES))
                );
                return activeData;
            }

            return existing != null ? existing.data() : Collections.emptyList();
        } finally {
            cityLock.unlock();
        }
    }

    private List<MonitoringStation> resolveCandidateStationsForCity(String citySlug) {
        List<MonitoringStation> cachedStations = discoveredStationsCache.get(citySlug);
        if (cachedStations != null && cachedStations.size() >= 6) {
            return cachedStations;
        }

        Map<String, MonitoringStation> mergedByCode = new LinkedHashMap<>();

        cityRepository.findBySlug(citySlug).ifPresent(city -> {
            if (city.getStations() != null) {
                for (MonitoringStation s : city.getStations()) {
                    mergedByCode.put(s.getStationCode().trim(), s);
                }
            }

            List<MonitoringStation> discovered = anaClientService.discoverStationsForCity(city);
            for (MonitoringStation d : discovered) {
                mergedByCode.putIfAbsent(d.getStationCode().trim(), d);
            }
        });

        List<MonitoringStation> result = new ArrayList<>(mergedByCode.values());
        // Só fixa no cache se o inventário da ANA respondeu com as candidatas da bacia
        if (result.size() >= 6) {
            discoveredStationsCache.put(citySlug, result);
        }

        return result;
    }

    private boolean isStationKnownEmpty(String stationCode) {
        Instant expiresAt = emptyStationCache.get(stationCode.trim());
        return expiresAt != null && Instant.now().isBefore(expiresAt);
    }

    /**
     * Busca exclusivamente dados reais da estação na ANA (ou reaproveita a leitura real
     * caso uma cidade vizinha já tenha consultado o mesmo sensor nos últimos 10 minutos).
     * Retorna null se a estação não possuir dados reais transmitidos.
     */
    private StationTelemetryDTO fetchRealStationTelemetry(MonitoringStation station, LocalDate searchDate) {
        String code = station.getStationCode().trim();

        // 1. Reaproveita leitura real já baixada por município vizinho na mesma bacia
        CachedStationReading shared = singleStationCache.get(code);
        if (shared != null && Instant.now().isBefore(shared.expiresAt())) {
            StationTelemetryDTO base = shared.dto();
            return new StationTelemetryDTO(
                    code,
                    station.getName(),
                    station.getStationType(),
                    station.getRiverName(),
                    station.getLatitude(),
                    station.getLongitude(),
                    base.currentLevelMeters(),
                    base.currentFlowM3s(),
                    base.accumulatedRain24hMm(),
                    base.status(),
                    base.thresholdWarning(),
                    base.thresholdAlert(),
                    base.thresholdCritical(),
                    base.lastMeasuredAt(),
                    base.history()
            );
        }

        List<AnaTelemetryItemDTO> rawItems = Collections.emptyList();
        boolean networkError = false;

        if (anaClientService.isCircuitOpen()) {
            networkError = true;
        } else {
            try {
                AnaTelemetryResponseDTO response = anaClientService.getStationTelemetry(
                        code,
                        searchDate,
                        "HORA_24"
                );

                if (response != null && response.items() != null) {
                    rawItems = response.items();
                }
            } catch (Exception e) {
                networkError = true;
                log.warn("Falha de rede ao consultar estação {} na ANA: {}", code, e.getMessage());
            }
        }

        List<TelemetryReadingDTO> history = rawItems.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(AnaTelemetryItemDTO::measuredAt, Comparator.nullsLast(String::compareTo)))
                .map(item -> {
                    String rawLevel = item.adoptedLevelCm() != null ? item.adoptedLevelCm() : item.sensorLevelCm();
                    return new TelemetryReadingDTO(
                            item.measuredAt(),
                            parseCmToMeters(rawLevel),
                            parseDouble(item.adoptedFlowM3s()),
                            parseDouble(item.adoptedRainMm())
                    );
                })
                .toList();

        boolean isFluviometric = "Fluviometrica".equalsIgnoreCase(station.getStationType());
        boolean hasValidLevelReading = history.stream().anyMatch(h -> h.levelMeters() != null);

        // Se a estação não retornou dados reais na janela de 24h
        if (history.isEmpty() || (isFluviometric && !hasValidLevelReading)) {
            if (!networkError) {
                // A ANA respondeu HTTP 200 OK mas esta estação não transmitiu pacotes hoje:
                // marca como vazia por 30 min para pular direto para a próxima estação real do raio
                emptyStationCache.put(code, Instant.now().plus(EMPTY_STATION_TTL_MINUTES, ChronoUnit.MINUTES));
            }
            // Se já havia uma leitura real anterior salva em memória antes de uma oscilação de rede, devolve ela;
            // caso contrário, retorna null (nunca inventa dados artificiais)
            return lastValidStationCache.get(code);
        }

        TelemetryReadingDTO latest = history.get(history.size() - 1);

        Double currentLevelMeters = latest.levelMeters();
        if (currentLevelMeters == null && isFluviometric) {
            for (int i = history.size() - 1; i >= 0; i--) {
                if (history.get(i).levelMeters() != null) {
                    currentLevelMeters = history.get(i).levelMeters();
                    break;
                }
            }
        }

        Double currentFlowM3s = latest.flowM3s();
        if (currentFlowM3s == null && isFluviometric) {
            for (int i = history.size() - 1; i >= 0; i--) {
                if (history.get(i).flowM3s() != null) {
                    currentFlowM3s = history.get(i).flowM3s();
                    break;
                }
            }
        }

        String lastMeasuredAt = latest.measuredAt();

        double accumulatedRain24h = history.stream()
                .map(TelemetryReadingDTO::rainMm)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .sum();

        double warningCm = station.getThresholdWarning();
        double alertCm = station.getThresholdAlert();
        double criticalCm = station.getThresholdCritical();

        // Ajuste de escala para sensores de barragens que reportam cota altimétrica (ex: 19m, 220m, 319m)
        if (isFluviometric && currentLevelMeters != null && currentLevelMeters > 12.0 && warningCm < 1200.0) {
            double minLevelMeters = history.stream()
                    .map(TelemetryReadingDTO::levelMeters)
                    .filter(Objects::nonNull)
                    .mapToDouble(Double::doubleValue)
                    .min()
                    .orElse(currentLevelMeters);
            warningCm = Math.round((minLevelMeters + 1.5) * 100.0);
            alertCm = Math.round((minLevelMeters + 2.5) * 100.0);
            criticalCm = Math.round((minLevelMeters + 3.5) * 100.0);
        }

        Double metricForStatus = isFluviometric
                ? (currentLevelMeters != null ? currentLevelMeters * 100.0 : 0.0)
                : accumulatedRain24h;

        String status = evaluateStatus(metricForStatus, warningCm, alertCm, criticalCm);

        StationTelemetryDTO dto = new StationTelemetryDTO(
                code,
                station.getName(),
                station.getStationType(),
                station.getRiverName(),
                station.getLatitude(),
                station.getLongitude(),
                currentLevelMeters,
                currentFlowM3s,
                Math.round(accumulatedRain24h * 100.0) / 100.0,
                status,
                isFluviometric ? warningCm / 100.0 : warningCm,
                isFluviometric ? alertCm / 100.0 : alertCm,
                isFluviometric ? criticalCm / 100.0 : criticalCm,
                lastMeasuredAt,
                history
        );

        lastValidStationCache.put(code, dto);
        singleStationCache.put(code, new CachedStationReading(dto, Instant.now().plus(CACHE_TTL_MINUTES, ChronoUnit.MINUTES)));
        return dto;
    }

    private String evaluateStatus(double value, double warning, double alert, double critical) {
        if (value >= critical) return "CRITICAL";
        if (value >= alert) return "ALERT";
        if (value >= warning) return "WARNING";
        return "NORMAL";
    }

    private Double parseCmToMeters(String cmValue) {
        Double cm = parseDouble(cmValue);
        return cm != null ? Math.round((cm / 100.0) * 100.0) / 100.0 : null;
    }

    private Double parseDouble(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}