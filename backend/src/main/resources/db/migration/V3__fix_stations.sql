DELETE FROM monitoring_stations
WHERE station_code IN ('CODIGO_DA_ESTACAO_AQUI', '83500000');

UPDATE monitoring_stations
SET threshold_warning = 2050.0,
    threshold_alert = 2150.0,
    threshold_critical = 2250.0
WHERE station_code = '83740000';

INSERT INTO monitoring_stations (
    id, station_code, name, station_type, river_name, river_code, operator_acronym,
    latitude, longitude, altitude, threshold_warning, threshold_alert, threshold_critical, city_id
)
VALUES (
    gen_random_uuid(), '83800010', 'PCH SALTO JUSANTE', 'Fluviometrica', 'RIO ITAJAÍ-AÇU', '83001000', 'CELESC',
    -26.9175, -49.0661, 5.0, 400.0, 600.0, 800.0, (SELECT id FROM cities WHERE slug = 'rodeio')
)
ON CONFLICT (station_code) DO NOTHING;