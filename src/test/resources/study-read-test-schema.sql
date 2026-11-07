-- Isolated H2 fixture for the V1/V2 attempt/session contract; never used by production.
CREATE TABLE session (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 user_id UUID NOT NULL REFERENCES app_user(id), mode VARCHAR(20) NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'READY');
CREATE TABLE session_question (
 session_id BIGINT REFERENCES session(id), question_id BIGINT REFERENCES question(id),
 "order" INT NOT NULL, PRIMARY KEY(session_id,question_id));
CREATE TABLE attempt (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 user_id UUID NOT NULL REFERENCES app_user(id), question_id BIGINT NOT NULL REFERENCES question(id),
 session_id BIGINT NOT NULL REFERENCES session(id), answer TEXT, correct BOOLEAN, score NUMERIC,
 answered_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 self_judged BOOLEAN NOT NULL DEFAULT FALSE, UNIQUE(session_id,question_id));
