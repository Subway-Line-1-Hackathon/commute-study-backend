-- 기존 비 NULL 단원 세션의 실제 출처는 복원할 수 없으므로 합의된 categories 기본값을 사용한다.
-- 배포 시 구버전의 세션 생성 요청을 중지하고 마이그레이션 후 새 버전으로 전환한다.
alter table public.session add column source text;

update public.session
set source = case when category_ids is null then 'wrongNotes' else 'categories' end;

alter table public.session alter column source set not null;

alter table public.session
    add constraint session_source_check check (source in ('categories', 'wrongNotes'));
