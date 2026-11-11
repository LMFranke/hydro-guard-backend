package com.hydroguard.backend.repositories;

import com.hydroguard.backend.models.Coordinator;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CoordinatorRepository extends JpaRepository<Coordinator, UUID> {
    @EntityGraph(attributePaths = "city")
    Optional<Coordinator> findByEmail(String email);
}