-- Team-agreed PNG batch contract: repeated multipart 'file', preserved request order (1-based).
-- material.file_url remains the first object reference for compatibility; size is the batch total.
create table public.material_file (
    material_id bigint not null references public.material (id) on delete cascade,
    page_order int not null check (page_order between 1 and 20),
    file_url text not null,
    primary key (material_id, page_order)
);

-- Preserve any existing single-PNG rows when introducing the ordered file table.
insert into public.material_file (material_id, page_order, file_url)
select id, 1, file_url from public.material where file_type = 'PNG';
