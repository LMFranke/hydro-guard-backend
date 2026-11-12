package com.hydroguard.backend.dtos;

import java.time.Instant;

public record SystemHealthDTO(
        ServiceStatus backendStatus,
        ServiceStatus anaApiStatus,
        Long anaLatencyMs,
        Instant checkedAt,
        Instant lastAnaSuccessAt,
        String message
) {
    public enum ServiceStatus {
        ONLINE,
        INSTABLE,
        OFFLINE
    }
}