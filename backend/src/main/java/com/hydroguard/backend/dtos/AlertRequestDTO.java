package com.hydroguard.backend.dtos;

import jakarta.validation.constraints.NotBlank;

import java.time.LocalDateTime;

public record AlertRequestDTO(
        @NotBlank String title,
        @NotBlank String severity,
        @NotBlank String region,
        @NotBlank String description,
        @NotBlank String safetyGuidelines,
        String source,
        Integer validityHours,
        LocalDateTime expiresAt,
        Double latitude,
        Double longitude
) {}