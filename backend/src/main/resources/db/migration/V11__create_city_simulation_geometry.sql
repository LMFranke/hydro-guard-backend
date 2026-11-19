CREATE TABLE IF NOT EXISTS city_simulation_geometries (
                                                          city_slug VARCHAR(100) PRIMARY KEY,
    payload_json TEXT NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL
    );