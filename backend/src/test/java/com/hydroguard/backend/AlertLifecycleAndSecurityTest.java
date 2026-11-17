package com.hydroguard.backend;

import com.hydroguard.backend.controllers.AlertController;
import com.hydroguard.backend.controllers.RiskZoneController;
import com.hydroguard.backend.dtos.AlertRequestDTO;
import com.hydroguard.backend.dtos.RiskZoneRequestDTO;
import com.hydroguard.backend.models.Alert;
import com.hydroguard.backend.models.City;
import com.hydroguard.backend.models.Coordinator;
import com.hydroguard.backend.models.RiskZone;
import com.hydroguard.backend.repositories.AlertRepository;
import com.hydroguard.backend.repositories.RiskZoneRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AlertLifecycleAndSecurityTest {

    @Mock
    private AlertRepository alertRepository;

    @Mock
    private RiskZoneRepository riskZoneRepository;

    @InjectMocks
    private AlertController alertController;

    @InjectMocks
    private RiskZoneController riskZoneController;

    private City cityRodeio;
    private City cityBlumenau;
    private Coordinator coordinatorRodeio;
    private Coordinator coordinatorBlumenau;

    @BeforeEach
    void setUp() {
        cityRodeio = City.builder()
                .id(UUID.randomUUID())
                .slug("rodeio")
                .name("Rodeio")
                .state("SC")
                .mapLat(-26.9248)
                .mapLng(-49.3664)
                .build();

        cityBlumenau = City.builder()
                .id(UUID.randomUUID())
                .slug("blumenau")
                .name("Blumenau")
                .state("SC")
                .mapLat(-26.9194)
                .mapLng(-49.0661)
                .build();

        coordinatorRodeio = Coordinator.builder()
                .id(UUID.randomUUID())
                .name("Operador Rodeio")
                .email("operador.demo@hydroguard.sc")
                .city(cityRodeio)
                .build();

        coordinatorBlumenau = Coordinator.builder()
                .id(UUID.randomUUID())
                .name("Operador Blumenau")
                .email("coordenador@blumenau.sc.gov.br")
                .city(cityBlumenau)
                .build();
    }

    @Test
    @DisplayName("Deve separar corretamente alertas ATIVOS do HISTÓRICO (EXPIRADOS e ENCERRADOS)")
    void shouldSeparateActiveAlertsFromExpiredAndClosedHistory() {
        LocalDateTime now = LocalDateTime.now();

        Alert activeAlert = Alert.builder()
                .id(UUID.randomUUID())
                .title("Risco de Alagamento")
                .severity("WARNING")
                .region("Centro")
                .source("Defesa Civil de Rodeio")
                .active(true)
                .createdAt(now.minusHours(2))
                .updatedAt(now.minusHours(2))
                .expiresAt(now.plusHours(10))
                .city(cityRodeio)
                .build();

        Alert expiredAlert = Alert.builder()
                .id(UUID.randomUUID())
                .title("Chuva Intensa Passada")
                .severity("WATCH")
                .region("Bairro Glória")
                .source("Defesa Civil de Rodeio")
                .active(true)
                .createdAt(now.minusHours(30))
                .updatedAt(now.minusHours(30))
                .expiresAt(now.minusHours(6))
                .city(cityRodeio)
                .build();

        Alert closedAlert = Alert.builder()
                .id(UUID.randomUUID())
                .title("Alerta já Encerrado")
                .severity("CRITICAL")
                .region("Margem Rio Benedito")
                .source("Defesa Civil de Rodeio")
                .active(false)
                .createdAt(now.minusHours(5))
                .updatedAt(now.minusHours(1))
                .expiresAt(now.plusHours(7))
                .closedAt(now.minusHours(1))
                .city(cityRodeio)
                .build();

        when(alertRepository.findByCitySlugOrderByCreatedAtDesc("rodeio"))
                .thenReturn(List.of(activeAlert, expiredAlert, closedAlert));

        List<Alert> activeResult = alertController.getActiveAlerts("rodeio").getBody();
        List<Alert> historyResult = alertController.getAlertHistory("rodeio").getBody();

        assertNotNull(activeResult);
        assertEquals(1, activeResult.size());
        assertEquals("ACTIVE", activeResult.get(0).getLifecycleStatus());
        assertEquals("Risco de Alagamento", activeResult.get(0).getTitle());

        assertNotNull(historyResult);
        assertEquals(2, historyResult.size());
        assertEquals("EXPIRED", expiredAlert.getLifecycleStatus());
        assertEquals("CLOSED", closedAlert.getLifecycleStatus());
    }

    @Test
    @DisplayName("Deve publicar alerta com origem, validade e coordenadas quando operador está autenticado")
    void shouldCreateAlertWithOriginAndValidityWhenAuthenticated() {
        AlertRequestDTO request = new AlertRequestDTO(
                "Elevação Rápida do Nível do Rio",
                "CRITICAL",
                "Centro e Beira Rio",
                "Nível subiu 1,2m na última hora.",
                "Evite pontes submersas\nAcione o 199",
                "Defesa Civil Municipal de Rodeio",
                24,
                null,
                null,
                null
        );

        when(alertRepository.save(any(Alert.class))).thenAnswer(inv -> inv.getArgument(0));

        ResponseEntity<Alert> response = alertController.createAlert(request, coordinatorRodeio);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        Alert saved = response.getBody();
        assertNotNull(saved);
        assertEquals("ACTIVE", saved.getLifecycleStatus());
        assertEquals("Defesa Civil Municipal de Rodeio", saved.getSource());
        assertEquals(cityRodeio.getMapLat(), saved.getLatitude());
        assertNotNull(saved.getExpiresAt());
        assertTrue(saved.getExpiresAt().isAfter(LocalDateTime.now().plusHours(23)));
    }

    @Test
    @DisplayName("Deve bloquear tentativa de publicação de alerta sem autenticação (401 UNAUTHORIZED)")
    void shouldRejectAlertCreationWithoutAuthentication() {
        AlertRequestDTO request = new AlertRequestDTO(
                "Alerta Falso",
                "CRITICAL",
                "Centro",
                "Tentativa sem token",
                "Orientação",
                null,
                12,
                null,
                null,
                null
        );

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> alertController.createAlert(request, null)
        );

        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
        verify(alertRepository, never()).save(any());
    }

    @Test
    @DisplayName("Deve impedir que coordenador de outro município encerre alerta alheio (403 FORBIDDEN)")
    void shouldForbidCoordinatorFromDeactivatingAnotherCityAlert() {
        UUID alertId = UUID.randomUUID();
        Alert rodeioAlert = Alert.builder()
                .id(alertId)
                .title("Alerta de Rodeio")
                .active(true)
                .city(cityRodeio)
                .build();

        when(alertRepository.findById(alertId)).thenReturn(Optional.of(rodeioAlert));

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> alertController.deactivateAlert(alertId, coordinatorBlumenau)
        );

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertTrue(rodeioAlert.getActive());
        verify(alertRepository, never()).save(any());
    }

    @Test
    @DisplayName("Deve encerrar alerta ativo do próprio município registrando closedAt e status CLOSED")
    void shouldDeactivateOwnCityAlertAndRecordClosedTimestamp() {
        UUID alertId = UUID.randomUUID();
        Alert rodeioAlert = Alert.builder()
                .id(alertId)
                .title("Alerta de Rodeio")
                .active(true)
                .createdAt(LocalDateTime.now().minusHours(3))
                .updatedAt(LocalDateTime.now().minusHours(3))
                .expiresAt(LocalDateTime.now().plusHours(9))
                .city(cityRodeio)
                .build();

        when(alertRepository.findById(alertId)).thenReturn(Optional.of(rodeioAlert));
        when(alertRepository.save(any(Alert.class))).thenAnswer(inv -> inv.getArgument(0));

        ResponseEntity<Alert> response = alertController.deactivateAlert(alertId, coordinatorRodeio);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Alert updated = response.getBody();
        assertNotNull(updated);
        assertFalse(updated.getActive());
        assertNotNull(updated.getClosedAt());
        assertEquals("CLOSED", updated.getLifecycleStatus());
    }

    @Test
    @DisplayName("Deve impedir edição ou exclusão de Zona de Risco por coordenador de outro município (403 FORBIDDEN)")
    void shouldForbidCrossTenantRiskZoneModification() {
        UUID zoneId = UUID.randomUUID();
        RiskZone zoneRodeio = RiskZone.builder()
                .id(zoneId)
                .name("Morro do Ipiranga")
                .city(cityRodeio)
                .build();

        when(riskZoneRepository.findById(zoneId)).thenReturn(Optional.of(zoneRodeio));

        RiskZoneRequestDTO updateDto = new RiskZoneRequestDTO(
                "Tentativa Invasão", "LANDSLIDE", "HIGH", -26.9, -49.3, 200, "Teste"
        );

        ResponseStatusException updateEx = assertThrows(
                ResponseStatusException.class,
                () -> riskZoneController.updateRiskZone(zoneId, updateDto, coordinatorBlumenau)
        );
        assertEquals(HttpStatus.FORBIDDEN, updateEx.getStatusCode());

        ResponseStatusException deleteEx = assertThrows(
                ResponseStatusException.class,
                () -> riskZoneController.deleteRiskZone(zoneId, coordinatorBlumenau)
        );
        assertEquals(HttpStatus.FORBIDDEN, deleteEx.getStatusCode());
    }
}