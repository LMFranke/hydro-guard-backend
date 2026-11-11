CREATE TABLE risk_zones (
                            id UUID PRIMARY KEY,
                            name VARCHAR(255) NOT NULL,
                            zone_type VARCHAR(50) NOT NULL, -- FLOOD or LANDSLIDE
                            risk_level VARCHAR(50) NOT NULL, -- HIGH, MEDIUM, LOW
                            latitude DOUBLE PRECISION NOT NULL,
                            longitude DOUBLE PRECISION NOT NULL,
                            radius INTEGER NOT NULL,
                            description TEXT NOT NULL,
                            city_id UUID NOT NULL,
                            CONSTRAINT fk_risk_zone_city FOREIGN KEY (city_id) REFERENCES cities(id) ON DELETE CASCADE
);

INSERT INTO risk_zones (id, name, zone_type, risk_level, latitude, longitude, radius, description, city_id)
VALUES
    (
        gen_random_uuid(), 'Portal Rodeio', 'FLOOD', 'HIGH',
        -26.940288891715177, -49.368609159227354, 100,
        'Histórico de inundação rápida pelo Rio Benedito.',
        (SELECT id FROM cities WHERE slug = 'rodeio')
    ),
    (
        gen_random_uuid(), 'Bairro Glória', 'FLOOD', 'MEDIUM',
        -26.93529514119753, -49.37040264871344, 100,
        'Cota de inundação atinge vias principais.',
        (SELECT id FROM cities WHERE slug = 'rodeio')
    ),
    (
        gen_random_uuid(), 'Rua Luiz Pisetta', 'FLOOD', 'MEDIUM',
        -26.91997101682467, -49.368742908571505, 200,
        'Histórico de inundação rápida.',
        (SELECT id FROM cities WHERE slug = 'rodeio')
    ),
    (
        gen_random_uuid(), 'Rua Lucínio Cristofoline', 'FLOOD', 'LOW',
        -26.932278316143297, -49.36981530579197, 50,
        'Histórico de fortes enxurradas.',
        (SELECT id FROM cities WHERE slug = 'rodeio')
    ),
    (
        gen_random_uuid(), 'Rua Roberto Tambosi', 'FLOOD', 'MEDIUM',
        -26.929897048162328, -49.3740019663946, 75,
        'Histórico de inundação rápida.',
        (SELECT id FROM cities WHERE slug = 'rodeio')
    ),
    (
        gen_random_uuid(), 'Morro do Ipiranga', 'LANDSLIDE', 'HIGH',
        -26.905, -49.370, 400,
        'Solo saturado. Risco iminente de deslizamento de terra.',
        (SELECT id FROM cities WHERE slug = 'rodeio')
    ),
    (
        gen_random_uuid(), 'Encosta Bairro Nova I', 'LANDSLIDE', 'MEDIUM',
        -26.940, -49.355, 350,
        'Área com histórico de rolamento de blocos.',
        (SELECT id FROM cities WHERE slug = 'rodeio')
    ),
    (
        gen_random_uuid(), 'Encosta Bairro Nova II', 'LANDSLIDE', 'MEDIUM',
        -26.926625628658382, -49.36417167034003, 150,
        'Área com histórico com quedas de terra e árvores.',
        (SELECT id FROM cities WHERE slug = 'rodeio')
    );