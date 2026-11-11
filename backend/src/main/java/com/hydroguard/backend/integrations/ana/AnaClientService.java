package com.hydroguard.backend.integrations.ana;

import com.hydroguard.backend.integrations.ana.dto.AnaAuthResponseDTO;
import com.hydroguard.backend.integrations.ana.dto.AnaInventoryResponseDTO;
import com.hydroguard.backend.integrations.ana.dto.AnaTelemetryResponseDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Service
public class AnaClientService {

    private final RestClient restClient;
    private final String baseUrl;
    private final String identifier;
    private final String password;

    private String cachedToken;
    private Instant tokenExpiration;
    private final ReentrantLock authLock = new ReentrantLock();

    public AnaClientService(
            @Value("${api.ana.base-url}") String baseUrl,
            @Value("${api.ana.identifier}") String identifier,
            @Value("${api.ana.password}") String password
    ) {
        this.baseUrl = baseUrl;
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.identifier = identifier;
        this.password = password;
    }

    public String getValidToken() {
        if (isTokenValid()) {
            return cachedToken;
        }

        authLock.lock();
        try {
            if (isTokenValid()) {
                return cachedToken;
            }

            AnaAuthResponseDTO response = restClient.get()
                    .uri("/OAUth/v1")
                    .header("Identificador", identifier)
                    .header("Senha", password)
                    .retrieve()
                    .body(AnaAuthResponseDTO.class);

            if (response == null || response.items() == null || response.items().tokenautenticacao() == null) {
                throw new RuntimeException("Failed to authenticate with ANA API");
            }

            this.cachedToken = response.items().tokenautenticacao();
            this.tokenExpiration = Instant.now().plus(55, ChronoUnit.MINUTES);
            log.info("Successfully obtained new ANA API token");
            return this.cachedToken;
        } finally {
            authLock.unlock();
        }
    }

    public AnaTelemetryResponseDTO getStationTelemetry(String stationCode, LocalDate searchDate, String rangeInterval) {
        String token = getValidToken();

        URI uri = UriComponentsBuilder.fromUriString(baseUrl + "/HidroinfoanaSerieTelemetricaDetalhada/v1")
                .queryParam("Código da Estação", stationCode)
                .queryParam("Tipo Filtro Data", "DATA_LEITURA")
                .queryParam("Data de Busca (yyyy-MM-dd)", searchDate.toString())
                .queryParam("Range Intervalo de busca", rangeInterval)
                .encode(StandardCharsets.UTF_8)
                .build()
                .toUri();

        return restClient.get()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .body(AnaTelemetryResponseDTO.class);
    }

    public List<AnaInventoryResponseDTO.AnaStationInventoryItemDTO> discoverActiveItajaiStations() {
        String token = getValidToken();

        URI uri = UriComponentsBuilder.fromUriString(baseUrl + "/HidroInventarioEstacoes/v1")
                .queryParam("Unidade Federativa", "SC")
                .queryParam("Código da Bacia", "8")
                .encode(StandardCharsets.UTF_8)
                .build()
                .toUri();

        var response = restClient.get()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .body(com.hydroguard.backend.integrations.ana.dto.AnaInventoryResponseDTO.class);

        if (response == null || response.items() == null) {
            return java.util.Collections.emptyList();
        }

        return response.items().stream()
                .filter(item -> "83".equals(item.subBasinCode()))
                .filter(item -> "1".equals(item.operating()))
                .filter(item -> "1".equals(item.isTelemetric()))
                .filter(item -> item.telemetricEndDate() == null)
                .filter(item -> "Fluviometrica".equalsIgnoreCase(item.stationType()))
                .toList();
    }

    private boolean isTokenValid() {
        return cachedToken != null && tokenExpiration != null && Instant.now().isBefore(tokenExpiration);
    }
}