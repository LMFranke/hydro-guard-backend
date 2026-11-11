package com.hydroguard.backend.repositories;

import com.hydroguard.backend.models.MonitoringStation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MonitoringStationRepository extends JpaRepository<MonitoringStation, UUID> {
    List<MonitoringStation> findByCitySlug(String slug);
    Optional<MonitoringStation> findByStationCode(String stationCode);
}