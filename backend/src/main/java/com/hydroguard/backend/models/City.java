package com.hydroguard.backend.models;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "cities")
public class City {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String slug;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, length = 2)
    private String state;

    @Column(name = "ana_municipality_code")
    private String anaMunicipalityCode;

    private String address;

    private String phone;

    private String email;

    @Column(name = "logo_url")
    private String logoUrl;

    @Column(name = "banner_url")
    private String bannerUrl;

    @Column(name = "map_lat", nullable = false)
    private Double mapLat;

    @Column(name = "map_lng", nullable = false)
    private Double mapLng;

    @Column(name = "theme_color")
    private String themeColor;

    @JsonIgnore
    @OneToMany(mappedBy = "city", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<MonitoringStation> stations;
}