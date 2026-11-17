package com.hydroguard.backend.integrations.ana;

import com.hydroguard.backend.dtos.SystemHealthDTO;
import com.hydroguard.backend.dtos.SystemHealthDTO.ServiceStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Component
public class AnaResilienceManager {

    private static final long MIN_REQUEST_INTERVAL_MS = 250;
    private static final long HIGH_LATENCY_THRESHOLD_MS = 8500;

    private final ReentrantLock paceLock = new ReentrantLock();
    private volatile long lastRequestTimestampMs = 0;

    private final AtomicBoolean simulatedOutageActive = new AtomicBoolean(false);
    private volatile Instant circuitOpenUntil = Instant.EPOCH;
    private volatile ServiceStatus lastKnownAnaStatus = ServiceStatus.ONLINE;
    private volatile Long lastLatencyMs = null;
    private volatile Instant lastSuccessAt = null;
    private volatile String lastStatusMessage = "Integração ANA operacional.";
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);

    public boolean isSimulatedOutageActive() {
        return simulatedOutageActive.get();
    }

    public void setSimulatedOutage(boolean active) {
        this.simulatedOutageActive.set(active);
        if (active) {
            log.warn("[Fault Injection] Simulação de indisponibilidade da API da ANA ATIVADA pelo operador.");
        } else {
            log.info("[Fault Injection] Simulação de indisponibilidade da API da ANA DESATIVADA. Restaurando operação.");
        }
    }

    public boolean isCircuitOpen() {
        return simulatedOutageActive.get() || Instant.now().isBefore(circuitOpenUntil);
    }

    public void enforceRequestPacing() {
        paceLock.lock();
        try {
            long now = System.currentTimeMillis();
            long elapsed = now - lastRequestTimestampMs;
            if (elapsed < MIN_REQUEST_INTERVAL_MS) {
                Thread.sleep(MIN_REQUEST_INTERVAL_MS - elapsed);
            }
            lastRequestTimestampMs = System.currentTimeMillis();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } finally {
            paceLock.unlock();
        }
    }

    public void openCircuit(long cooldownSeconds, String reason) {
        this.circuitOpenUntil = Instant.now().plusSeconds(cooldownSeconds);
        this.consecutiveFailures.incrementAndGet();
        this.lastKnownAnaStatus = ServiceStatus.OFFLINE;
        this.lastStatusMessage = reason;
        log.warn("ANA Circuit Breaker OPENED for {}s. Reason: {}", cooldownSeconds, reason);
    }

    public void recordSuccess(long latencyMs) {
        this.circuitOpenUntil = Instant.EPOCH;
        this.lastLatencyMs = latencyMs;
        this.lastSuccessAt = Instant.now();
        int failuresReset = this.consecutiveFailures.getAndSet(0);

        if (latencyMs > HIGH_LATENCY_THRESHOLD_MS) {
            this.lastKnownAnaStatus = ServiceStatus.INSTABLE;
            this.lastStatusMessage = "API da ANA respondendo com alta latência (" + latencyMs + " ms).";
        } else {
            this.lastKnownAnaStatus = ServiceStatus.ONLINE;
            this.lastStatusMessage = failuresReset > 0
                    ? "Conexão com a ANA restabelecida."
                    : "Telemetria ANA operacional.";
        }
    }

    public void recordFailure(String reason) {
        int failures = this.consecutiveFailures.incrementAndGet();

        if (failures >= 8) {
            openCircuit(30, reason);
        } else if (failures >= 4) {
            this.lastKnownAnaStatus = ServiceStatus.INSTABLE;
            this.lastStatusMessage = "Oscilação em algumas estações da ANA (" + reason + ").";
        }
    }

    public void restoreOnlineIfTokenValid() {
        if (!isCircuitOpen() && this.lastKnownAnaStatus == ServiceStatus.OFFLINE) {
            this.lastKnownAnaStatus = ServiceStatus.ONLINE;
            this.lastStatusMessage = "Telemetria ANA operacional.";
        }
    }

    public SystemHealthDTO buildHealthSnapshot() {
        if (simulatedOutageActive.get()) {
            return new SystemHealthDTO(
                    ServiceStatus.ONLINE,
                    ServiceStatus.OFFLINE,
                    null,
                    Instant.now(),
                    this.lastSuccessAt,
                    "Falha de comunicação com o provedor externo (Webservice ANA inacessível / Modo de Contingência ativo).",
                    true
            );
        }

        return new SystemHealthDTO(
                ServiceStatus.ONLINE,
                this.lastKnownAnaStatus,
                this.lastLatencyMs,
                Instant.now(),
                this.lastSuccessAt,
                this.lastStatusMessage,
                false
        );
    }
}