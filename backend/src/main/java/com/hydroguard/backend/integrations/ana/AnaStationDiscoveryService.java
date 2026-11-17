package com.hydroguard.backend.integrations.ana;

import com.hydroguard.backend.integrations.ana.dto.AnaInventoryResponseDTO.AnaStationInventoryItemDTO;
import com.hydroguard.backend.models.City;
import com.hydroguard.backend.models.MonitoringStation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnaStationDiscoveryService {

    private static final int CANDIDATE_FLUVIOMETRIC_LIMIT = 18;
    private static final int CANDIDATE_PLUVIOMETRIC_LIMIT = 6;
    private static final double PRIMARY_SEARCH_RADIUS_KM = 30.0;
    private static final double EXPANDED_SEARCH_RADIUS_KM = 55.0;
    private static final int MIN_FLUVIOMETRIC_BEFORE_EXPANDING = 3;
    private static final List<String> SC_HYDROGRAPHIC_BASINS = List.of("8", "7");

    private final AnaClientService anaClientService;

    private final Map<String, CachedStateInventory> stateInventoryCache = new ConcurrentHashMap<>();
    private final ReentrantLock inventoryLock = new ReentrantLock();

    private record CachedStateInventory(List<AnaStationInventoryItemDTO> items, Instant expiresAt) {}

    public boolean isStateInventoryLoaded(String stateUf) {
        CachedStateInventory cached = stateInventoryCache.get(stateUf.toUpperCase());
        return cached != null && !cached.items().isEmpty();
    }

    public void preloadStateInventory(String stateUf) {
        getOperatingTelemetricStationsByState(stateUf);
    }

    public List<AnaStationInventoryItemDTO> getActiveStateStations(String stateUf) {
        String uf = (stateUf != null && !stateUf.isBlank()) ? stateUf.trim().toUpperCase() : "SC";
        return getOperatingTelemetricStationsByState(uf);
    }

    public List<MonitoringStation> discoverStationsForCity(City city) {
        String stateUf = (city.getState() != null && !city.getState().isBlank())
                ? city.getState().trim().toUpperCase()
                : "SC";

        List<AnaStationInventoryItemDTO> stateStations = getOperatingTelemetricStationsByState(stateUf);
        if (stateStations.isEmpty()) {
            return Collections.emptyList();
        }

        String normalizedCityName = normalizeText(city.getName());

        List<AnaStationInventoryItemDTO> exactCityMatches = stateStations.stream()
                .filter(item -> normalizeText(item.municipalityName()).equals(normalizedCityName))
                .sorted(Comparator.comparing(
                        (AnaStationInventoryItemDTO item) -> item.lastUpdate() != null ? item.lastUpdate() : "",
                        Comparator.reverseOrder()
                ))
                .toList();

        List<AnaStationInventoryItemDTO> primaryRadiusMatches = filterStationsByRadius(
                stateStations, normalizedCityName, city.getMapLat(), city.getMapLng(), PRIMARY_SEARCH_RADIUS_KM
        );

        long initialFluviometricCount = exactCityMatches.stream().filter(this::isFluviometricItem).count()
                + primaryRadiusMatches.stream().filter(this::isFluviometricItem).count();

        double effectiveRadiusKm = PRIMARY_SEARCH_RADIUS_KM;
        List<AnaStationInventoryItemDTO> radiusMatches = primaryRadiusMatches;

        if (initialFluviometricCount < MIN_FLUVIOMETRIC_BEFORE_EXPANDING) {
            effectiveRadiusKm = EXPANDED_SEARCH_RADIUS_KM;
            radiusMatches = filterStationsByRadius(
                    stateStations, normalizedCityName, city.getMapLat(), city.getMapLng(), EXPANDED_SEARCH_RADIUS_KM
            );
        }

        List<AnaStationInventoryItemDTO> combinedCandidates = new ArrayList<>(exactCityMatches.size() + radiusMatches.size());
        combinedCandidates.addAll(exactCityMatches);
        combinedCandidates.addAll(radiusMatches);

        Map<String, MonitoringStation> selectedStations = new LinkedHashMap<>();
        Set<String> seenNamesAndType = new HashSet<>();
        int fluviometricCount = 0;
        int pluviometricCount = 0;

        for (AnaStationInventoryItemDTO item : combinedCandidates) {
            String code = item.stationCode().trim();
            if (selectedStations.containsKey(code)) continue;

            boolean isFluviometric = isFluviometricItem(item);
            String typeKey = isFluviometric ? "FLUV" : "PLUV";
            String dedupNameKey = typeKey + ":" + normalizeText(item.stationName());

            if (seenNamesAndType.contains(dedupNameKey)) continue;

            if (isFluviometric && fluviometricCount < CANDIDATE_FLUVIOMETRIC_LIMIT) {
                selectedStations.put(code, mapInventoryItemToStation(item, city, normalizedCityName));
                seenNamesAndType.add(dedupNameKey);
                fluviometricCount++;
            } else if (!isFluviometric && pluviometricCount < CANDIDATE_PLUVIOMETRIC_LIMIT) {
                selectedStations.put(code, mapInventoryItemToStation(item, city, normalizedCityName));
                seenNamesAndType.add(dedupNameKey);
                pluviometricCount++;
            }

            if (fluviometricCount >= CANDIDATE_FLUVIOMETRIC_LIMIT && pluviometricCount >= CANDIDATE_PLUVIOMETRIC_LIMIT) {
                break;
            }
        }

        List<MonitoringStation> finalCandidates = applyCoordinateDeoverlapping(new ArrayList<>(selectedStations.values()));

        log.info("[ANA Discovery] Cidade '{}': {} candidatas encontradas (raio: {}km)",
                city.getName(), finalCandidates.size(), effectiveRadiusKm);

        return finalCandidates;
    }

    private List<AnaStationInventoryItemDTO> getOperatingTelemetricStationsByState(String stateUf) {
        CachedStateInventory cached = stateInventoryCache.get(stateUf);
        if (cached != null && Instant.now().isBefore(cached.expiresAt())) {
            return cached.items();
        }
        if (anaClientService.isCircuitOpen()) {
            return cached != null ? cached.items() : Collections.emptyList();
        }

        inventoryLock.lock();
        try {
            cached = stateInventoryCache.get(stateUf);
            if (cached != null && Instant.now().isBefore(cached.expiresAt())) {
                return cached.items();
            }

            Map<String, AnaStationInventoryItemDTO> consolidatedByCode = new LinkedHashMap<>();

            for (String basinCode : SC_HYDROGRAPHIC_BASINS) {
                for (int attempt = 1; attempt <= 2; attempt++) {
                    try {
                        List<AnaStationInventoryItemDTO> rawItems =
                                anaClientService.fetchInventoryByStateAndBasin(stateUf, basinCode);

                        rawItems.stream()
                                .filter(item -> item.stationCode() != null && !item.stationCode().isBlank())
                                .filter(item -> "1".equals(item.operating()))
                                .filter(item -> "1".equals(item.isTelemetric()))
                                .filter(item -> item.telemetricEndDate() == null || item.telemetricEndDate().isBlank())
                                .filter(item -> parseCoord(item.latitude()) != null && parseCoord(item.longitude()) != null)
                                .forEach(item -> consolidatedByCode.put(item.stationCode().trim(), item));

                        break;
                    } catch (Exception e) {
                        log.warn("[ANA Inventory] Bacia {} tentativa {}/2 falhou em {}: {}",
                                basinCode, attempt, stateUf, e.getMessage());
                    }
                }
            }

            if (!consolidatedByCode.isEmpty()) {
                List<AnaStationInventoryItemDTO> allActive = new ArrayList<>(consolidatedByCode.values());
                log.info("[ANA Inventory] Consolidado para {}: {} estações telemétricas ativas.", stateUf, allActive.size());
                stateInventoryCache.put(
                        stateUf,
                        new CachedStateInventory(allActive, Instant.now().plus(6, ChronoUnit.HOURS))
                );
                return allActive;
            }

            return cached != null ? cached.items() : Collections.emptyList();
        } finally {
            inventoryLock.unlock();
        }
    }

    private List<AnaStationInventoryItemDTO> filterStationsByRadius(
            List<AnaStationInventoryItemDTO> stateStations,
            String normalizedCityName,
            Double cityLat,
            Double cityLng,
            double maxRadiusKm
    ) {
        if (cityLat == null || cityLng == null) return Collections.emptyList();

        return stateStations.stream()
                .filter(item -> !normalizeText(item.municipalityName()).equals(normalizedCityName))
                .filter(item -> {
                    Double lat = parseCoord(item.latitude());
                    Double lng = parseCoord(item.longitude());
                    return lat != null && lng != null
                            && calculateDistanceKm(cityLat, cityLng, lat, lng) <= maxRadiusKm;
                })
                .sorted(Comparator.comparingDouble(item ->
                        calculateDistanceKm(cityLat, cityLng, parseCoord(item.latitude()), parseCoord(item.longitude()))
                ))
                .toList();
    }

    private MonitoringStation mapInventoryItemToStation(
            AnaStationInventoryItemDTO item,
            City city,
            String normalizedTargetCityName
    ) {
        boolean isFluviometric = isFluviometricItem(item);
        String rawStationName = item.stationName() != null ? item.stationName().trim() : "Estação " + item.stationCode();
        String stationMunicipality = item.municipalityName() != null ? item.municipalityName().trim() : "";

        boolean isFromNeighborCity = !stationMunicipality.isEmpty()
                && !normalizeText(stationMunicipality).equals(normalizedTargetCityName);

        String displayName = (isFromNeighborCity && !normalizeText(rawStationName).contains(normalizeText(stationMunicipality)))
                ? rawStationName + " (" + stationMunicipality + ")"
                : rawStationName;

        String riverName = (item.riverName() != null && !item.riverName().isBlank()) ? item.riverName().trim() : "N/A";

        return MonitoringStation.builder()
                .stationCode(item.stationCode().trim())
                .name(displayName)
                .stationType(isFluviometric ? "Fluviometrica" : "Pluviometrica")
                .riverName(riverName)
                .riverCode(item.riverCode())
                .operatorAcronym(item.operatorAcronym())
                .latitude(parseCoord(item.latitude()))
                .longitude(parseCoord(item.longitude()))
                .altitude(parseCoord(item.altitude()))
                .thresholdWarning(isFluviometric ? 450.0 : 40.0)
                .thresholdAlert(isFluviometric ? 650.0 : 70.0)
                .thresholdCritical(isFluviometric ? 850.0 : 100.0)
                .city(city)
                .build();
    }

    private List<MonitoringStation> applyCoordinateDeoverlapping(List<MonitoringStation> stations) {
        for (int i = 0; i < stations.size(); i++) {
            MonitoringStation current = stations.get(i);
            if (current.getLatitude() == null || current.getLongitude() == null) continue;

            int overlapCount = 0;
            for (int j = 0; j < i; j++) {
                MonitoringStation prev = stations.get(j);
                if (prev.getLatitude() == null || prev.getLongitude() == null) continue;

                if (Math.abs(current.getLatitude() - prev.getLatitude()) < 0.0010
                        && Math.abs(current.getLongitude() - prev.getLongitude()) < 0.0010) {
                    overlapCount++;
                }
            }

            if (overlapCount > 0) {
                double angle = overlapCount * (Math.PI / 3.0);
                double radiusOffset = 0.0022 * ((overlapCount + 1) / 2);
                current.setLatitude(Math.round((current.getLatitude() + radiusOffset * Math.cos(angle)) * 1000000.0) / 1000000.0);
                current.setLongitude(Math.round((current.getLongitude() + radiusOffset * Math.sin(angle)) * 1000000.0) / 1000000.0);
            }
        }
        return stations;
    }

    private boolean isFluviometricItem(AnaStationInventoryItemDTO item) {
        return item.stationType() != null && normalizeText(item.stationType()).startsWith("FLUVIO");
    }

    private static String normalizeText(String input) {
        if (input == null) return "";
        return Normalizer.normalize(input, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .trim()
                .toUpperCase();
    }

    private static Double parseCoord(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Double.parseDouble(raw.trim().replace(",", "."));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static double calculateDistanceKm(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * (2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a)));
    }
}