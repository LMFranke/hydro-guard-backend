package com.hydroguard.backend.services;

import com.hydroguard.backend.dtos.SimulationGeometryDTO;
import com.hydroguard.backend.dtos.SimulationGeometryDTO.StreetWayDTO;
import com.hydroguard.backend.dtos.SimulationGeometryDTO.WaterwayDTO;
import com.hydroguard.backend.models.City;
import com.hydroguard.backend.models.CitySimulationGeometry;
import com.hydroguard.backend.repositories.CityRepository;
import com.hydroguard.backend.repositories.CitySimulationGeometryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Service
public class SimulationGeometryService {

    private static final List<String> OVERPASS_ENDPOINTS = List.of(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.private.coffee/api/interpreter",
            "https://maps.mail.ru/osm/tools/overpass/api/interpreter"
    );

    private static final int CACHE_TTL_DAYS = 30;
    private static final double WATERWAY_HALF_SPAN_KM = 8.5;
    private static final double STREET_HALF_SPAN_KM = 6.5;
    private static final double MIN_WATERWAY_STEP_DEG = 0.00018;
    private static final double MIN_STREET_STEP_DEG = 0.00014;

    private final CityRepository cityRepository;
    private final CitySimulationGeometryRepository geometryRepository;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final Map<String, ReentrantLock> cityLocks = new ConcurrentHashMap<>();

