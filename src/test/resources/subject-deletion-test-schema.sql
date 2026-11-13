CREATE TABLE commute_profile (
 user_id UUID PRIMARY KEY REFERENCES app_user(id), from_station VARCHAR(50), to_station VARCHAR(50),
 one_way_min INT NOT NULL, departure_time TIME, notify_enabled BOOLEAN NOT NULL DEFAULT FALSE);
ALTER TABLE session ALTER COLUMN source SET DEFAULT 'categories';
