package com.hydroguard.backend.services;

import com.hydroguard.backend.dtos.StationTelemetryDTO;
import com.hydroguard.backend.dtos.StationTelemetryDTO.TelemetryReadingDTO;
import com.hydroguard.backend.integrations.ana.AnaClientService;
import com.hydroguard.backend.integrations.ana.AnaResilienceManager;
import com.hydroguard.backend.integrations.ana.AnaStationDiscoveryService;
import com.hydroguard.backend.integrations.ana.dto.AnaTelemetryResponseDTO;
import com.hydroguard.backend.integrations.ana.dto.AnaTelemetryResponseDTO.AnaTelemetryItemDTO;
import com.hydroguard.backend.models.City;
import com.hydroguard.backend.models.MonitoringStation;
import com.hydroguard.backend.repositories.CityRepository;
import com.hydroguard.backend.services.TelemetryCacheManager.CachedCityTelemetry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Service
@RequiredArgsConstructor
public class TelemetryService {

    private static final int MAX_ACTIVE_FLUVIOMETRIC_PER_CITY = 8;
    private static final int MAX_ACTIVE_PLUVIOMETRIC_PER_CITY = 3;

    private final CityRepository cityRepository;
    private final AnaClientService anaClientService;
    private final AnaStationDiscoveryService discoveryService;
    private final TelemetryCacheManager cacheManager;
    private final TelemetryMapper telemetryMapper;
    private final AnaResilienceManager resilienceManager;

