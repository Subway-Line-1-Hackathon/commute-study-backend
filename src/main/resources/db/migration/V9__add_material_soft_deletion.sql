-- V8 is reserved by R7 (#81); deploy only after V8 has been applied.
alter table public.material add column deleted_at timestamp with time zone;

-- No FK to material: account withdrawal may remove its row before Storage recovers.
create table public.material_delete_object (
    id bigint generated always as identity primary key,
    material_id bigint not null,
    object_ref text not null,
    retry_order_at timestamp with time zone not null default current_timestamp,
    unique (material_id, object_ref)
);
create index material_delete_object_retry_idx on public.material_delete_object (retry_order_at, id);
