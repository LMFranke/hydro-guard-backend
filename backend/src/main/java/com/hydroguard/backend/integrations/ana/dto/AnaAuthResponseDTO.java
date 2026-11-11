package com.hydroguard.backend.integrations.ana.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AnaAuthResponseDTO(
        String status,
        Integer code,
        String message,
        AnaAuthItemsDTO items
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AnaAuthItemsDTO(
            Boolean sucesso,
            String validade,
            String tokenautenticacao,
            String respostaautenticacao
    ) {}
}