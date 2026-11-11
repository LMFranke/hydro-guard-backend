package com.hydroguard.backend.integrations.ana.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AnaTelemetryResponseDTO(
        String status,
        Integer code,
        String message,
        List<AnaTelemetryItemDTO> items
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AnaTelemetryItemDTO(
            @JsonProperty("codigoestacao") String stationCode,
            @JsonProperty("Data_Hora_Medicao") String measuredAt,
            @JsonProperty("Data_Atualizacao") String updatedAt,
            @JsonProperty("Chuva_Adotada") String adoptedRainMm,
            @JsonProperty("Chuva_Acumulada") String accumulatedRainMm,
            @JsonProperty("Cota_Adotada") String adoptedLevelCm,
            @JsonProperty("Cota_Sensor") String sensorLevelCm,
            @JsonProperty("Vazao_Adotada") String adoptedFlowM3s
    ) {}
}