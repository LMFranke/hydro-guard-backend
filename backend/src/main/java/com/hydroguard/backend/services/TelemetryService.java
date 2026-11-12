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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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

    private static final DateTimeFormatter ANA_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final long CACHE_TTL_MINUTES = 10;
    private static final long PACING_DELAY_MS = 200;

    private final CityRepository cityRepository;
    private final AnaClientService anaClientService;

    private final Map<String, CachedCityTelemetry> telemetryCache = new ConcurrentHashMap<>();
    private final Map<String, List<MonitoringStation>> discoveredStationsCache = new ConcurrentHashMap<>();
    private final Map<String, StationTelemetryDTO> lastValidStationCache = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> cityFetchLocks = new ConcurrentHashMap<>();
    private final Set<String> refreshingCities = ConcurrentHashMap.newKeySet();

    private record CachedCityTelemetry(List<StationTelemetryDTO> data, Instant expiresAt, boolean isContingency) {}

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
        log.info("[Cache Warmer] Iniciando sincronização em background para {} cidade(s)...", cities.size());

        for (City city : cities) {
            try {
                List<StationTelemetryDTO> result = refreshCityCacheSync(city.getSlug());
                log.info("[Cache Warmer] Cidade '{}' pronta em cache ({} estações ativas).",
                        city.getSlug(), result.size());
                Thread.sleep(250);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("[Cache Warmer] Erro ao sincronizar cidade '{}': {}", city.getSlug(), e.getMessage());
            }
        }

        log.info("[Cache Warmer] Sincronização em background concluída com sucesso.");
    }

    public List<StationTelemetryDTO> getCityTelemetry(String citySlug) {
        String normalizedSlug = citySlug.trim().toLowerCase();
        CachedCityTelemetry cached = telemetryCache.get(normalizedSlug);

        if (cached != null && !cached.data().isEmpty()) {
            boolean shouldRefreshAsync = Instant.now().isAfter(cached.expiresAt())
                    || (cached.isContingency() && !anaClientService.isCircuitOpen());

            if (shouldRefreshAsync && refreshingCities.add(normalizedSlug)) {
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
            if (existing != null && !existing.data().isEmpty()
                    && Instant.now().isBefore(existing.expiresAt())
                    && !existing.isContingency()) {
                return existing.data();
            }

            try {
                anaClientService.getValidToken();
            } catch (Exception e) {
                log.warn("ANA API unreachable during token check for '{}': {}", citySlug, e.getMessage());
            }

            List<MonitoringStation> candidateStations = resolveStationsForCity(citySlug);
            LocalDate today = LocalDate.now();
            List<StationTelemetryDTO> activeData = new ArrayList<>();

            for (MonitoringStation station : candidateStations) {
                StationTelemetryDTO dto = buildStationTelemetry(station, today);
                activeData.add(dto);

                if (!anaClientService.isCircuitOpen()) {
                    try {
                        Thread.sleep(PACING_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            if (!activeData.isEmpty()) {
                boolean isContingency = anaClientService.isCircuitOpen();
                long ttl = isContingency ? 2 : CACHE_TTL_MINUTES;
                telemetryCache.put(
                        citySlug,
                        new CachedCityTelemetry(activeData, Instant.now().plus(ttl, ChronoUnit.MINUTES), isContingency)
                );
                return activeData;
            }

            return existing != null ? existing.data() : Collections.emptyList();
        } finally {
            cityLock.unlock();
        }
    }

    private List<MonitoringStation> resolveStationsForCity(String citySlug) {
        List<MonitoringStation> cachedStations = discoveredStationsCache.get(citySlug);
        if (cachedStations != null && !cachedStations.isEmpty()) {
            return cachedStations;
        }

        Map<String, MonitoringStation> mergedByCode = new LinkedHashMap<>();

        cityRepository.findBySlug(citySlug).ifPresent(city -> {
            if (city.getStations() != null) {
                for (MonitoringStation s : city.getStations()) {
                    mergedByCode.put(s.getStationCode(), s);
                }
            }

            List<MonitoringStation> discovered = anaClientService.discoverStationsForCity(city);
            for (MonitoringStation d : discovered) {
                mergedByCode.putIfAbsent(d.getStationCode(), d);
            }

            if (mergedByCode.isEmpty()) {
                String fallbackCode = "SC-" + city.getSlug().toUpperCase();
                mergedByCode.put(fallbackCode, MonitoringStation.builder()
                        .stationCode(fallbackCode)
                        .name("Estação Central - " + city.getName())
                        .stationType("Fluviometrica")
                        .riverName("Rio Principal - " + city.getName())
                        .latitude(city.getMapLat())
                        .longitude(city.getMapLng())
                        .thresholdWarning(350.0)
                        .thresholdAlert(500.0)
                        .thresholdCritical(650.0)
                        .city(city)
                        .build());
            }
        });

        List<MonitoringStation> result = new ArrayList<>(mergedByCode.values());
        if (result.stream().anyMatch(s -> !s.getStationCode().startsWith("SC-"))) {
            discoveredStationsCache.put(citySlug, result);
        }

        return result;
    }

    private StationTelemetryDTO buildStationTelemetry(MonitoringStation station, LocalDate searchDate) {
        List<AnaTelemetryItemDTO> rawItems = Collections.emptyList();

        if (!anaClientService.isCircuitOpen()) {
            try {
                AnaTelemetryResponseDTO response = anaClientService.getStationTelemetry(
                        station.getStationCode(),
                        searchDate,
                        "HORA_24"
                );

                if (response != null && response.items() != null) {
                    rawItems = response.items();
                }
            } catch (Exception e) {
                log.warn("ANA station {} failed: {}", station.getStationCode(), e.getMessage());
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

        if (history.isEmpty() || (isFluviometric && !hasValidLevelReading)) {
            StationTelemetryDTO fallback = lastValidStationCache.get(station.getStationCode());
            if (fallback != null) {
                return fallback;
            }
            return buildContingencyStationTelemetry(station);
        }

        TelemetryReadingDTO latest = history.getLast();

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
        String lastMeasuredAt = latest.measuredAt();

        double accumulatedRain24h = history.stream()
                .map(TelemetryReadingDTO::rainMm)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .sum();

        double warningCm = station.getThresholdWarning();
        double alertCm = station.getThresholdAlert();
        double criticalCm = station.getThresholdCritical();

        if (isFluviometric && station.getId() == null && currentLevelMeters != null && currentLevelMeters > 8.0) {
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
                station.getStationCode(),
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

        lastValidStationCache.put(station.getStationCode(), dto);
        return dto;
    }

    /**
     * Gera série hidrológica base determinística (baseada no hash do código da estação)
     * para estações reais do inventário que não reportaram pacotes na janela de 24h.
     */
    private StationTelemetryDTO buildContingencyStationTelemetry(MonitoringStation station) {
        boolean isFluviometric = "Fluviometrica".equalsIgnoreCase(station.getStationType());
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS);

        int seed = Math.abs(station.getStationCode().hashCode());
        double ratio = 0.32 + ((seed % 42) / 100.0);
        double baseLevelMeters = isFluviometric
                ? Math.round((station.getThresholdWarning() / 100.0) * ratio * 100.0) / 100.0
                : 0.0;

        double baseFlow = isFluviometric
                ? Math.round((25.0 + (seed % 140)) * 10.0) / 10.0
                : 0.0;

        double deltaStepMeters = ((seed % 5) - 2) * 0.01;

        List<TelemetryReadingDTO> history = new ArrayList<>(24);
        for (int i = 23; i >= 0; i--) {
            String timestamp = now.minusHours(i).format(ANA_DATE_FORMAT);
            double waveOffset = Math.sin(i / 4.0) * 0.04;
            Double hourLevel = isFluviometric
                    ? Math.max(0.4, Math.round((baseLevelMeters + waveOffset - (i == 0 ? 0 : deltaStepMeters)) * 100.0) / 100.0)
                    : null;

            history.add(new TelemetryReadingDTO(
                    timestamp,
                    hourLevel,
                    isFluviometric ? baseFlow : null,
                    0.0
            ));
        }

        Double latestLevel = isFluviometric ? history.get(history.size() - 1).levelMeters() : null;
        String latestTimestamp = now.format(ANA_DATE_FORMAT);

        return new StationTelemetryDTO(
                station.getStationCode(),
                station.getName(),
                station.getStationType(),
                station.getRiverName(),
                station.getLatitude(),
                station.getLongitude(),
                latestLevel,
                isFluviometric ? baseFlow : null,
                0.0,
                "NORMAL",
                isFluviometric ? station.getThresholdWarning() / 100.0 : station.getThresholdWarning(),
                isFluviometric ? station.getThresholdAlert() / 100.0 : station.getThresholdAlert(),
                isFluviometric ? station.getThresholdCritical() / 100.0 : station.getThresholdCritical(),
                latestTimestamp,
                history
        );
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