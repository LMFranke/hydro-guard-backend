package com.hydroguard.backend.controllers;

import com.hydroguard.backend.services.SimulationGeometryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/v1/simulation")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class SimulationController {

    private final SimulationGeometryService simulationGeometryService;

    @GetMapping(value = "/{citySlug}/geometry", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getCitySimulationGeometry(@PathVariable String citySlug) {
        String json = simulationGeometryService.getCompactGeometryJson(citySlug);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(24, TimeUnit.HOURS).cachePublic())
                .contentType(MediaType.APPLICATION_JSON)
                .body(json);
    }

    @DeleteMapping("/{citySlug}/geometry/cache")
    public ResponseEntity<Void> evictCityGeometryCache(@PathVariable String citySlug) {
        simulationGeometryService.evictCityCache(citySlug);
        return ResponseEntity.noContent().build();
    }
}