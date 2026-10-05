-- Discovery time is not conversation activity. Rebuild only from known message dates.
ALTER TABLE conversation ALTER COLUMN last_activity DROP NOT NULL;
ALTER TABLE conversation ALTER COLUMN last_activity DROP DEFAULT;
UPDATE conversation c SET last_activity = (
  SELECT max(m.sent_at) FROM message m WHERE m.conversation_id=c.id AND NOT m.deleted
);
