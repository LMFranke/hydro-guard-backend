package com.hydroguard.backend;

import com.hydroguard.backend.models.City;
import com.hydroguard.backend.models.Coordinator;
import com.hydroguard.backend.services.TokenService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TokenServiceSecurityTest {

    private static final String TEST_SECRET = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";

    @Test
    @DisplayName("Deve impedir inicialização do TokenService caso JWT_SECRET esteja vazio ou ausente")
    void shouldFailWhenJwtSecretIsBlank() {
        assertThrows(IllegalStateException.class, () -> new TokenService(""));
        assertThrows(IllegalStateException.class, () -> new TokenService("   "));
        assertThrows(IllegalStateException.class, () -> new TokenService(null));
    }

    @Test
    @DisplayName("Deve gerar token JWT assinado e validar o subject do coordenador corretamente")
    void shouldGenerateAndValidateJwtToken() {
        TokenService tokenService = new TokenService(TEST_SECRET);

        City city = City.builder().slug("rodeio").name("Rodeio").build();
        Coordinator coordinator = Coordinator.builder()
                .email("operador.demo@hydroguard.sc")
                .name("Operador Demo")
                .city(city)
                .build();

        String token = tokenService.generateToken(coordinator);
        assertNotNull(token);
        assertFalse(token.isBlank());

        String extractedEmail = tokenService.validateToken(token);
        assertEquals("operador.demo@hydroguard.sc", extractedEmail);
    }

    @Test
    @DisplayName("Deve rejeitar token JWT adulterado ou assinado com outro segredo")
    void shouldRejectForgedOrInvalidToken() {
        TokenService legitimateService = new TokenService(TEST_SECRET);
        TokenService attackerService = new TokenService("outro-segredo-invalido-1234567890");

        City city = City.builder().slug("rodeio").name("Rodeio").build();
        Coordinator coordinator = Coordinator.builder()
                .email("operador.demo@hydroguard.sc")
                .city(city)
                .build();

        String forgedToken = attackerService.generateToken(coordinator);

        assertEquals("", legitimateService.validateToken(forgedToken));
        assertEquals("", legitimateService.validateToken("token.malformado.invalido"));
    }
}