package com.hydroguard.backend.models;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "monitoring_stations")
public class MonitoringStation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "station_code", nullable = false, unique = true)
    private String stationCode;

    @Column(nullable = false)
    private String name;

    @Column(name = "station_type", nullable = false)
    private String stationType; // Fluviometrica or Pluviometrica

    @Column(name = "river_name")
    private String riverName;

    @Column(name = "river_code")
    private String riverCode;

    @Column(name = "operator_acronym")
    private String operatorAcronym;

    private Double latitude;

    private Double longitude;

    private Double altitude;

    @Column(name = "threshold_warning", nullable = false)
    private Double thresholdWarning; // cm for Fluviometrica, mm (24h) for Pluviometrica

    @Column(name = "threshold_alert", nullable = false)
    private Double thresholdAlert;

    @Column(name = "threshold_critical", nullable = false)
    private Double thresholdCritical;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "city_id", nullable = false)
    private City city;
}