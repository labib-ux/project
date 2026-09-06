# Runbook: ward boundary import

## GeoJSON format requirements

`POST /api/admin/wards/geojson` (ADMIN) accepts:

```json
{
  "municipalityId": 1,
  "wardNumber": 6,
  "areaName": "Mirpur 11",
  "geojson": {
    "type": "Polygon",
    "coordinates": [[[90.36, 23.72], [90.44, 23.72], [90.44, 23.75], [90.36, 23.75], [90.36, 23.72]]]
  }
}
```

- `type` is `Polygon` (first ring used) or `MultiPolygon`.
- Positions are `[lng, lat]` in WGS84 (SRID 4326); rings need ≥ 4 positions
  and are auto-closed when the first/last differ.
- The ward number must be positive and unique per municipality.

## How the R__ migration works

`R__ward_boundaries.geojson.sql` is repeatable: it re-runs on every migrate.
It aligns wards 1–3 of `dhaka-north` to the shared grid (UPDATE only) and
inserts wards 4–5 when absent — every statement guarded on the municipality
existing, so on a fresh database (migration runs before the seeder) it is a
deliberate no-op. Edit the embedded WKT/GeoJSON comment together; checksums
re-run it automatically.

## Overlap validation

Touching edges are intended adjacency, not errors. Validation semantics:

```sql
-- What the validator checks (true area overlaps only):
SELECT a.id, b.id FROM wards a JOIN wards b
  ON b.municipality_id = :mid AND b.is_active AND a.id < b.id
  AND ST_Overlaps(a.boundary, b.boundary)
WHERE a.municipality_id = :mid AND a.is_active;
```

- Admin UI/API: `GET /api/municipalities/{slug}/boundary-validation` returns
  `{valid, overlaps: [{wardA, wardB}]}`; the GeoJSON import endpoint refuses
  (400) and rolls back any ward that would introduce an overlap.
- Tests: `WardBoundaryIntegrationTests` pins adjacency vs overlap behavior.

## Rollback procedure

Boundaries are data, not schema — there is no down-migration. To revert a bad
import: `DELETE FROM wards WHERE id = :id` (only when no complaints reference
it), or re-import the previous polygon for the same ward number. Overlapping
imports are already refused at write time, so rollback is only ever for
misplaced-but-valid shapes.
