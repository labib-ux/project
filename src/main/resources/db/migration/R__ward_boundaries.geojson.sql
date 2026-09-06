-- ============================================================================
-- Nagorik Seba — repeatable ward boundaries (Blueprint §3.1, Phase 4)
-- Upserts realistic Dhaka North ward boundaries for wards 1–5 as adjacent
-- non-overlapping polygons covering ~23.72–23.88 N, 90.36–90.44 E.
--
-- Source GeoJSON (five stacked rectangles, WGS84; WKT below mirrors it):
-- {"type":"FeatureCollection","features":[
--  {"type":"Feature","properties":{"ward":1},"geometry":{"type":"Polygon","coordinates":[[[90.36,23.848],[90.44,23.848],[90.44,23.88],[90.36,23.88],[90.36,23.848]]]}},
--  {"type":"Feature","properties":{"ward":2},"geometry":{"type":"Polygon","coordinates":[[[90.36,23.816],[90.44,23.816],[90.44,23.848],[90.36,23.848],[90.36,23.816]]]}},
--  {"type":"Feature","properties":{"ward":3},"geometry":{"type":"Polygon","coordinates":[[[90.36,23.784],[90.44,23.784],[90.44,23.816],[90.36,23.816],[90.36,23.784]]]}},
--  {"type":"Feature","properties":{"ward":4},"geometry":{"type":"Polygon","coordinates":[[[90.36,23.752],[90.44,23.752],[90.44,23.784],[90.36,23.784],[90.36,23.752]]]}},
--  {"type":"Feature","properties":{"ward":5},"geometry":{"type":"Polygon","coordinates":[[[90.36,23.72],[90.44,23.72],[90.44,23.752],[90.36,23.752],[90.36,23.72]]]}}]}
--
-- Every statement is guarded on the municipality existing: on a fresh test
-- database this migration runs before the seeder creates municipalities, so it
-- is a deliberate no-op there and the seeder's polygons rule. On dev/prod it
-- aligns wards 1–3 to the shared grid and adds wards 4–5 when absent.
-- ============================================================================

-- Align existing wards 1–3 to the grid (UPDATE only, never INSERT here)
UPDATE wards w SET boundary = v.boundary, updated_at = now()
FROM (
    VALUES
        (1, ST_GeomFromText('MULTIPOLYGON(((90.36 23.848, 90.44 23.848, 90.44 23.88, 90.36 23.88, 90.36 23.848)))', 4326)),
        (2, ST_GeomFromText('MULTIPOLYGON(((90.36 23.816, 90.44 23.816, 90.44 23.848, 90.36 23.848, 90.36 23.816)))', 4326)),
        (3, ST_GeomFromText('MULTIPOLYGON(((90.36 23.784, 90.44 23.784, 90.44 23.816, 90.36 23.816, 90.36 23.784)))', 4326))
) AS v(ward_number, boundary)
WHERE w.municipality_id = (SELECT id FROM municipalities WHERE slug = 'dhaka-north')
  AND w.ward_number = v.ward_number;

-- Insert wards 4–5 only when dhaka-north exists and the ward is absent
INSERT INTO wards (municipality_id, ward_number, area_name, area_name_bn, boundary, is_active)
SELECT m.id, v.ward_number, v.area_name, v.area_name_bn, v.boundary, true
FROM (SELECT id FROM municipalities WHERE slug = 'dhaka-north') AS m
CROSS JOIN (VALUES
    (4, 'Mirpur 10', 'মিরপুর ১০', ST_GeomFromText('MULTIPOLYGON(((90.36 23.752, 90.44 23.752, 90.44 23.784, 90.36 23.784, 90.36 23.752)))', 4326)),
    (5, 'Pallabi', 'পল্লবী', ST_GeomFromText('MULTIPOLYGON(((90.36 23.72, 90.44 23.72, 90.44 23.752, 90.36 23.752, 90.36 23.72)))', 4326))
) AS v(ward_number, area_name, area_name_bn, boundary)
WHERE NOT EXISTS (
    SELECT 1 FROM wards w WHERE w.municipality_id = m.id AND w.ward_number = v.ward_number
);
