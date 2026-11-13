-- ============================================================================
-- 1. CADASTRO DOS MUNICÍPIOS (APIÚNA, IBIRAMA, INDAIAL E TIMBÓ)
-- ============================================================================
INSERT INTO cities (
    id, slug, name, state, ana_municipality_code, address, phone, email,
    logo_url, banner_url, map_lat, map_lng, theme_color
) VALUES
      (
          gen_random_uuid(),
          'apiuna',
          'Apiúna',
          'SC',
          '4201257',
          'Rua Quintino Bocaiúva, 204 - Centro, Apiúna - SC',
          '(47) 3353-2000 / 199',
          'defesacivil@apiuna.sc.gov.br',
          NULL,
          NULL,
          -27.0358,
          -49.3897,
          '#0d9488'
      ),
      (
          gen_random_uuid(),
          'ibirama',
          'Ibirama',
          'SC',
          '4206900',
          'Rua Doutor Getúlio Vargas, 70 - Centro, Ibirama - SC',
          '(47) 3357-8500 / 199',
          'defesacivil@ibirama.sc.gov.br',
          NULL,
          NULL,
          -27.0569,
          -49.5178,
          '#ea580c'
      ),
      (
          gen_random_uuid(),
          'indaial',
          'Indaial',
          'SC',
          '4207502',
          'Avenida Getúlio Vargas, 126 - Centro, Indaial - SC',
          '(47) 3317-8800 / 199',
          'defesacivil@indaial.sc.gov.br',
          NULL,
          NULL,
          -26.8978,
          -49.2317,
          '#7c3aed'
      ),
      (
          gen_random_uuid(),
          'timbo',
          'Timbó',
          'SC',
          '4218204',
          'Avenida Getúlio Vargas, 700 - Centro, Timbó - SC',
          '(47) 3380-7000 / 199',
          'defesacivil@timbo.sc.gov.br',
          NULL,
          NULL,
          -26.8233,
          -49.2717,
          '#0284c7'
      )
    ON CONFLICT (slug) DO UPDATE SET
    name = EXCLUDED.name,
                              ana_municipality_code = EXCLUDED.ana_municipality_code,
                              address = EXCLUDED.address,
                              phone = EXCLUDED.phone,
                              email = EXCLUDED.email,
                              map_lat = EXCLUDED.map_lat,
                              map_lng = EXCLUDED.map_lng,
                              theme_color = EXCLUDED.theme_color;

-- ============================================================================
-- 2. COORDENADORES EXCLUSIVOS DE CADA MUNICÍPIO (SENHA PADRÃO: 123456)
-- ============================================================================
INSERT INTO coordinators (id, name, email, password, city_id)
VALUES
    (
        gen_random_uuid(),
        'Coordenador Apiúna',
        'coordenador@apiuna.sc.gov.br',
        '$2a$10$trT3.R/Nfey62eczbKEnueTcIbJXW.u1ffAo/XfyLpofwNDbEB86O',
        (SELECT id FROM cities WHERE slug = 'apiuna')
    ),
    (
        gen_random_uuid(),
        'Coordenador Ibirama',
        'coordenador@ibirama.sc.gov.br',
        '$2a$10$trT3.R/Nfey62eczbKEnueTcIbJXW.u1ffAo/XfyLpofwNDbEB86O',
        (SELECT id FROM cities WHERE slug = 'ibirama')
    ),
    (
        gen_random_uuid(),
        'Coordenador Indaial',
        'coordenador@indaial.sc.gov.br',
        '$2a$10$trT3.R/Nfey62eczbKEnueTcIbJXW.u1ffAo/XfyLpofwNDbEB86O',
        (SELECT id FROM cities WHERE slug = 'indaial')
    ),
    (
        gen_random_uuid(),
        'Coordenador Timbó',
        'coordenador@timbo.sc.gov.br',
        '$2a$10$trT3.R/Nfey62eczbKEnueTcIbJXW.u1ffAo/XfyLpofwNDbEB86O',
        (SELECT id FROM cities WHERE slug = 'timbo')
    )
    ON CONFLICT (email) DO NOTHING;

-- ============================================================================
-- 3. ZONAS DE RISCO INICIAIS (INUNDAÇÃO E DESLIZAMENTO POR MUNICÍPIO)
-- ============================================================================
INSERT INTO risk_zones (id, name, zone_type, risk_level, latitude, longitude, radius, description, city_id)
VALUES
-- Apiúna
(
    gen_random_uuid(),
    'Margem do Rio Itajaí-Açu (Centro / BR-470)',
    'FLOOD',
    'HIGH',
    -27.0345,
    -49.3882,
    260,
    'Planície ribeirinha sujeita a extravasamento do Rio Itajaí-Açu e represamento do Ribeirão São Pedro.',
    (SELECT id FROM cities WHERE slug = 'apiuna')
),
(
    gen_random_uuid(),
    'Encosta da Serra Subida / BR-470',
    'LANDSLIDE',
    'MEDIUM',
    -27.0430,
    -49.4015,
    200,
    'Taludes íngremes com histórico de escorregamento sob acumulado pluviométrico superior a 80mm.',
    (SELECT id FROM cities WHERE slug = 'apiuna')
),

-- Ibirama
(
    gen_random_uuid(),
    'Confluência Rio Hercílio e Ribeirão Taquaras (Centro)',
    'FLOOD',
    'HIGH',
    -27.0558,
    -49.5162,
    240,
    'Área central baixa impactada pela elevação rápida do Rio Itajaí do Norte (Hercílio).',
    (SELECT id FROM cities WHERE slug = 'ibirama')
),
(
    gen_random_uuid(),
    'Encosta Bairro Ponto Chic / Bela Vista',
    'LANDSLIDE',
    'HIGH',
    -27.0615,
    -49.5230,
    180,
    'Vertente ocupada com declividade acentuada e suscetibilidade a movimentos de massa.',
    (SELECT id FROM cities WHERE slug = 'ibirama')
),

-- Indaial
(
    gen_random_uuid(),
    'Calha do Rio Itajaí-Açu (Ponte dos Arcos / Centro)',
    'FLOOD',
    'HIGH',
    -26.8992,
    -49.2335,
    300,
    'Cota crítica de alagamento urbano nas imediações da Avenida Beira Rio e Bairro Warnow.',
    (SELECT id FROM cities WHERE slug = 'indaial')
),
(
    gen_random_uuid(),
    'Morro do Bairro Estradinha',
    'LANDSLIDE',
    'MEDIUM',
    -26.9065,
    -49.2440,
    190,
    'Área de monitoramento geotécnico preventivo em períodos de chuva persistente.',
    (SELECT id FROM cities WHERE slug = 'indaial')
),

-- Timbó
(
    gen_random_uuid(),
    'Confluência Rio Benedito e Rio dos Cedros (Centro)',
    'FLOOD',
    'HIGH',
    -26.8248,
    -49.2732,
    280,
    'Região central na junção das duas bacias, com resposta hidrológica rápida a enxurradas.',
    (SELECT id FROM cities WHERE slug = 'timbo')
),
(
    gen_random_uuid(),
    'Encosta Bairro Capitais / Araponguinhas',
    'LANDSLIDE',
    'MEDIUM',
    -26.8320,
    -49.2625,
    180,
    'Taludes monitorados pela Defesa Civil Municipal para prevenção de deslizamentos.',
    (SELECT id FROM cities WHERE slug = 'timbo')
);