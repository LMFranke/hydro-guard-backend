package com.hydroguard.backend.controllers;

import com.hydroguard.backend.dtos.SystemHealthDTO;
import com.hydroguard.backend.integrations.ana.AnaClientService;
import com.hydroguard.backend.integrations.ana.AnaResilienceManager;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/system")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class SystemHealthController {

    private final AnaClientService anaClientService;
    private final AnaResilienceManager resilienceManager;

    @GetMapping("/health")
    public ResponseEntity<SystemHealthDTO> getSystemHealth() {
        return ResponseEntity.ok(anaClientService.checkAnaHealth());
    }

    @PostMapping("/ana-outage")
    public ResponseEntity<SystemHealthDTO> toggleSimulatedAnaOutage(
            @RequestParam boolean active
    ) {
        resilienceManager.setSimulatedOutage(active);
        return ResponseEntity.ok(anaClientService.checkAnaHealth());
    }
}