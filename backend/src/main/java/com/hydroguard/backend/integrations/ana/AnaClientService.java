package com.hydroguard.backend.integrations.ana;

import com.hydroguard.backend.dtos.SystemHealthDTO;
import com.hydroguard.backend.integrations.ana.dto.AnaAuthResponseDTO;
import com.hydroguard.backend.integrations.ana.dto.AnaInventoryResponseDTO;
import com.hydroguard.backend.integrations.ana.dto.AnaInventoryResponseDTO.AnaStationInventoryItemDTO;
import com.hydroguard.backend.integrations.ana.dto.AnaTelemetryResponseDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Service
public class AnaClientService {

    private static final long TOKEN_TTL_MINUTES = 52;

    private final RestClient restClient;
    private final String baseUrl;
    private final String identifier;
    private final String password;
    private final AnaResilienceManager resilience;

    private volatile String cachedToken;
    private volatile Instant tokenExpiration = Instant.EPOCH;
    private volatile Instant lastAuthAttemptAt = Instant.EPOCH;
    private final ReentrantLock authLock = new ReentrantLock();

    public AnaClientService(
            @Value("${api.ana.base-url}") String baseUrl,
            @Value("${api.ana.identifier}") String identifier,
            @Value("${api.ana.password}") String password,
            AnaResilienceManager resilience
    ) {
        this.baseUrl = baseUrl;
        this.identifier = identifier;
        this.password = password;
        this.resilience = resilience;

        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(8))
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(18));

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.USER_AGENT, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) HydroGuard/1.0")
                .defaultHeader(HttpHeaders.ACCEPT, "application/json")
                .build();
    }

    public boolean isCircuitOpen() {
        return resilience.isCircuitOpen();
    }

    public String getValidToken() {
        if (isTokenValid()) {
            return cachedToken;
        }
        if (resilience.isCircuitOpen()) {
            throw new RuntimeException("ANA API Circuit Breaker is OPEN (Fast-Fail)");
        }

        authLock.lock();
        try {
            if (isTokenValid()) {
                return cachedToken;
            }
            if (resilience.isCircuitOpen()) {
                throw new RuntimeException("ANA API Circuit Breaker is OPEN (Fast-Fail)");
            }
            if (Instant.now().isBefore(lastAuthAttemptAt.plusSeconds(15))) {
                throw new RuntimeException("Aguardando janela mínima de segurança entre autenticações na ANA.");
            }

            Exception lastException = null;

            for (int attempt = 1; attempt <= 2; attempt++) {
                this.lastAuthAttemptAt = Instant.now();
                resilience.enforceRequestPacing();
                long startNanos = System.nanoTime();

                try {
                    AnaAuthResponseDTO response = restClient.get()
                            .uri("/OAUth/v1")
                            .header("Identificador", identifier)
                            .header("Senha", password)
                            .retrieve()
                            .body(AnaAuthResponseDTO.class);

                    long elapsedMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();

                    if (response == null || response.items() == null || response.items().tokenautenticacao() == null) {
                        resilience.openCircuit(30, "Resposta de autenticação inválida da ANA.");
                        throw new RuntimeException("Failed to authenticate with ANA API");
                    }

                    this.cachedToken = response.items().tokenautenticacao();
                    this.tokenExpiration = Instant.now().plus(TOKEN_TTL_MINUTES, ChronoUnit.MINUTES);
                    resilience.recordSuccess(elapsedMs);
                    log.info("[ANA Auth] Novo token obtido na tentativa {}/2 ({} ms).", attempt, elapsedMs);

                    return this.cachedToken;
                } catch (HttpStatusCodeException httpEx) {
                    lastException = httpEx;
                    int statusCode = httpEx.getStatusCode().value();

                    if (statusCode == 429) {
                        resilience.openCircuit(90, "Limite de requisições atingido no /OAUth/v1 (aguardando 90s).");
                        break;
                    }
                    resilience.openCircuit(30, "Erro HTTP " + statusCode + " na autenticação da ANA.");
                    break;
                } catch (ResourceAccessException ioEx) {
                    lastException = ioEx;
                    log.warn("[ANA Auth] Timeout na tentativa {}/2 do /OAUth/v1: {}", attempt, ioEx.getMessage());
                    if (attempt == 1) {
                        sleepQuietly(1200);
                    } else {
                        resilience.openCircuit(30, "Timeout ao renovar token no webservice da ANA.");
                    }
                } catch (Exception ex) {
                    lastException = ex;
                    resilience.openCircuit(30, "Falha inesperada na autenticação com a ANA.");
                    break;
                }
            }

            throw new RuntimeException("Não foi possível obter token da ANA", lastException);
        } finally {
            authLock.unlock();
        }
    }

    public AnaTelemetryResponseDTO getStationTelemetry(String stationCode, LocalDate searchDate, String rangeInterval) {
        if (resilience.isCircuitOpen()) {
            throw new RuntimeException("ANA Circuit Breaker OPEN - skipping station " + stationCode);
        }

        String token = getValidToken();
        resilience.enforceRequestPacing();
        long startNanos = System.nanoTime();

        try {
            URI uri = UriComponentsBuilder.fromUriString(baseUrl + "/HidroinfoanaSerieTelemetricaDetalhada/v1")
                    .queryParam("Código da Estação", stationCode)
                    .queryParam("Tipo Filtro Data", "DATA_LEITURA")
                    .queryParam("Data de Busca (yyyy-MM-dd)", searchDate.toString())
                    .queryParam("Range Intervalo de busca", rangeInterval)
                    .encode(StandardCharsets.UTF_8)
                    .build()
                    .toUri();

            AnaTelemetryResponseDTO response = restClient.get()
                    .uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .retrieve()
                    .body(AnaTelemetryResponseDTO.class);

            long elapsedMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
            resilience.recordSuccess(elapsedMs);
            return response;
        } catch (HttpStatusCodeException httpEx) {
            int code = httpEx.getStatusCode().value();
            if (code == 401 || code == 403) {
                invalidateToken();
            } else if (code == 429) {
                resilience.openCircuit(60, "Rate limit (HTTP 429) atingido na telemetria da ANA.");
            } else {
                resilience.recordFailure("HTTP " + code + " na estação " + stationCode);
            }
            throw httpEx;
        } catch (Exception e) {
            resilience.recordFailure("Falha na leitura da estação " + stationCode);
            throw e;
        }
    }

    public List<AnaStationInventoryItemDTO> fetchInventoryByStateAndBasin(String stateUf, String basinCode) {
        String token = getValidToken();
        resilience.enforceRequestPacing();

        URI uri = UriComponentsBuilder.fromUriString(baseUrl + "/HidroInventarioEstacoes/v1")
                .queryParam("Unidade Federativa", stateUf)
                .queryParam("Código da Bacia", basinCode)
                .encode(StandardCharsets.UTF_8)
                .build()
                .toUri();

        long startNanos = System.nanoTime();
        try {
            AnaInventoryResponseDTO response = restClient.get()
                    .uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .retrieve()
                    .body(AnaInventoryResponseDTO.class);

            long elapsedMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
            resilience.recordSuccess(elapsedMs);

            return (response != null && response.items() != null)
                    ? response.items()
                    : Collections.emptyList();
        } catch (HttpStatusCodeException httpEx) {
            int code = httpEx.getStatusCode().value();
            if (code == 401 || code == 403) {
                invalidateToken();
            }
            throw httpEx;
        }
    }

    public SystemHealthDTO checkAnaHealth() {
        if (!resilience.isCircuitOpen()) {
            if (!isTokenValid()) {
                try {
                    getValidToken();
                } catch (Exception e) {
                    log.debug("Health check unable to refresh expired token: {}", e.getMessage());
                }
            } else {
                resilience.restoreOnlineIfTokenValid();
            }
        }
        return resilience.buildHealthSnapshot();
    }

    public void invalidateToken() {
        this.cachedToken = null;
        this.tokenExpiration = Instant.EPOCH;
    }

    private boolean isTokenValid() {
        return cachedToken != null && tokenExpiration != null && Instant.now().isBefore(tokenExpiration);
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}