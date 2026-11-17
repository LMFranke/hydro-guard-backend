package com.hydroguard.backend;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class BackendApplicationTests {

	@Test
	@DisplayName("Verifica carregamento da classe principal da aplicação")
	void applicationMainClassIsPresent() {
		BackendApplication app = new BackendApplication();
		assertNotNull(app);
	}
}