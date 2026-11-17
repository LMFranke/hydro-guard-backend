package com.hydroguard.backend.services;

import com.hydroguard.backend.dtos.StationTelemetryDTO;
import com.hydroguard.backend.models.MonitoringStation;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class TelemetryCacheManager {

    private static final long CITY_CACHE_TTL_MINUTES = 12;
    private static final long CANDIDATES_CACHE_TTL_HOURS = 6;
    private static final long EMPTY_STATION_TTL_MINUTES = 180;

    private final Map<String, CachedCityTelemetry> cityTelemetryCache = new ConcurrentHashMap<>();
    private final Map<String, CachedCandidateList> discoveredStationsCache = new ConcurrentHashMap<>();
    private final Map<String, CachedStationReading> singleStationCache = new ConcurrentHashMap<>();
    private final Map<String, Instant> emptyStationCache = new ConcurrentHashMap<>();
    private final Map<String, StationTelemetryDTO> lastValidStationCache = new ConcurrentHashMap<>();

    public record CachedCityTelemetry(List<StationTelemetryDTO> data, Instant expiresAt) {
        public boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    private record CachedCandidateList(List<MonitoringStation> stations, Instant expiresAt) {}
    private record CachedStationReading(StationTelemetryDTO dto, Instant expiresAt) {}

    public Optional<CachedCityTelemetry> getCityTelemetryEntry(String citySlug) {
        CachedCityTelemetry entry = cityTelemetryCache.get(citySlug);
        return (entry != null && !entry.data().isEmpty()) ? Optional.of(entry) : Optional.empty();
    }

    public void putCityTelemetry(String citySlug, List<StationTelemetryDTO> data) {
        cityTelemetryCache.put(
                citySlug,
                new CachedCityTelemetry(data, Instant.now().plus(CITY_CACHE_TTL_MINUTES, ChronoUnit.MINUTES))
        );
    }

    public Optional<List<MonitoringStation>> getFreshCandidateStations(String citySlug) {
        CachedCandidateList cached = discoveredStationsCache.get(citySlug);
        if (cached != null && Instant.now().isBefore(cached.expiresAt()) && !cached.stations().isEmpty()) {
            return Optional.of(cached.stations());
        }
        return Optional.empty();
    }

    public void putCandidateStations(String citySlug, List<MonitoringStation> stations) {
        discoveredStationsCache.put(
                citySlug,
                new CachedCandidateList(stations, Instant.now().plus(CANDIDATES_CACHE_TTL_HOURS, ChronoUnit.HOURS))
        );
    }

    public Optional<StationTelemetryDTO> getFreshSharedStationReading(String stationCode) {
        CachedStationReading shared = singleStationCache.get(stationCode.trim());
        if (shared != null && Instant.now().isBefore(shared.expiresAt())) {
            return Optional.of(shared.dto());
        }
        return Optional.empty();
    }

    public Optional<StationTelemetryDTO> getAnyAvailableStationReading(String stationCode) {
        String code = stationCode.trim();
        CachedStationReading shared = singleStationCache.get(code);
        if (shared != null) {
            return Optional.of(shared.dto());
        }
        return Optional.ofNullable(lastValidStationCache.get(code));
    }

    public void putStationReading(String stationCode, StationTelemetryDTO dto) {
        String code = stationCode.trim();
        lastValidStationCache.put(code, dto);
        singleStationCache.put(
                code,
                new CachedStationReading(dto, Instant.now().plus(CITY_CACHE_TTL_MINUTES, ChronoUnit.MINUTES))
        );
    }

    public Optional<StationTelemetryDTO> getLastValidStationReading(String stationCode) {
        return Optional.ofNullable(lastValidStationCache.get(stationCode.trim()));
    }

    public boolean isStationKnownEmpty(String stationCode) {
        Instant expiresAt = emptyStationCache.get(stationCode.trim());
        return expiresAt != null && Instant.now().isBefore(expiresAt);
    }

    public void markStationEmpty(String stationCode) {
        emptyStationCache.put(
                stationCode.trim(),
                Instant.now().plus(EMPTY_STATION_TTL_MINUTES, ChronoUnit.MINUTES)
        );
    }
}