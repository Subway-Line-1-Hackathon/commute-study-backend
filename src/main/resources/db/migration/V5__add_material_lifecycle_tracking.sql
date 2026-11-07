-- Do not apply to the shared DB during development. Deploy with old workers stopped.
alter table public.material add column processing_started_at timestamp with time zone;
alter table public.material add column processing_token uuid;
alter table public.material add column cleanup_pending boolean not null default false;

-- Legacy running jobs get a fresh grace period; upload time is not processing start time.
update public.material set processing_started_at = current_timestamp where status = 'PROCESSING';
create index material_processing_started_idx on public.material (status, processing_started_at);
