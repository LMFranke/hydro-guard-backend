CREATE TABLE cities (
                        id UUID PRIMARY KEY,
                        slug VARCHAR(255) NOT NULL UNIQUE,
                        name VARCHAR(255) NOT NULL,
                        state VARCHAR(2) NOT NULL,
                        ana_municipality_code VARCHAR(50),
                        address VARCHAR(255),
                        phone VARCHAR(50),
                        email VARCHAR(255),
                        logo_url VARCHAR(255),
                        banner_url VARCHAR(255),
                        map_lat DOUBLE PRECISION NOT NULL,
                        map_lng DOUBLE PRECISION NOT NULL,
                        theme_color VARCHAR(50)
);

CREATE TABLE monitoring_stations (
                                     id UUID PRIMARY KEY,
                                     station_code VARCHAR(50) NOT NULL UNIQUE,
                                     name VARCHAR(255) NOT NULL,
                                     station_type VARCHAR(50) NOT NULL,
                                     river_name VARCHAR(255),
                                     river_code VARCHAR(50),
                                     operator_acronym VARCHAR(50),
                                     latitude DOUBLE PRECISION,
                                     longitude DOUBLE PRECISION,
                                     altitude DOUBLE PRECISION,
                                     threshold_warning DOUBLE PRECISION NOT NULL,
                                     threshold_alert DOUBLE PRECISION NOT NULL,
                                     threshold_critical DOUBLE PRECISION NOT NULL,
                                     city_id UUID NOT NULL,
                                     CONSTRAINT fk_station_city FOREIGN KEY (city_id) REFERENCES cities(id) ON DELETE CASCADE
);

CREATE TABLE coordinators (
                              id UUID PRIMARY KEY,
                              name VARCHAR(255) NOT NULL,
                              email VARCHAR(255) NOT NULL UNIQUE,
                              password VARCHAR(255) NOT NULL,
                              city_id UUID NOT NULL,
                              CONSTRAINT fk_coordinator_city FOREIGN KEY (city_id) REFERENCES cities(id) ON DELETE CASCADE
);

CREATE TABLE alerts (
                        id UUID PRIMARY KEY,
                        title VARCHAR(255) NOT NULL,
                        severity VARCHAR(50) NOT NULL,
                        region VARCHAR(255) NOT NULL,
                        description TEXT NOT NULL,
                        safety_guidelines TEXT NOT NULL,
                        active BOOLEAN NOT NULL DEFAULT TRUE,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        city_id UUID NOT NULL,
                        CONSTRAINT fk_alert_city FOREIGN KEY (city_id) REFERENCES cities(id) ON DELETE CASCADE
);

INSERT INTO cities (id, slug, name, state, ana_municipality_code, address, phone, email, logo_url, banner_url, map_lat, map_lng, theme_color)
VALUES (
           gen_random_uuid(),
           'rodeio',
           'Rodeio',
           'SC',
           '23149000',
           'Rua Barão do Rio Branco, 1069 - Centro, Rodeio - SC',
           '(47) 3384-0161 / 199',
           'defesacivil@rodeio.sc.gov.br',
           '/src/assets/logo-rodeio.png',
           '/src/assets/banner-rodeio.png',
           -26.9248,
           -49.3739,
           '#1A56DB'
       );

INSERT INTO coordinators (id, name, email, password, city_id)
VALUES (
           gen_random_uuid(),
           'Coordenador Rodeio',
           'coordenador@hydroguard.sc',
           '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
           (SELECT id FROM cities WHERE slug = 'rodeio')
       );

INSERT INTO monitoring_stations (
    id, station_code, name, station_type, river_name, river_code, operator_acronym,
    latitude, longitude, altitude, threshold_warning, threshold_alert, threshold_critical, city_id
)
VALUES (
           gen_random_uuid(),
           'CODIGO_DA_ESTACAO_AQUI',
           'NOME_DA_ESTACAO_AQUI',
           'Fluviometrica',
           'RIO ITAJAÍ-AÇU',
           '83001000',
           'ANA',
           -26.8970,
           -49.2310,
           60.0,
           400.0,
           500.0,
           600.0,
           (SELECT id FROM cities WHERE slug = 'rodeio')
       );

INSERT INTO monitoring_stations (
    id, station_code, name, station_type, river_name, river_code, operator_acronym,
    latitude, longitude, altitude, threshold_warning, threshold_alert, threshold_critical, city_id
)
VALUES
    (
        gen_random_uuid(), '83662000', 'PCH RODEIO MONTANTE', 'Fluviometrica', 'RIO BENEDITO', '83400000', 'RODEIO',
        -26.8047, -49.3567, 146.0, 300.0, 450.0, 600.0, (SELECT id FROM cities WHERE slug = 'rodeio')
    ),
    (
        gen_random_uuid(), '2649157', 'RODEIO_BR 470', 'Pluviometrica', 'N/A', NULL, 'CEMADEN',
        -26.952, -49.353, 77.0, 40.0, 70.0, 100.0, (SELECT id FROM cities WHERE slug = 'rodeio')
    ),
    (
        gen_random_uuid(), '2649158', 'RODEIO_Ipiranga', 'Pluviometrica', 'N/A', NULL, 'CEMADEN',
        -26.872, -49.385, 532.0, 40.0, 70.0, 100.0, (SELECT id FROM cities WHERE slug = 'rodeio')
    ),
    (
        gen_random_uuid(), '2649159', 'RODEIO_Rodeio 12', 'Pluviometrica', 'N/A', NULL, 'CEMADEN',
        -26.846, -49.326, 73.0, 40.0, 70.0, 100.0, (SELECT id FROM cities WHERE slug = 'rodeio')
    ),
    (
        gen_random_uuid(), '2649160', 'RODEIO_Centro', 'Pluviometrica', 'N/A', NULL, 'CEMADEN',
        -26.924, -49.369, 85.0, 40.0, 70.0, 100.0, (SELECT id FROM cities WHERE slug = 'rodeio')
    );