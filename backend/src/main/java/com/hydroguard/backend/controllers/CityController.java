package com.hydroguard.backend.controllers;

import com.hydroguard.backend.models.City;
import com.hydroguard.backend.repositories.CityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/tenant")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class CityController {

    private final CityRepository cityRepository;

    @GetMapping("/{slug}")
    public ResponseEntity<City> getCityBySlug(@PathVariable String slug) {
        return cityRepository.findBySlug(slug)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}