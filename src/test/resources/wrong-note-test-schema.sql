-- Isolated fixture of the existing V1 contract, not a production migration.
CREATE TABLE wrong_note (
 user_id UUID NOT NULL REFERENCES app_user(id), question_id BIGINT NOT NULL REFERENCES question(id),
 wrong_count INT NOT NULL DEFAULT 1, status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
 last_wrong_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(user_id,question_id));
