package com.hydroguard.backend.models;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "alerts")
public class Alert {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, length = 50)
    private String severity;

    @Column(nullable = false)
    private String region;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String description;

    @Column(name = "safety_guidelines", columnDefinition = "TEXT", nullable = false)
    private String safetyGuidelines;

    @Column(nullable = false, length = 150)
    private String source;

    @Column(name = "active", nullable = false)
    private Boolean active;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    private Double latitude;

    private Double longitude;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "city_id", nullable = false)
    private City city;

    @JsonProperty("lifecycleStatus")
    public String getLifecycleStatus() {
        if (Boolean.FALSE.equals(active) || closedAt != null) {
            return "CLOSED";
        }
        if (expiresAt != null && LocalDateTime.now().isAfter(expiresAt)) {
            return "EXPIRED";
        }
        return "ACTIVE";
    }

    @JsonIgnore
    public boolean isCurrentlyActive() {
        return "ACTIVE".equals(getLifecycleStatus());
    }
}