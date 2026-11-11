package com.hydroguard.backend.services;

import com.hydroguard.backend.models.City;
import com.hydroguard.backend.repositories.CityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CityService {

    private final CityRepository cityRepository;

    public City getCityBySlug(String slug) {
        return cityRepository.findBySlug(slug)
                .orElseThrow(() -> new RuntimeException("City not found with slug: " + slug));
    }
}