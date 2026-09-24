CREATE TABLE delivery_receipt (
    connection_id uuid NOT NULL REFERENCES connection(id) ON DELETE CASCADE,
    chat_id text NOT NULL,
    temporary_id text NOT NULL,
    message_id text NOT NULL,
    success boolean NOT NULL,
    category text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(connection_id,chat_id,temporary_id)
);
