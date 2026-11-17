-- ============================================================================
-- 1. CORRIGE VÍNCULO DAS ESTAÇÕES DE BLUMENAU QUE ESTAVAM ASSOCIADAS A RODEIO
-- ============================================================================
UPDATE monitoring_stations
SET city_id = (SELECT id FROM cities WHERE slug = 'blumenau')
WHERE station_code IN ('83740000', '83800010')
  AND EXISTS (SELECT 1 FROM cities WHERE slug = 'blumenau');

-- Limpa caminhos locais legados caso alguma cidade ainda tenha referência a /src/assets/
UPDATE cities
SET logo_url = NULL
WHERE logo_url LIKE '/src/assets/%';

UPDATE cities
SET banner_url = NULL
WHERE banner_url LIKE '/src/assets/%';

-- ============================================================================
-- 2. FUNÇÃO DE PROVISIONAMENTO RÁPIDO PARA NOVOS MUNICÍPIOS DE SANTA CATARINA
-- ============================================================================
CREATE OR REPLACE FUNCTION provision_sc_city(
    p_slug VARCHAR,
    p_name VARCHAR,
    p_ibge_code VARCHAR,
    p_lat DOUBLE PRECISION,
    p_lng DOUBLE PRECISION,
    p_theme_color VARCHAR DEFAULT '#2563eb',
    p_address VARCHAR DEFAULT NULL,
    p_phone VARCHAR DEFAULT '199'
) RETURNS UUID AS $$
DECLARE
v_city_id UUID;
    v_storage_base CONSTANT VARCHAR := 'https://ptodegomcuosjdvireax.supabase.co/storage/v1/object/public/city-assets';
    v_clean_slug VARCHAR := lower(trim(p_slug));
BEGIN
INSERT INTO cities (
    id, slug, name, state, ana_municipality_code, address, phone, email,
    logo_url, banner_url, map_lat, map_lng, theme_color
) VALUES (
             gen_random_uuid(),
             v_clean_slug,
             trim(p_name),
             'SC',
             p_ibge_code,
             COALESCE(p_address, 'Centro Administrativo - ' || trim(p_name) || ' - SC'),
             p_phone,
             'defesacivil@' || v_clean_slug || '.sc.gov.br',
             v_storage_base || '/' || v_clean_slug || '/' || v_clean_slug || '-logo.png',
             v_storage_base || '/' || v_clean_slug || '/' || v_clean_slug || '-banner.jpg',
             p_lat,
             p_lng,
             p_theme_color
         )
    ON CONFLICT (slug) DO UPDATE SET
    name = EXCLUDED.name,
                              ana_municipality_code = EXCLUDED.ana_municipality_code,
                              map_lat = EXCLUDED.map_lat,
                              map_lng = EXCLUDED.map_lng,
                              theme_color = EXCLUDED.theme_color
                              RETURNING id INTO v_city_id;

-- Cria automaticamente o Coordenador do município (Senha padrão: 123456)
INSERT INTO coordinators (id, name, email, password, city_id)
VALUES (
           gen_random_uuid(),
           'Coordenador ' || trim(p_name),
           'coordenador@' || v_clean_slug || '.sc.gov.br',
           '$2a$10$trT3.R/Nfey62eczbKEnueTcIbJXW.u1ffAo/XfyLpofwNDbEB86O',
           v_city_id
       )
    ON CONFLICT (email) DO NOTHING;

RETURN v_city_id;
END;
$$ LANGUAGE plpgsql;