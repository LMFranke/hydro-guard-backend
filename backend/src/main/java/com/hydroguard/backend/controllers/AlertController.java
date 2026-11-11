package com.hydroguard.backend.controllers;

import com.hydroguard.backend.dtos.AlertRequestDTO;
import com.hydroguard.backend.models.Alert;
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

    private final AlertRepository alertRepository;

    @GetMapping("/{citySlug}")
    public ResponseEntity<List<Alert>> getActiveAlerts(@PathVariable String citySlug) {
        return ResponseEntity.ok(alertRepository.findByCitySlugAndActiveTrueOrderByCreatedAtDesc(citySlug));
    }

    @PostMapping
    public ResponseEntity<Alert> createAlert(
            @RequestBody @Valid AlertRequestDTO data,
            @AuthenticationPrincipal Coordinator coordinator
    ) {
        Alert alert = Alert.builder()
                .title(data.title())
                .severity(data.severity())
                .region(data.region())
                .description(data.description())
                .safetyGuidelines(data.safetyGuidelines())
                .active(true)
                .createdAt(LocalDateTime.now())
                .city(coordinator.getCity())
                .build();

        return ResponseEntity.status(HttpStatus.CREATED).body(alertRepository.save(alert));
    }

    @PatchMapping("/{id}/deactivate")
    public ResponseEntity<Void> deactivateAlert(
            @PathVariable UUID id,
            @AuthenticationPrincipal Coordinator coordinator
    ) {
        Alert alert = alertRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Alert not found"));

        if (!alert.getCity().getId().equals(coordinator.getCity().getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized for this city");
        }

        alert.setActive(false);
        alertRepository.save(alert);
        return ResponseEntity.noContent().build();
    }
}