    private final Map<String, ReentrantLock> cityFetchLocks = new ConcurrentHashMap<>();
    private final Set<String> refreshingCities = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean warmerRunning = new AtomicBoolean(false);

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        CompletableFuture.runAsync(this::syncAllCitiesTelemetryInBackground);
    }

    @Scheduled(fixedDelay = 10 * 60 * 1000, initialDelay = 10 * 60 * 1000)
    public void scheduledTelemetrySync() {
        syncAllCitiesTelemetryInBackground();
    }

    public List<StationTelemetryDTO> getCityTelemetry(String citySlug) {
        if (resilienceManager.isSimulatedOutageActive()) {
            return Collections.emptyList();
        }

        String normalizedSlug = citySlug.trim().toLowerCase();
        Optional<CachedCityTelemetry> cached = cacheManager.getCityTelemetryEntry(normalizedSlug);

        if (cached.isPresent()) {
            if (cached.get().isExpired()) {
                triggerCitySyncAsync(normalizedSlug);
            }
            return cached.get().data();
        }

        if (warmerRunning.get()) {
            List<StationTelemetryDTO> memorySnapshot = assembleAvailableSnapshotFromMemory(normalizedSlug);
            if (!memorySnapshot.isEmpty()) {
                triggerCitySyncAsync(normalizedSlug);
                return memorySnapshot;
            }
        }

        return refreshCityCacheSync(normalizedSlug, false);
    }

    private void triggerCitySyncAsync(String citySlug) {
        if (refreshingCities.add(citySlug)) {
            CompletableFuture.runAsync(() -> {
                try {
                    refreshCityCacheSync(citySlug, true);
                } finally {
                    refreshingCities.remove(citySlug);
                }
            });
        }
    }

    private void syncAllCitiesTelemetryInBackground() {
        if (!warmerRunning.compareAndSet(false, true)) {
            return;
        }
        try {
            List<City> cities = cityRepository.findAll();
            log.info("[Cache Warmer] Pré-carregando inventário de SC e sincronizando {} municípios...", cities.size());

            discoveryService.preloadStateInventory("SC");

            for (City city : cities) {
                if (anaClientService.isCircuitOpen()) {
                    log.warn("[Cache Warmer] Disjuntor da ANA aberto. Pausando varredura.");
                    break;
                }
                try {
                    List<StationTelemetryDTO> result = refreshCityCacheSync(city.getSlug(), true);
                    log.info("[Cache Warmer] Município '{}' sincronizado ({} estações ativas).", city.getSlug(), result.size());
                    Thread.sleep(800);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.warn("[Cache Warmer] Erro ao sincronizar '{}': {}", city.getSlug(), e.getMessage());
                }
            }
        } finally {
            warmerRunning.set(false);
        }
    }

    private List<StationTelemetryDTO> refreshCityCacheSync(String citySlug, boolean forceRefresh) {
        ReentrantLock cityLock = cityFetchLocks.computeIfAbsent(citySlug, k -> new ReentrantLock());
        cityLock.lock();
        try {
            Optional<CachedCityTelemetry> existing = cacheManager.getCityTelemetryEntry(citySlug);
            if (!forceRefresh && existing.isPresent() && !existing.get().isExpired()) {
                return existing.get().data();
            }

            List<MonitoringStation> candidates = resolveCandidateStationsForCity(citySlug);
            LocalDate today = LocalDate.now();
            List<StationTelemetryDTO> activeData = new ArrayList<>();
            int activeFluviometric = 0;
            int activePluviometric = 0;

            for (MonitoringStation station : candidates) {
                if (anaClientService.isCircuitOpen()) break;

                boolean isFluviometric = "Fluviometrica".equalsIgnoreCase(station.getStationType());
                if (isFluviometric && activeFluviometric >= MAX_ACTIVE_FLUVIOMETRIC_PER_CITY) continue;
                if (!isFluviometric && activePluviometric >= MAX_ACTIVE_PLUVIOMETRIC_PER_CITY) continue;
                if (cacheManager.isStationKnownEmpty(station.getStationCode())) continue;

                StationTelemetryDTO dto = fetchOrReuseStationTelemetry(station, today);
                if (dto != null) {
                    activeData.add(dto);
                    if (isFluviometric) activeFluviometric++;
                    else activePluviometric++;
                }

                if (activeFluviometric >= MAX_ACTIVE_FLUVIOMETRIC_PER_CITY
                        && activePluviometric >= MAX_ACTIVE_PLUVIOMETRIC_PER_CITY) {
                    break;
                }
            }

            if (!activeData.isEmpty()) {
                cacheManager.putCityTelemetry(citySlug, activeData);
                return activeData;
            }

            return existing.map(CachedCityTelemetry::data).orElse(Collections.emptyList());
        } finally {
            cityLock.unlock();
        }
    }

    private List<MonitoringStation> resolveCandidateStationsForCity(String citySlug) {
        Optional<List<MonitoringStation>> cached = cacheManager.getFreshCandidateStations(citySlug);
        if (cached.isPresent()) {
            return cached.get();
        }

        Map<String, MonitoringStation> mergedByCode = new LinkedHashMap<>();

        cityRepository.findBySlug(citySlug).ifPresent(city -> {
            if (city.getStations() != null) {
                for (MonitoringStation s : city.getStations()) {
                    mergedByCode.put(s.getStationCode().trim(), s);
                }
            }
            for (MonitoringStation d : discoveryService.discoverStationsForCity(city)) {
                mergedByCode.putIfAbsent(d.getStationCode().trim(), d);
            }
        });

        List<MonitoringStation> result = new ArrayList<>(mergedByCode.values());
        if (!result.isEmpty() && discoveryService.isStateInventoryLoaded("SC")) {
            cacheManager.putCandidateStations(citySlug, result);
        }
        return result;
    }

    private StationTelemetryDTO fetchOrReuseStationTelemetry(MonitoringStation station, LocalDate searchDate) {
        String code = station.getStationCode().trim();

        Optional<StationTelemetryDTO> shared = cacheManager.getFreshSharedStationReading(code);
        if (shared.isPresent()) {
            return telemetryMapper.adaptSharedTelemetryToCityStation(station, shared.get());
        }

        List<AnaTelemetryItemDTO> rawItems = Collections.emptyList();
        boolean networkError = false;

        if (anaClientService.isCircuitOpen()) {
            networkError = true;
        } else {
            try {
                AnaTelemetryResponseDTO response = anaClientService.getStationTelemetry(code, searchDate, "HORA_24");
                if (response != null && response.items() != null) {
                    rawItems = response.items();
                }
            } catch (Exception e) {
                networkError = true;
                log.warn("Falha de rede na estação {}: {}", code, e.getMessage());
            }
        }

        List<TelemetryReadingDTO> history = telemetryMapper.mapRawItemsToHistory(rawItems);

        if (!telemetryMapper.hasValidTelemetryForStationType(station, history)) {
            if (!networkError) {
                cacheManager.markStationEmpty(code);
            }
            return cacheManager.getLastValidStationReading(code).orElse(null);
        }

        StationTelemetryDTO dto = telemetryMapper.buildStationTelemetryDTO(station, history);
        cacheManager.putStationReading(code, dto);
        return dto;
    }

    private List<StationTelemetryDTO> assembleAvailableSnapshotFromMemory(String citySlug) {
        List<MonitoringStation> candidates = resolveCandidateStationsForCity(citySlug);
        List<StationTelemetryDTO> snapshot = new ArrayList<>();
        int fluv = 0;
        int pluv = 0;

        for (MonitoringStation station : candidates) {
            boolean isFluviometric = "Fluviometrica".equalsIgnoreCase(station.getStationType());
            if (isFluviometric && fluv >= MAX_ACTIVE_FLUVIOMETRIC_PER_CITY) continue;
            if (!isFluviometric && pluv >= MAX_ACTIVE_PLUVIOMETRIC_PER_CITY) continue;

            Optional<StationTelemetryDTO> base = cacheManager.getAnyAvailableStationReading(station.getStationCode());
            if (base.isPresent()) {
                snapshot.add(telemetryMapper.adaptSharedTelemetryToCityStation(station, base.get()));
                if (isFluviometric) fluv++;
                else pluv++;
            }
        }
        return snapshot;
    }
}