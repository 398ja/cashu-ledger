-- SQL preload generated from JSON mint data
-- Mint: 1f240ace-0e4e-42dd-bdcb-9ad4ce8eaeae
-- Keyset: 00e3372e61d05605

-- Create revinfo_seq sequence for Hibernate Envers (required for proof audit logging)
-- This sequence is used by the revinfo table for revision tracking
CREATE SEQUENCE IF NOT EXISTS revinfo_seq START WITH 1 INCREMENT BY 50;

-- Truncate only on first run (check if mint already exists)
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM t_mint WHERE id = '1f240ace-0e4e-42dd-bdcb-9ad4ce8eaeae'::uuid) THEN
        -- Only truncate if the mint doesn't exist (fresh database)
        IF to_regclass('t_key') IS NOT NULL THEN TRUNCATE TABLE t_key RESTART IDENTITY CASCADE; END IF;
        IF to_regclass('t_keyset') IS NOT NULL THEN TRUNCATE TABLE t_keyset RESTART IDENTITY CASCADE; END IF;
        IF to_regclass('t_proof') IS NOT NULL THEN TRUNCATE TABLE t_proof RESTART IDENTITY CASCADE; END IF;
        IF to_regclass('t_mint') IS NOT NULL THEN TRUNCATE TABLE t_mint RESTART IDENTITY CASCADE; END IF;
        IF to_regclass('t_key_a') IS NOT NULL THEN TRUNCATE TABLE t_key_a RESTART IDENTITY CASCADE; END IF;
        IF to_regclass('t_keyset_a') IS NOT NULL THEN TRUNCATE TABLE t_keyset_a RESTART IDENTITY CASCADE; END IF;
        IF to_regclass('t_proof_a') IS NOT NULL THEN TRUNCATE TABLE t_proof_a RESTART IDENTITY CASCADE; END IF;
        IF to_regclass('t_mint_a') IS NOT NULL THEN TRUNCATE TABLE t_mint_a RESTART IDENTITY CASCADE; END IF;
        IF to_regclass('revinfo') IS NOT NULL THEN TRUNCATE TABLE revinfo RESTART IDENTITY CASCADE; END IF;
    END IF;
END $$;

DO $$ BEGIN IF to_regclass('t_proof') IS NOT NULL THEN ALTER TABLE t_proof ADD COLUMN IF NOT EXISTS unblinded_signature VARCHAR(255); END IF; END $$;

DO $$ BEGIN IF to_regclass('t_mint') IS NOT NULL AND to_regclass('t_keyset') IS NOT NULL AND to_regclass('t_key') IS NOT NULL THEN
    INSERT INTO t_mint AS target (id, archived, created_at, updated_at, version)
    VALUES ('1f240ace-0e4e-42dd-bdcb-9ad4ce8eaeae'::uuid, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
    ON CONFLICT (id) DO UPDATE
    SET archived = EXCLUDED.archived,
        updated_at = EXCLUDED.updated_at,
        version = EXCLUDED.version;

    INSERT INTO t_keyset AS target (id, archived, created_at, updated_at, version, key_set_id, unit, mint_id)
    VALUES ('a086d577-d938-307b-a14d-e729caaeddf2'::uuid, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, '00e3372e61d05605', 'sat', '1f240ace-0e4e-42dd-bdcb-9ad4ce8eaeae'::uuid)
    ON CONFLICT (id) DO UPDATE
    SET archived = EXCLUDED.archived,
        updated_at = EXCLUDED.updated_at,
        version = EXCLUDED.version,
        key_set_id = EXCLUDED.key_set_id,
        unit = EXCLUDED.unit,
        mint_id = EXCLUDED.mint_id;

    INSERT INTO t_key AS target (id, archived, created_at, updated_at, version, amount, private_key, key_set_id)
    VALUES
        ('a0f1e55f-5d4b-351f-98f9-77677c3f52ce'::uuid, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 1, '4fbf609f4d521cf57b93bba3c530d7be1a1e5da7186c2d33990f0a4ead82ccf4', 'a086d577-d938-307b-a14d-e729caaeddf2'::uuid),
        ('65b31ecd-6830-3329-9132-ae895dbe0928'::uuid, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 2, '34087457fc5668d84c4adb282b6d6504aee920444a76ad802a496f0470b63bd4', 'a086d577-d938-307b-a14d-e729caaeddf2'::uuid),
        ('8d186843-76e7-301e-b704-ae3ff1fae3e3'::uuid, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 4, '96764f964f8e8f606a104fb107f4bb1566004babb1b1902379a97de8d5f726cc', 'a086d577-d938-307b-a14d-e729caaeddf2'::uuid),
        ('8535f2eb-f83e-3280-b664-9d52f64f9b37'::uuid, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 8, '0810f116d6b98fac26fd4529464d234457804e110a398dd69845ca6b68898142', 'a086d577-d938-307b-a14d-e729caaeddf2'::uuid),
        ('dd8f0238-d817-3404-a48d-3177545a32db'::uuid, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 16, '1c5b94ffb5f3f434ef4f7820348b8b2e9f21db8c362daa5b094c8975f9a611b2', 'a086d577-d938-307b-a14d-e729caaeddf2'::uuid),
        ('ed792a7d-6016-31e9-86d9-677abaebb65c'::uuid, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 32, 'c7ed093f604d897091b3b6e34f3005e27e8e8b7e984757153426c1c244e74324', 'a086d577-d938-307b-a14d-e729caaeddf2'::uuid),
        ('56f3bb2f-a11d-367b-b204-d4dfb00f46ac'::uuid, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 64, '7f92c62917f281775e2b27d7121cbf135c0583e33e6594d8798325919909bca6', 'a086d577-d938-307b-a14d-e729caaeddf2'::uuid),
        ('35dc585c-1d8b-3d45-932d-cb6574bc6978'::uuid, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 128, 'f242e9ec2a6854a626cdfbeefe3ceddaf31f498376f90284188645b8c44a8dd6', 'a086d577-d938-307b-a14d-e729caaeddf2'::uuid),
        ('a3d5a1e2-1c2b-4f0a-9a11-d8f9b7a6c5e4'::uuid, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 256, '111122223333444455556666777788889999aaaabbbbccccddddeeeeffff0000', 'a086d577-d938-307b-a14d-e729caaeddf2'::uuid),
        ('b4e6c2d3-2d3e-5a1b-8b22-e7f0c9d8e7f0'::uuid, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 512, '0000fffefdfcfbfaf9f8f7f6f5f4f3f2f1f0efeeedecebeae9e8e7e6e5e4e3e2', 'a086d577-d938-307b-a14d-e729caaeddf2'::uuid),
        ('c5f7d3e4-3e4f-6b2c-7c33-f6e1d0cfe6e1'::uuid, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 1024, 'abcdef0123456789fedcba9876543210abcdef0123456789fedcba9876543210', 'a086d577-d938-307b-a14d-e729caaeddf2'::uuid)
    ON CONFLICT (id) DO UPDATE
    SET archived = EXCLUDED.archived,
        updated_at = EXCLUDED.updated_at,
        version = EXCLUDED.version,
        amount = EXCLUDED.amount,
        private_key = EXCLUDED.private_key,
        key_set_id = EXCLUDED.key_set_id;

END IF; END $$;
