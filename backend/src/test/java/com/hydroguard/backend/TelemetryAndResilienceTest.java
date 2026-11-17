package com.hydroguard.backend;

import com.hydroguard.backend.dtos.StationTelemetryDTO;
import com.hydroguard.backend.dtos.StationTelemetryDTO.TelemetryReadingDTO;
import com.hydroguard.backend.dtos.SystemHealthDTO;
import com.hydroguard.backend.dtos.SystemHealthDTO.ServiceStatus;
import com.hydroguard.backend.integrations.ana.AnaClientService;
import com.hydroguard.backend.integrations.ana.AnaResilienceManager;
import com.hydroguard.backend.integrations.ana.AnaStationDiscoveryService;
import com.hydroguard.backend.integrations.ana.dto.AnaTelemetryResponseDTO.AnaTelemetryItemDTO;
import com.hydroguard.backend.models.City;
import com.hydroguard.backend.models.MonitoringStation;
import com.hydroguard.backend.repositories.CityRepository;
import com.hydroguard.backend.services.TelemetryCacheManager;
import com.hydroguard.backend.services.TelemetryMapper;
import com.hydroguard.backend.services.TelemetryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TelemetryAndResilienceTest {

    private TelemetryMapper telemetryMapper;
    private TelemetryCacheManager cacheManager;
    private AnaResilienceManager resilienceManager;

    @Mock
    private CityRepository cityRepository;

    @Mock
    private AnaClientService anaClientService;

    @Mock
    private AnaStationDiscoveryService discoveryService;

    private TelemetryService telemetryService;

    @BeforeEach
    void setUp() {
        telemetryMapper = new TelemetryMapper();
        cacheManager = new TelemetryCacheManager();
        resilienceManager = new AnaResilienceManager();
        telemetryService = new TelemetryService(
                cityRepository,
                anaClientService,
                discoveryService,
                cacheManager,
                telemetryMapper
        );
    }

    @Test
    @DisplayName("Deve rejeitar estação fluviométrica sem leituras válidas (nunca forjar status NORMAL)")
    void shouldRejectFluviometricStationWithMissingReadings() {
        MonitoringStation station = MonitoringStation.builder()
                .stationCode("83662000")
                .name("APIÚNA - RÉGUA NOVA")
                .stationType("Fluviometrica")
                .thresholdWarning(400.0)
                .thresholdAlert(500.0)
                .thresholdCritical(600.0)
                .build();

        List<TelemetryReadingDTO> emptyHistory = telemetryMapper.mapRawItemsToHistory(Collections.emptyList());
        assertFalse(telemetryMapper.hasValidTelemetryForStationType(station, emptyHistory));

        List<AnaTelemetryItemDTO> nullLevelItems = List.of(
                new AnaTelemetryItemDTO("83662000", "2026-11-16 14:00:00", null, "0.0", null, null, null, null)
        );
        List<TelemetryReadingDTO> historyWithoutLevel = telemetryMapper.mapRawItemsToHistory(nullLevelItems);
        assertFalse(telemetryMapper.hasValidTelemetryForStationType(station, historyWithoutLevel));
    }

    @Test
    @DisplayName("Deve retornar lista vazia quando a API da ANA está indisponível e não há cache prévio")
    void shouldReturnEmptyListWhenAnaIsOfflineAndNoCacheExists() {
        MonitoringStation station = MonitoringStation.builder()
                .stationCode("83677000")
                .name("PCH RODEIO MONTANTE")
                .stationType("Fluviometrica")
                .thresholdWarning(300.0)
                .thresholdAlert(450.0)
                .thresholdCritical(600.0)
                .build();

        City city = City.builder()
                .id(UUID.randomUUID())
                .slug("rodeio")
                .name("Rodeio")
                .state("SC")
                .stations(List.of(station))
                .build();

        when(cityRepository.findBySlug("rodeio")).thenReturn(Optional.of(city));
        when(discoveryService.discoverStationsForCity(city)).thenReturn(Collections.emptyList());
        when(anaClientService.isCircuitOpen()).thenReturn(true);

        List<StationTelemetryDTO> result = telemetryService.getCityTelemetry("rodeio");

        assertNotNull(result);
        assertTrue(result.isEmpty(), "Sem dados reais da ANA, deve retornar lista vazia para acionar estado 'Dado Indisponível'");
    }

    @Test
    @DisplayName("Deve converter cota de centímetros para metros, somar chuva de 24h e classificar status CRITICAL")
    void shouldConvertCmToMetersAndEvaluateCriticalStatus() {
        MonitoringStation station = MonitoringStation.builder()
                .stationCode("83300200")
                .name("RIO DO SUL NOVO")
                .stationType("Fluviometrica")
                .riverName("RIO ITAJAÍ-AÇU")
                .latitude(-27.2142)
                .longitude(-49.6433)
                .thresholdWarning(450.0)
                .thresholdAlert(650.0)
                .thresholdCritical(850.0)
                .build();

        List<AnaTelemetryItemDTO> rawItems = List.of(
                new AnaTelemetryItemDTO("83300200", "2026-11-16 12:00:00", null, "12.5", null, "810", null, "420.0"),
                new AnaTelemetryItemDTO("83300200", "2026-11-16 13:00:00", null, "15.0", null, "875", null, "485.5")
        );

        List<TelemetryReadingDTO> history = telemetryMapper.mapRawItemsToHistory(rawItems);
        assertTrue(telemetryMapper.hasValidTelemetryForStationType(station, history));

        StationTelemetryDTO dto = telemetryMapper.buildStationTelemetryDTO(station, history);

        assertEquals(8.75, dto.currentLevelMeters());
        assertEquals(485.5, dto.currentFlowM3s());
        assertEquals(27.5, dto.accumulatedRain24hMm());
        assertEquals("CRITICAL", dto.status());
        assertEquals(4.5, dto.thresholdWarning());
        assertEquals(6.5, dto.thresholdAlert());
        assertEquals(8.5, dto.thresholdCritical());
    }

    @Test
    @DisplayName("Deve abrir Circuit Breaker e reportar status OFFLINE quando a API da ANA falha")
    void shouldOpenCircuitBreakerAndReportOfflineStatus() {
        resilienceManager.recordSuccess(320);
        SystemHealthDTO healthy = resilienceManager.buildHealthSnapshot();
        assertEquals(ServiceStatus.ONLINE, healthy.anaApiStatus());

        resilienceManager.openCircuit(30, "Timeout ao conectar na ANA.");

        assertTrue(resilienceManager.isCircuitOpen());
        SystemHealthDTO offline = resilienceManager.buildHealthSnapshot();
        assertEquals(ServiceStatus.ONLINE, offline.backendStatus());
        assertEquals(ServiceStatus.OFFLINE, offline.anaApiStatus());
        assertEquals("Timeout ao conectar na ANA.", offline.message());
    }
}