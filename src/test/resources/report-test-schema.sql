-- Only used in the isolated report test database; mirrors existing session columns.
ALTER TABLE session ADD COLUMN started_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE session ADD COLUMN ended_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE session ADD COLUMN feedback TEXT;
ALTER TABLE session ADD COLUMN category_ids BIGINT ARRAY;
