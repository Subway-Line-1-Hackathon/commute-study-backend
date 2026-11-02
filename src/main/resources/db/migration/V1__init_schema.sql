-- 백엔드 개발 명세서 6장(데이터 모델) 기준 초기 스키마.
-- (제안) 표시된 부분은 이슈 #3에서 팀 합의 후 필요하면 V2 이후 마이그레이션으로 수정한다.
-- 이미 적용된 이 파일은 이후 수정하지 않는다 (Flyway 체크섬 불일치로 기동 실패).

-- user: Postgres 예약어라 app_user로 명명(제안), Supabase Auth(auth.users)와 1:1
create table public.app_user (
    id uuid primary key references auth.users (id) on delete cascade,
    nickname varchar(50) not null,
    created_at timestamptz not null default now()
);

create table public.commute_profile (
    user_id uuid primary key references public.app_user (id) on delete cascade,
    from_station varchar(50) not null,
    to_station varchar(50) not null,
    one_way_min int not null,
    departure_time time, -- P1
    notify_enabled boolean not null default false -- P1
);

create table public.subject (
    id bigint generated always as identity primary key,
    user_id uuid not null references public.app_user (id) on delete cascade,
    name varchar(100) not null,
    exam_date date,
    created_at timestamptz not null default now()
);

create type material_file_type as enum ('PDF', 'PPT', 'PNG');
create type material_kind as enum ('LECTURE', 'PAST_EXAM');
create type material_status as enum ('UPLOADED', 'PROCESSING', 'READY', 'FAILED');

create table public.material (
    id bigint generated always as identity primary key,
    subject_id bigint not null references public.subject (id) on delete cascade,
    file_url text not null,
    file_type material_file_type not null,
    kind material_kind not null,
    size bigint not null,
    page_count int,
    status material_status not null default 'UPLOADED',
    error_message text, -- (제안) 실패 안내용
    created_at timestamptz not null default now()
);

create table public.category (
    id bigint generated always as identity primary key,
    subject_id bigint not null references public.subject (id) on delete cascade,
    material_id bigint not null references public.material (id) on delete cascade, -- (제안)
    name varchar(100) not null,
    page_start int not null,
    page_end int not null,
    summary text,
    "order" int not null
);

create type question_type as enum ('OX', 'MC', 'SHORT', 'ESSAY');

create table public.question (
    id bigint generated always as identity primary key,
    category_id bigint not null references public.category (id) on delete cascade,
    type question_type not null,
    difficulty int,
    stem text not null,
    choices jsonb,
    answer text,
    accepted_answers jsonb,
    rubric text,
    explanation text,
    source_page int
);

create type session_mode as enum ('CURRICULUM', 'REVIEW');
create type session_status as enum ('READY', 'IN_PROGRESS', 'COMPLETED', 'ENDED');

create table public.session (
    id bigint generated always as identity primary key,
    user_id uuid not null references public.app_user (id) on delete cascade,
    category_ids bigint[] not null, -- (제안) 조인 테이블 대안 가능, 합의 필요
    question_types jsonb,
    difficulty int,
    study_min int,
    mode session_mode not null,
    status session_status not null default 'READY',
    started_at timestamptz,
    ended_at timestamptz
);

create table public.session_question ( -- (제안)
    session_id bigint not null references public.session (id) on delete cascade,
    question_id bigint not null references public.question (id) on delete cascade,
    "order" int not null,
    primary key (session_id, question_id)
);

create table public.attempt (
    id bigint generated always as identity primary key,
    user_id uuid not null references public.app_user (id) on delete cascade,
    question_id bigint not null references public.question (id),
    session_id bigint not null references public.session (id),
    answer text,
    correct boolean,
    score numeric,
    answered_at timestamptz not null default now()
);

create type wrong_note_status as enum ('OPEN', 'RESOLVED');

create table public.wrong_note (
    user_id uuid not null references public.app_user (id) on delete cascade,
    question_id bigint not null references public.question (id) on delete cascade,
    wrong_count int not null default 1,
    status wrong_note_status not null default 'OPEN', -- (제안)
    last_wrong_at timestamptz not null default now(),
    primary key (user_id, question_id)
);

create table public.ai_question_usage ( -- P1
    user_id uuid not null references public.app_user (id) on delete cascade,
    date date not null,
    count int not null default 0,
    primary key (user_id, date)
);
