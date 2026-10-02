-- Reset demo data to the seeded baseline.
-- Safe to run: deletes only complaints created AFTER the seed (reference_code > 62),
-- so seeded demo complaints, users, wards and SLA policies are left untouched.
--
-- Usage:
--   docker exec -i nagorik-postgis psql -U nagorik -d nagorik_seba < docs/reset-demo.sql

BEGIN;

-- Children first (attachments reference transitions; everything else references complaints)
DELETE FROM attachments
 WHERE complaint_id IN (SELECT id FROM complaints WHERE reference_code > 'NS-2026-000062');

DELETE FROM complaint_transitions
 WHERE complaint_id IN (SELECT id FROM complaints WHERE reference_code > 'NS-2026-000062');

DELETE FROM complaint_assignments
 WHERE complaint_id IN (SELECT id FROM complaints WHERE reference_code > 'NS-2026-000062');

DELETE FROM resolution_attempts
 WHERE complaint_id IN (SELECT id FROM complaints WHERE reference_code > 'NS-2026-000062');

DELETE FROM sla_instances
 WHERE complaint_id IN (SELECT id FROM complaints WHERE reference_code > 'NS-2026-000062');

DELETE FROM sla_breaches
 WHERE complaint_id IN (SELECT id FROM complaints WHERE reference_code > 'NS-2026-000062');

DELETE FROM status_updates
 WHERE complaint_id IN (SELECT id FROM complaints WHERE reference_code > 'NS-2026-000062');

DELETE FROM complaints WHERE reference_code > 'NS-2026-000062';

COMMIT;

-- Report the restored baseline
SELECT status, count(*) FROM complaints GROUP BY status ORDER BY count(*) DESC;
