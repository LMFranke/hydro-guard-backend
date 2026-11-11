package com.hydroguard.backend.controllers;

import com.hydroguard.backend.dtos.AuthenticationDTO;
import com.hydroguard.backend.dtos.ChangePasswordDTO;
import com.hydroguard.backend.dtos.LoginResponseDTO;
import com.hydroguard.backend.models.Coordinator;
import com.hydroguard.backend.repositories.CoordinatorRepository;
import com.hydroguard.backend.services.TokenService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final TokenService tokenService;
    private final CoordinatorRepository coordinatorRepository;
    private final PasswordEncoder passwordEncoder;

    @PostMapping("/login")
    public ResponseEntity<LoginResponseDTO> login(@RequestBody @Valid AuthenticationDTO data) {
        var usernamePassword = new UsernamePasswordAuthenticationToken(data.email(), data.password());
        var auth = this.authenticationManager.authenticate(usernamePassword);

        Coordinator coordinator = (Coordinator) auth.getPrincipal();
        var token = tokenService.generateToken(coordinator);

        return ResponseEntity.ok(new LoginResponseDTO(
                token,
                coordinator.getName(),
                coordinator.getEmail(),
                coordinator.getCity().getSlug()
        ));
    }

    @PatchMapping("/change-password")
    public ResponseEntity<Void> changePassword(
            @RequestBody @Valid ChangePasswordDTO data,
            @AuthenticationPrincipal Coordinator coordinator
    ) {
        if (!passwordEncoder.matches(data.currentPassword(), coordinator.getPassword())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Current password is incorrect");
        }

        coordinator.setPassword(passwordEncoder.encode(data.newPassword()));
        coordinatorRepository.save(coordinator);
        return ResponseEntity.noContent().build();
    }
}