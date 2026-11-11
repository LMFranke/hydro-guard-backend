package com.hydroguard.backend.dtos;

public record LoginResponseDTO(
        String token,
        String name,
        String email,
        String citySlug
) {}