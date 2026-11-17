package com.hydroguard.backend.controllers;

import com.hydroguard.backend.dtos.StationTelemetryDTO;
import com.hydroguard.backend.integrations.ana.AnaStationDiscoveryService;
import com.hydroguard.backend.services.TelemetryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/telemetry")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class TelemetryController {

    private final TelemetryService telemetryService;
    private final AnaStationDiscoveryService discoveryService;

    @GetMapping("/{citySlug}")
    public ResponseEntity<List<StationTelemetryDTO>> getCityTelemetry(@PathVariable String citySlug) {
        return ResponseEntity.ok(telemetryService.getCityTelemetry(citySlug));
    }

    @GetMapping("/discover/state")
    public ResponseEntity<?> discoverStateStations(@RequestParam(defaultValue = "SC") String uf) {
        return ResponseEntity.ok(discoveryService.getActiveStateStations(uf));
    }
}