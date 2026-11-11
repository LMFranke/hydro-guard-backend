package com.hydroguard.backend.dtos;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RiskZoneRequestDTO(
        @NotBlank String name,
        @NotBlank String zoneType,
        @NotBlank String riskLevel,
        @NotNull Double latitude,
        @NotNull Double longitude,
        @NotNull @Min(10) Integer radius,
        @NotBlank String description
) {}