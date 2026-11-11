plugins {
	java
	id("org.springframework.boot") version "4.1.0"
	id("org.flywaydb.flyway") version "12.9.0"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "com.hydroguard"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("jakarta.persistence:jakarta.persistence-api")
	implementation("jakarta.validation:jakarta.validation-api")
	compileOnly("org.projectlombok:lombok")
	developmentOnly("org.springframework.boot:spring-boot-devtools")
	runtimeOnly("org.postgresql:postgresql")
	implementation("org.flywaydb:flyway-core")
	implementation("org.flywaydb:flyway-database-postgresql")
	annotationProcessor("org.projectlombok:lombok")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testCompileOnly("org.projectlombok:lombok")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
	testAnnotationProcessor("org.projectlombok:lombok")
	implementation("com.auth0:java-jwt:4.4.0")
}

flyway {
	url = "jdbc:postgresql://localhost:5432/hydroguard"
	user = "postgres"
	password = "postgres"
	schemas = arrayOf("public")
	locations = arrayOf("filesystem:src/main/resources/db/migration")
}

tasks.withType<Test> {
	useJUnitPlatform()
}
