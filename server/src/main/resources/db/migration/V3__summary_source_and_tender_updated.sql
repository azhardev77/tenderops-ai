-- M6 reads: executive-summary source label + tender updated-at for list views.
-- Runs on H2 (PostgreSQL mode) and PostgreSQL 17; no PG-only features.
ALTER TABLE compliance_runs ADD COLUMN summary_source VARCHAR(24);

ALTER TABLE tenders ADD COLUMN updated_at TIMESTAMP WITH TIME ZONE;
UPDATE tenders SET updated_at = created_at;
ALTER TABLE tenders ALTER COLUMN updated_at SET NOT NULL;
