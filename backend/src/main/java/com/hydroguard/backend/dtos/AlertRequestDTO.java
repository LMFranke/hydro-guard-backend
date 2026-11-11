package com.hydroguard.backend.dtos;

import jakarta.validation.constraints.NotBlank;

public record AlertRequestDTO(
        @NotBlank String title,
        @NotBlank String severity,
        @NotBlank String region,
        @NotBlank String description,
        @NotBlank String safetyGuidelines
) {}