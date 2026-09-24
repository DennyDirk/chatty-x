CREATE TABLE memory_job (
    conversation_id uuid PRIMARY KEY REFERENCES conversation(id) ON DELETE CASCADE,
    revision bigint NOT NULL DEFAULT 0,
    due_at timestamptz NOT NULL DEFAULT now(),
    attempts integer NOT NULL DEFAULT 0,
    error_category text
);
