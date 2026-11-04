-- 백엔드 개발 명세서 6장, AI 서버 ↔ 백엔드 API 명세 11장 기준 V2 변경.
-- V1__init_schema.sql은 수정하지 않는다(체크섬 불일치로 기동 실패).
-- PNG 여러 장 저장 구조(합의 #3, material_file 신규 여부)는 AI 담당과 결정 전이라
-- 이번 파일에 포함하지 않는다. 결정되면 V4 이후 파일로 추가한다.
-- material_status에 'EXPIRED' 값을 추가하는 것은 같은 트랜잭션에서 그 값을
-- 바로 쓸 수 없어 V3__add_material_status_expired_value.sql로 분리했다.

-- 1. app_user: 로그인 직후 "닉네임 없는 자동 생성"을 허용 (4장)
alter table public.app_user
    alter column nickname drop not null;

-- 2. commute_profile: 최초 설정에서 역 이름 입력을 선택값으로 바꿈 (7장 PUT /users/me/settings)
alter table public.commute_profile
    alter column from_station drop not null,
    alter column to_station drop not null;

-- 3. session: source=wrong_notes(오답노트 다시 풀기)는 단원을 안 넘길 수 있음 (7장 POST /sessions)
alter table public.session
    alter column category_ids drop not null;

-- 4. material: AI-01 요청의 fileName, 자료 보관 만료일(7일 후 EXPIRED)
alter table public.material
    add column file_name text,
    add column expires_at timestamptz;

-- 5. category: 단원당 40문제 사전 생성 상태 (AI-02, 9.4장)
create type category_question_status as enum ('PREPARING', 'AVAILABLE');

alter table public.category
    add column question_status category_question_status not null default 'PREPARING';

-- 6. question: AI-02가 주는 개념 태그(AI-06 입력으로 재사용), 생성 출처(사전/추가)
create type question_origin as enum ('PREGEN', 'EXTRA');

alter table public.question
    add column concept_tags jsonb not null default '[]'::jsonb,
    add column origin question_origin not null default 'PREGEN';

-- 7. session: 통학 후 피드백(AI-06), 세션당 1회만 호출해 저장 (9.9장)
alter table public.session
    add column feedback text;

-- 8. attempt: 단답 자가 판정 여부(LN-03), 일괄 전송 중복 저장 방지 (9.5, 9.7장)
alter table public.attempt
    add column self_judged boolean not null default false;

alter table public.attempt
    add constraint attempt_session_question_uk unique (session_id, question_id);

-- 9. session: 사용자당 IN_PROGRESS 세션 1개만 허용 (동시 요청 방지, 9.5장)
create unique index session_user_in_progress_uk
    on public.session (user_id)
    where status = 'IN_PROGRESS';

-- 10. material_page: AI-01이 돌려준 쪽 텍스트. AI-02·AI-05 호출 입력으로 다시 쓴다 (8, 9.4장)
create table public.material_page (
    material_id bigint not null references public.material (id) on delete cascade,
    page int not null,
    text text not null,
    primary key (material_id, page)
);