    public SimulationGeometryService(
            CityRepository cityRepository,
            CitySimulationGeometryRepository geometryRepository,
            ObjectMapper objectMapper
    ) {
        this.cityRepository = cityRepository;
        this.geometryRepository = geometryRepository;
        this.objectMapper = objectMapper;

        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(8))
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(30));

        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.USER_AGENT, "HydroGuard/1.0 (Academic Flood Monitor)")
                .build();
    }

    public String getCompactGeometryJson(String citySlug) {
        String normalizedSlug = citySlug.trim().toLowerCase(Locale.ROOT);
        LocalDateTime now = LocalDateTime.now();

        Optional<CitySimulationGeometry> existing = geometryRepository.findById(normalizedSlug);
        if (existing.isPresent() && now.isBefore(existing.get().getExpiresAt())) {
            return existing.get().getPayloadJson();
        }

        ReentrantLock lock = cityLocks.computeIfAbsent(normalizedSlug, k -> new ReentrantLock());
        lock.lock();
        try {
            existing = geometryRepository.findById(normalizedSlug);
            if (existing.isPresent() && LocalDateTime.now().isBefore(existing.get().getExpiresAt())) {
                return existing.get().getPayloadJson();
            }

            City city = cityRepository.findBySlug(normalizedSlug)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "City not found"));

            try {
                SimulationGeometryDTO dto = fetchAndCompactFromOverpass(city);
                String json = objectMapper.writeValueAsString(dto);

                if (!dto.waterways().isEmpty() || !dto.streets().isEmpty()) {
                    LocalDateTime updatedAt = LocalDateTime.now();
                    geometryRepository.save(CitySimulationGeometry.builder()
                            .citySlug(normalizedSlug)
                            .payloadJson(json)
                            .updatedAt(updatedAt)
                            .expiresAt(updatedAt.plusDays(CACHE_TTL_DAYS))
                            .build());
                }

                return json;
            } catch (Exception e) {
                log.warn("[Simulation Geometry] Falha ao atualizar geometria de '{}': {}", normalizedSlug, e.getMessage());
                if (existing.isPresent()) {
                    return existing.get().getPayloadJson();
                }
                throw new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Não foi possível obter a malha viária e hidrográfica no momento."
                );
            }
        } finally {
            lock.unlock();
        }
    }

    public void evictCityCache(String citySlug) {
        geometryRepository.deleteById(citySlug.trim().toLowerCase(Locale.ROOT));
    }

    private SimulationGeometryDTO fetchAndCompactFromOverpass(City city) throws Exception {
        double lat = city.getMapLat();
        double lng = city.getMapLng();
        double cosLat = Math.cos(Math.toRadians(lat));

        double wLatDelta = WATERWAY_HALF_SPAN_KM / 111.32;
        double wLngDelta = WATERWAY_HALF_SPAN_KM / (111.32 * cosLat);

        double sLatDelta = STREET_HALF_SPAN_KM / 111.32;
        double sLngDelta = STREET_HALF_SPAN_KM / (111.32 * cosLat);

        String query = String.format(
                Locale.US,
                "[out:json][timeout:25];(" +
                        "way[\"waterway\"~\"^(river|stream|canal)$\"](%.5f,%.5f,%.5f,%.5f);" +
                        "way[\"highway\"~\"^(trunk|primary|secondary|tertiary|residential)$\"][\"name\"](%.5f,%.5f,%.5f,%.5f);" +
                        ");out tags geom;",
                lat - wLatDelta, lng - wLngDelta, lat + wLatDelta, lng + wLngDelta,
                lat - sLatDelta, lng - sLngDelta, lat + sLatDelta, lng + sLngDelta
        );

        String formBody = "data=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
        String rawResponse = null;

        for (String endpoint : OVERPASS_ENDPOINTS) {
            try {
                rawResponse = restClient.post()
                        .uri(endpoint)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body(formBody)
                        .retrieve()
                        .body(String.class);

                if (rawResponse != null && !rawResponse.isBlank()) {
                    break;
                }
            } catch (Exception ex) {
                log.warn("[Simulation Geometry] Mirror {} falhou para '{}': {}", endpoint, city.getSlug(), ex.getMessage());
            }
        }

        if (rawResponse == null || rawResponse.isBlank()) {
            throw new IllegalStateException("Todos os mirrors da Overpass falharam.");
        }

        JsonNode root = objectMapper.readTree(rawResponse);
        JsonNode elements = root.path("elements");

        List<WaterwayDTO> waterways = new ArrayList<>();
        List<StreetWayDTO> streets = new ArrayList<>();

        if (elements.isArray()) {
            for (JsonNode el : elements) {
                JsonNode geom = el.path("geometry");
                if (!geom.isArray() || geom.size() < 2) continue;

                JsonNode tags = el.path("tags");
                String id = el.path("id").asText();

                if (tags.hasNonNull("waterway")) {
                    String rawKind = tags.path("waterway").asText("stream");
                    String kind = "river".equals(rawKind) ? "river" : ("canal".equals(rawKind) ? "canal" : "stream");
                    String name = tags.hasNonNull("name")
                            ? tags.path("name").asText().trim()
                            : ("river".equals(kind) ? "Rio Principal" : "Ribeirão / Afluente");
                    int channelWidth = "river".equals(kind) ? 52 : 14;

                    List<double[]> points = simplifyAndRoundGeometry(geom, MIN_WATERWAY_STEP_DEG);
                    if (points.size() >= 2) {
                        waterways.add(new WaterwayDTO(id, name, kind, channelWidth, points));
                    }
                } else if (tags.hasNonNull("highway") && tags.hasNonNull("name")) {
                    String name = tags.path("name").asText().trim();
                    if (name.isEmpty()) continue;

                    String suburb = tags.hasNonNull("addr:suburb")
                            ? tags.path("addr:suburb").asText().trim()
                            : (tags.hasNonNull("suburb") ? tags.path("suburb").asText().trim() : null);
                    String highwayType = tags.path("highway").asText("residential");

                    List<double[]> points = simplifyAndRoundGeometry(geom, MIN_STREET_STEP_DEG);
                    if (points.size() >= 2) {
                        streets.add(new StreetWayDTO(id, name, suburb, highwayType, points));
                    }
                }
            }
        }

        log.info("[Simulation Geometry] Compactado '{}': {} trechos hídricos e {} trechos de vias.",
                city.getSlug(), waterways.size(), streets.size());

        return new SimulationGeometryDTO(
                city.getSlug(),
                new double[]{roundCoord(lat), roundCoord(lng)},
                waterways,
                streets
        );
    }

    private List<double[]> simplifyAndRoundGeometry(JsonNode geom, double minStepDeg) {
        List<double[]> result = new ArrayList<>();
        double lastLat = Double.NaN;
        double lastLng = Double.NaN;
        int total = geom.size();

        for (int i = 0; i < total; i++) {
            JsonNode pt = geom.get(i);
            double lat = roundCoord(pt.path("lat").asDouble());
            double lng = roundCoord(pt.path("lon").asDouble());

            boolean isEdgePoint = (i == 0 || i == total - 1);
            if (isEdgePoint || Double.isNaN(lastLat)
                    || Math.abs(lat - lastLat) >= minStepDeg
                    || Math.abs(lng - lastLng) >= minStepDeg) {
                result.add(new double[]{lat, lng});
                lastLat = lat;
                lastLng = lng;
            }
        }

        return result;
    }

    private double roundCoord(double value) {
        return Math.round(value * 100000.0) / 100000.0;
    }
}