ALTER TABLE conversation ADD COLUMN import_run_id uuid;
ALTER TABLE conversation ADD COLUMN import_started_at timestamptz;
