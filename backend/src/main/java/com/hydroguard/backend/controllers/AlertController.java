package com.hydroguard.backend.controllers;

import com.hydroguard.backend.dtos.AlertRequestDTO;
import com.hydroguard.backend.models.Alert;
import com.hydroguard.backend.models.City;
import com.hydroguard.backend.models.Coordinator;
import com.hydroguard.backend.repositories.AlertRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/alerts")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AlertController {

    private static final int DEFAULT_VALIDITY_HOURS = 12;

    private final AlertRepository alertRepository;

    @GetMapping("/{citySlug}")
    public ResponseEntity<List<Alert>> getActiveAlerts(@PathVariable String citySlug) {
        List<Alert> activeAlerts = alertRepository.findByCitySlugOrderByCreatedAtDesc(citySlug.trim().toLowerCase())
                .stream()
                .filter(Alert::isCurrentlyActive)
                .toList();
        return ResponseEntity.ok(activeAlerts);
    }

    @GetMapping("/{citySlug}/history")
    public ResponseEntity<List<Alert>> getAlertHistory(@PathVariable String citySlug) {
        List<Alert> history = alertRepository.findByCitySlugOrderByCreatedAtDesc(citySlug.trim().toLowerCase())
                .stream()
                .filter(alert -> !alert.isCurrentlyActive())
                .toList();
        return ResponseEntity.ok(history);
    }

    @PostMapping
    public ResponseEntity<Alert> createAlert(
            @RequestBody @Valid AlertRequestDTO data,
            @AuthenticationPrincipal Coordinator coordinator
    ) {
        if (coordinator == null || coordinator.getCity() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Operador não autenticado.");
        }

        City city = coordinator.getCity();
        LocalDateTime now = LocalDateTime.now();

        LocalDateTime resolvedExpiresAt;
        if (data.expiresAt() != null) {
            resolvedExpiresAt = data.expiresAt();
        } else {
            int hours = (data.validityHours() != null && data.validityHours() > 0)
                    ? data.validityHours()
                    : DEFAULT_VALIDITY_HOURS;
            resolvedExpiresAt = now.plusHours(hours);
        }

        String resolvedSource = (data.source() != null && !data.source().isBlank())
                ? data.source().trim()
                : "Defesa Civil Municipal de " + city.getName();

        Double resolvedLat = data.latitude() != null ? data.latitude() : city.getMapLat();
        Double resolvedLng = data.longitude() != null ? data.longitude() : city.getMapLng();

        Alert alert = Alert.builder()
                .title(data.title().trim())
                .severity(data.severity().trim().toUpperCase())
                .region(data.region().trim())
                .description(data.description().trim())
                .safetyGuidelines(data.safetyGuidelines().trim())
                .source(resolvedSource)
                .active(true)
                .createdAt(now)
                .updatedAt(now)
                .expiresAt(resolvedExpiresAt)
                .latitude(resolvedLat)
                .longitude(resolvedLng)
                .city(city)
                .build();

        return ResponseEntity.status(HttpStatus.CREATED).body(alertRepository.save(alert));
    }

    @PatchMapping("/{id}/deactivate")
    public ResponseEntity<Alert> deactivateAlert(
            @PathVariable UUID id,
            @AuthenticationPrincipal Coordinator coordinator
    ) {
        if (coordinator == null || coordinator.getCity() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Operador não autenticado.");
        }

        Alert alert = alertRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Alert not found"));

        if (!alert.getCity().getId().equals(coordinator.getCity().getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized for this city");
        }

        LocalDateTime now = LocalDateTime.now();
        alert.setActive(false);
        alert.setClosedAt(now);
        alert.setUpdatedAt(now);

        return ResponseEntity.ok(alertRepository.save(alert));
    }
}