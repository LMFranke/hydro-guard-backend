package com.hydroguard.backend.controllers;

import com.hydroguard.backend.dtos.RiskZoneRequestDTO;
import com.hydroguard.backend.models.Coordinator;
import com.hydroguard.backend.models.RiskZone;
import com.hydroguard.backend.repositories.RiskZoneRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/risk-zones")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class RiskZoneController {

    private final RiskZoneRepository riskZoneRepository;

    @GetMapping("/{citySlug}")
    public ResponseEntity<List<RiskZone>> getCityRiskZones(@PathVariable String citySlug) {
        return ResponseEntity.ok(riskZoneRepository.findByCitySlugOrderByNameAsc(citySlug));
    }

    @PostMapping
    public ResponseEntity<RiskZone> createRiskZone(
            @RequestBody @Valid RiskZoneRequestDTO data,
            @AuthenticationPrincipal Coordinator coordinator
    ) {
        RiskZone zone = RiskZone.builder()
                .name(data.name())
                .zoneType(data.zoneType())
                .riskLevel(data.riskLevel())
                .latitude(data.latitude())
                .longitude(data.longitude())
                .radius(data.radius())
                .description(data.description())
                .city(coordinator.getCity())
                .build();

        return ResponseEntity.status(HttpStatus.CREATED).body(riskZoneRepository.save(zone));
    }

    @PutMapping("/{id}")
    public ResponseEntity<RiskZone> updateRiskZone(
            @PathVariable UUID id,
            @RequestBody @Valid RiskZoneRequestDTO data,
            @AuthenticationPrincipal Coordinator coordinator
    ) {
        RiskZone zone = riskZoneRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Risk zone not found"));

        if (!zone.getCity().getId().equals(coordinator.getCity().getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized to edit risk zones for this city");
        }

        zone.setName(data.name());
        zone.setZoneType(data.zoneType());
        zone.setRiskLevel(data.riskLevel());
        zone.setLatitude(data.latitude());
        zone.setLongitude(data.longitude());
        zone.setRadius(data.radius());
        zone.setDescription(data.description());

        return ResponseEntity.ok(riskZoneRepository.save(zone));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteRiskZone(
            @PathVariable UUID id,
            @AuthenticationPrincipal Coordinator coordinator
    ) {
        RiskZone zone = riskZoneRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Risk zone not found"));

        if (!zone.getCity().getId().equals(coordinator.getCity().getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized to delete risk zones for this city");
        }

        riskZoneRepository.delete(zone);
        return ResponseEntity.noContent().build();
    }
}