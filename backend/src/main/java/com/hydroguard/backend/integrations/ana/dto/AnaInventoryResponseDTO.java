package com.hydroguard.backend.integrations.ana.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AnaInventoryResponseDTO(
        String status,
        Integer code,
        String message,
        List<AnaStationInventoryItemDTO> items
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AnaStationInventoryItemDTO(
            @JsonProperty("codigoestacao") String stationCode,
            @JsonProperty("Estacao_Nome") String stationName,
            @JsonProperty("Municipio_Nome") String municipalityName,
            @JsonProperty("Rio_Nome") String riverName,
            @JsonProperty("Rio_Codigo") String riverCode,
            @JsonProperty("Sub_Bacia_Codigo") String subBasinCode,
            @JsonProperty("Tipo_Estacao") String stationType,
            @JsonProperty("Tipo_Estacao_Telemetrica") String isTelemetric,
            @JsonProperty("Operando") String operating,
            @JsonProperty("Data_Periodo_Telemetrica_Fim") String telemetricEndDate,
            @JsonProperty("Data_Ultima_Atualizacao") String lastUpdate,
            @JsonProperty("Operadora_Sigla") String operatorAcronym,
            @JsonProperty("Latitude") String latitude,
            @JsonProperty("Longitude") String longitude,
            @JsonProperty("Altitude") String altitude
    ) {}
}