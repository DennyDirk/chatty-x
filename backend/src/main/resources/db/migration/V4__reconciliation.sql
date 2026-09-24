ALTER TABLE conversation ADD COLUMN next_sync_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE conversation ADD COLUMN sync_error text;
CREATE INDEX conversation_sync ON conversation(next_sync_at) WHERE selected AND imported;
ALTER TABLE attachment ADD COLUMN cache_released boolean NOT NULL DEFAULT false;
