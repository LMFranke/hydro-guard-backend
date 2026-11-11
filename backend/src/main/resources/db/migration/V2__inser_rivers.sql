INSERT INTO monitoring_stations (
    id, station_code, name, station_type, river_name, river_code, operator_acronym,
    latitude, longitude, altitude, threshold_warning, threshold_alert, threshold_critical, city_id
)
VALUES
    (
        gen_random_uuid(), '83500000', 'APIÚNA - RÉGUA NOVA', 'Fluviometrica', 'RIO ITAJAÍ-AÇU', '83001000', 'EPAGRI-SC',
        -27.0375, -49.3919, 93.0, 400.0, 500.0, 600.0, (SELECT id FROM cities WHERE slug = 'rodeio')
    ),
    (
        gen_random_uuid(), '83740000', 'PCH SALTO BARRAMENTO', 'Fluviometrica', 'RIO ITAJAÍ-AÇU', '83001000', 'CELESC',
        -26.8800, -49.1089, 25.0, 400.0, 500.0, 600.0, (SELECT id FROM cities WHERE slug = 'rodeio')
    )
    ON CONFLICT (station_code) DO NOTHING;