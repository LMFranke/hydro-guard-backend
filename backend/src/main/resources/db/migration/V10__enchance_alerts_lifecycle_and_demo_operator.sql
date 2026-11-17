ALTER TABLE alerts
    ADD COLUMN IF NOT EXISTS source VARCHAR(150) NOT NULL DEFAULT 'Defesa Civil Municipal',
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN IF NOT EXISTS expires_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS closed_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS latitude DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS longitude DOUBLE PRECISION;

UPDATE alerts
SET expires_at = created_at + INTERVAL '24 hours',
    updated_at = created_at
WHERE expires_at IS NULL;

INSERT INTO coordinators (id, name, email, password, city_id)
VALUES (
           gen_random_uuid(),
           'Operador Demonstração (CP3)',
           'operador.demo@hydroguard.sc',
           '$2a$10$trT3.R/Nfey62eczbKEnueTcIbJXW.u1ffAo/XfyLpofwNDbEB86O',
           (SELECT id FROM cities WHERE slug = 'rodeio')
       )
    ON CONFLICT (email) DO NOTHING;