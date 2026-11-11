package com.hydroguard.backend.repositories;

import com.hydroguard.backend.models.RiskZone;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface RiskZoneRepository extends JpaRepository<RiskZone, UUID> {
    List<RiskZone> findByCitySlugOrderByNameAsc(String slug);
}