-- ============================================================================
-- Nagorik Seba — V6 demo seed guard (Phase 6)
--
-- File uploads and demo rows live in DataSeeder Java code only — SQL never
-- creates files. This migration therefore carries no INSERTs of its own:
-- it verifies the Phase 1–5 seed baseline exists (idempotent NOTICE when it
-- does not, e.g. a fresh database where the seeder has not run yet) and adds
-- the citizen_ref_hash column the anonymization flow needs.
-- ============================================================================

-- Anonymized reference kept when a user is deleted (§9.4 retention).
ALTER TABLE IF EXISTS complaints
    ADD COLUMN IF NOT EXISTS citizen_ref_hash VARCHAR(64);

-- Baseline verification: warns instead of failing so deploy-time Flyway runs
-- stay green on both seeded and seed-pending databases.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM municipalities WHERE slug = 'dhaka-north') THEN
        RAISE NOTICE 'V6: demo municipality dhaka-north not present yet; DataSeeder will create it on boot';
    ELSE
        RAISE NOTICE 'V6: demo baseline present (municipalities=%, wards=%, complaints=%)',
            (SELECT count(*) FROM municipalities),
            (SELECT count(*) FROM wards),
            (SELECT count(*) FROM complaints);
    END IF;
END $$;
