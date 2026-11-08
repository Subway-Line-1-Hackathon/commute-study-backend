CREATE TABLE ai_question_usage (
 user_id UUID NOT NULL REFERENCES app_user(id), date DATE NOT NULL, count INT NOT NULL DEFAULT 0,
 PRIMARY KEY(user_id,date));
