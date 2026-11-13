-- Apply after R7 V8 and R8 V9. Preserve subjects and all historical foreign keys.
alter table public.subject add column deleted_at timestamp with time zone;
