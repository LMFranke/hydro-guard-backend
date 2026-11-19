package com.hydroguard.backend.repositories;

import com.hydroguard.backend.models.CitySimulationGeometry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CitySimulationGeometryRepository extends JpaRepository<CitySimulationGeometry, String> {
}