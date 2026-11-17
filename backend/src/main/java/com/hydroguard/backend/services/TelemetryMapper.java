package com.hydroguard.backend.services;

import com.hydroguard.backend.dtos.StationTelemetryDTO;
import com.hydroguard.backend.dtos.StationTelemetryDTO.TelemetryReadingDTO;
import com.hydroguard.backend.integrations.ana.dto.AnaTelemetryResponseDTO.AnaTelemetryItemDTO;
import com.hydroguard.backend.models.MonitoringStation;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Component
public class TelemetryMapper {

    public List<TelemetryReadingDTO> mapRawItemsToHistory(List<AnaTelemetryItemDTO> rawItems) {
        if (rawItems == null || rawItems.isEmpty()) {
            return List.of();
        }

        return rawItems.stream()
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
    }

    public boolean hasValidTelemetryForStationType(MonitoringStation station, List<TelemetryReadingDTO> history) {
        if (history.isEmpty()) return false;
        boolean isFluviometric = "Fluviometrica".equalsIgnoreCase(station.getStationType());
        return !isFluviometric || history.stream().anyMatch(h -> h.levelMeters() != null);
    }

    public StationTelemetryDTO buildStationTelemetryDTO(MonitoringStation station, List<TelemetryReadingDTO> history) {
        boolean isFluviometric = "Fluviometrica".equalsIgnoreCase(station.getStationType());
        TelemetryReadingDTO latest = history.get(history.size() - 1);

        Double currentLevelMeters = resolveLatestNonNullLevel(history, isFluviometric);
        Double currentFlowM3s = resolveLatestNonNullFlow(history, isFluviometric);

        double accumulatedRain24h = history.stream()
                .map(TelemetryReadingDTO::rainMm)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .sum();

        double warningCm = station.getThresholdWarning();
        double alertCm = station.getThresholdAlert();
        double criticalCm = station.getThresholdCritical();

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

        double metricForStatus = isFluviometric
                ? (currentLevelMeters != null ? currentLevelMeters * 100.0 : 0.0)
                : accumulatedRain24h;

        String status = evaluateStatus(metricForStatus, warningCm, alertCm, criticalCm);

        return new StationTelemetryDTO(
                station.getStationCode().trim(),
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
                latest.measuredAt(),
                history
        );
    }

    public StationTelemetryDTO adaptSharedTelemetryToCityStation(MonitoringStation station, StationTelemetryDTO base) {
        return new StationTelemetryDTO(
                station.getStationCode().trim(),
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

    private Double resolveLatestNonNullLevel(List<TelemetryReadingDTO> history, boolean isFluviometric) {
        if (!isFluviometric) return null;
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i).levelMeters() != null) {
                return history.get(i).levelMeters();
            }
        }
        return null;
    }

    private Double resolveLatestNonNullFlow(List<TelemetryReadingDTO> history, boolean isFluviometric) {
        if (!isFluviometric) return null;
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i).flowM3s() != null) {
                return history.get(i).flowM3s();
            }
        }
        return null;
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