package com.hydroguard.backend.dtos;

import java.util.List;

public record StationTelemetryDTO(
        String stationCode,
        String stationName,
        String stationType,
        String riverName,
        Double latitude,
        Double longitude,
        Double currentLevelMeters,
        Double currentFlowM3s,
        Double accumulatedRain24hMm,
        String status,
        Double thresholdWarning,
        Double thresholdAlert,
        Double thresholdCritical,
        String lastMeasuredAt,
        List<TelemetryReadingDTO> history
) {
    public record TelemetryReadingDTO(
            String measuredAt,
            Double levelMeters,
            Double flowM3s,
            Double rainMm
    ) {}
}