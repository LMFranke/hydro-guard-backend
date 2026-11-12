package com.hydroguard.backend.controllers;

import com.hydroguard.backend.dtos.SystemHealthDTO;
import com.hydroguard.backend.integrations.ana.AnaClientService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class SystemHealthController {

    private final AnaClientService anaClientService;

    @GetMapping("/health")
    public ResponseEntity<SystemHealthDTO> getSystemHealth() {
        return ResponseEntity.ok(anaClientService.checkAnaHealth());
    }
}