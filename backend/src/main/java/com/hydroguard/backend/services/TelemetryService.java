package com.hydroguard.backend.services;

import com.hydroguard.backend.dtos.StationTelemetryDTO;
import com.hydroguard.backend.dtos.StationTelemetryDTO.TelemetryReadingDTO;
import com.hydroguard.backend.integrations.ana.AnaClientService;
import com.hydroguard.backend.integrations.ana.dto.AnaTelemetryResponseDTO;
import com.hydroguard.backend.integrations.ana.dto.AnaTelemetryResponseDTO.AnaTelemetryItemDTO;
import com.hydroguard.backend.models.MonitoringStation;
import com.hydroguard.backend.repositories.MonitoringStationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class TelemetryService {

    private final MonitoringStationRepository stationRepository;
    private final AnaClientService anaClientService;

    private final Map<String, CachedCityTelemetry> telemetryCache = new ConcurrentHashMap<>();

    private record CachedCityTelemetry(List<StationTelemetryDTO> data, Instant expiresAt) {}

    public List<StationTelemetryDTO> getCityTelemetry(String citySlug) {
        CachedCityTelemetry cached = telemetryCache.get(citySlug);
        if (cached != null && Instant.now().isBefore(cached.expiresAt())) {
            return cached.data();
        }

        List<MonitoringStation> stations = stationRepository.findByCitySlug(citySlug);
        LocalDate today = LocalDate.now();

        List<StationTelemetryDTO> freshData = stations.parallelStream()
                .map(station -> buildStationTelemetry(station, today))
                .toList();

        if (!freshData.isEmpty()) {
            telemetryCache.put(citySlug, new CachedCityTelemetry(freshData, Instant.now().plus(5, ChronoUnit.MINUTES)));
        }

        return freshData;
    }

    private StationTelemetryDTO buildStationTelemetry(MonitoringStation station, LocalDate searchDate) {
        List<AnaTelemetryItemDTO> rawItems = Collections.emptyList();

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
            log.error("Failed to fetch ANA telemetry for station {}: {}", station.getStationCode(), e.getMessage());
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

        TelemetryReadingDTO latest = history.isEmpty() ? null : history.get(history.size() - 1);

        Double currentLevelMeters = latest != null ? latest.levelMeters() : null;
        Double currentFlowM3s = latest != null ? latest.flowM3s() : null;
        String lastMeasuredAt = latest != null ? latest.measuredAt() : null;

        double accumulatedRain24h = history.stream()
                .map(TelemetryReadingDTO::rainMm)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .sum();

        boolean isFluviometric = "Fluviometrica".equalsIgnoreCase(station.getStationType());
        Double metricForStatus = isFluviometric
                ? (currentLevelMeters != null ? currentLevelMeters * 100.0 : 0.0)
                : accumulatedRain24h;

        String status = evaluateStatus(
                metricForStatus,
                station.getThresholdWarning(),
                station.getThresholdAlert(),
                station.getThresholdCritical()
        );

        return new StationTelemetryDTO(
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
                isFluviometric ? station.getThresholdWarning() / 100.0 : station.getThresholdWarning(),
                isFluviometric ? station.getThresholdAlert() / 100.0 : station.getThresholdAlert(),
                isFluviometric ? station.getThresholdCritical() / 100.0 : station.getThresholdCritical(),
                lastMeasuredAt,
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