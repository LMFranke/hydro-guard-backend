INSERT INTO cities (
    id, slug, name, state, ana_municipality_code, address, phone, email,
    logo_url, banner_url, map_lat, map_lng, theme_color
) VALUES
(
    gen_random_uuid(),
    'blumenau',
    'Blumenau',
    'SC',
    '4202404',
    'Praça Victor Konder, 2 - Centro, Blumenau - SC',
    '(47) 3381-6000 / 199',
    'defesacivil@blumenau.sc.gov.br',
    NULL,
    NULL,
    -26.9194,
    -49.0661,
    '#dc2626'
),
(
    gen_random_uuid(),
    'rio-do-sul',
    'Rio do Sul',
    'SC',
    '4214805',
    'Praça 25 de Julho, 1 - Centro, Rio do Sul - SC',
    '(47) 3531-1200 / 199',
    'defesacivil@riodosul.sc.gov.br',
    NULL,
    NULL,
    -27.2156,
    -49.6430,
    '#059669'
);