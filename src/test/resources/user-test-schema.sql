-- Isolated H2 PostgreSQL-mode fixture for user API tests, not a Flyway migration.
-- Supabase auth.users and its FK are verified separately against PostgreSQL.
CREATE TABLE public.app_user (
    id UUID PRIMARY KEY,
    nickname VARCHAR(50),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE public.commute_profile (
    user_id UUID PRIMARY KEY REFERENCES public.app_user(id),
    one_way_min INT NOT NULL,
    from_station VARCHAR(50),
    to_station VARCHAR(50)
);
CREATE TABLE public.subject (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES public.app_user(id),
    name VARCHAR(100) NOT NULL
);
