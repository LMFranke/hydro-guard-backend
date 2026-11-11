package com.hydroguard.backend.repositories;

import com.hydroguard.backend.models.Alert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AlertRepository extends JpaRepository<Alert, UUID> {
    List<Alert> findByCitySlugAndActiveTrueOrderByCreatedAtDesc(String slug);
}