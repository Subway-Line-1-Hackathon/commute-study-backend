-- 개발용 시드 데이터 (이슈 #4)
-- Flyway 마이그레이션이 아니다. 필요할 때 Supabase 대시보드 > SQL Editor에서 수동으로 실행한다.
--
-- 사전 조건:
--   본인 구글 계정으로 한 번 로그인해서 auth.users 에 UUID가 이미 존재해야 한다.
--   (Supabase 대시보드 > Authentication > Users 에서 UUID 확인)
--
-- 사용법:
--   1. 아래 v_user_id 값을 본인 UUID로 바꾼다.
--   2. 전체를 SQL Editor에 붙여넣고 실행한다.
--   3. 다시 실행해도 안전하다 (이미 들어간 데이터는 건너뛴다).
--
-- 팀원도 각자 자기 UUID로 이 파일을 그대로 실행하면, user_id 기준으로 각자 독립된
-- 동일한 샘플 데이터(과목 1 / 단원 3 / 문제 9 / 학습 기록 1건)를 갖게 된다.
-- 삭제는 dev_seed_cleanup.sql 참고.

do $$
declare
    v_user_id uuid := '<YOUR_SUPABASE_AUTH_UUID>'; -- TODO: 본인 UUID로 교체
    v_subject_id bigint;
    v_material_id bigint;
    v_category_id1 bigint;
    v_category_id2 bigint;
    v_category_id3 bigint;
    v_q_ox bigint;
    v_q_mc bigint;
    v_q_short bigint;
    v_session_id bigint;
begin
    -- app_user (auth.users 에 이미 있는 계정이어야 FK를 만족한다)
    insert into public.app_user (id, nickname)
    select v_user_id, '개발 시드 사용자'
    where not exists (select 1 from public.app_user where id = v_user_id);

    -- subject 1개
    select id into v_subject_id from public.subject where user_id = v_user_id and name = '컴퓨터 구조';
    if v_subject_id is null then
        insert into public.subject (user_id, name, exam_date)
        values (v_user_id, '컴퓨터 구조', null)
        returning id into v_subject_id;
    end if;

    -- material (더미, 자료 업로드·단원화 파이프라인이 없을 때의 대체용)
    select id into v_material_id from public.material where subject_id = v_subject_id and file_url = 'seed://dev/sample.pdf';
    if v_material_id is null then
        insert into public.material (subject_id, file_url, file_type, kind, size, page_count, status)
        values (v_subject_id, 'seed://dev/sample.pdf', 'PDF', 'LECTURE', 1048576, 30, 'READY')
        returning id into v_material_id;
    end if;

    -- category 3개
    select id into v_category_id1 from public.category where subject_id = v_subject_id and name = '1단원 CPU 구조';
    if v_category_id1 is null then
        insert into public.category (subject_id, material_id, name, page_start, page_end, summary, "order")
        values (v_subject_id, v_material_id, '1단원 CPU 구조', 1, 10, 'CPU의 구성과 동작 원리 요약', 1)
        returning id into v_category_id1;
    end if;

    select id into v_category_id2 from public.category where subject_id = v_subject_id and name = '2단원 메모리 계층';
    if v_category_id2 is null then
        insert into public.category (subject_id, material_id, name, page_start, page_end, summary, "order")
        values (v_subject_id, v_material_id, '2단원 메모리 계층', 11, 20, '캐시와 메모리 계층 구조 요약', 2)
        returning id into v_category_id2;
    end if;

    select id into v_category_id3 from public.category where subject_id = v_subject_id and name = '3단원 입출력 장치';
    if v_category_id3 is null then
        insert into public.category (subject_id, material_id, name, page_start, page_end, summary, "order")
        values (v_subject_id, v_material_id, '3단원 입출력 장치', 21, 30, '입출력 장치와 인터럽트 요약', 3)
        returning id into v_category_id3;
    end if;

    -- 단원별 O/X·객관식·단답형 문제 (해설·source_page 포함)
    insert into public.question (category_id, type, difficulty, stem, choices, answer, accepted_answers, rubric, explanation, source_page)
    select v_category_id1, 'OX', 1, 'CPU는 산술논리연산장치(ALU)를 포함한다.', null, 'true', null, null, 'ALU는 CPU 내부에서 산술·논리 연산을 수행하는 핵심 구성요소다.', 3
    where not exists (select 1 from public.question where category_id = v_category_id1 and stem = 'CPU는 산술논리연산장치(ALU)를 포함한다.');

    insert into public.question (category_id, type, difficulty, stem, choices, answer, accepted_answers, rubric, explanation, source_page)
    select v_category_id1, 'MC', 2, '다음 중 CPU의 구성 요소가 아닌 것은?', '["ALU", "레지스터", "제어장치", "하드디스크"]'::jsonb, '4', null, null, '하드디스크는 보조기억장치로 CPU 내부 구성요소가 아니다.', 5
    where not exists (select 1 from public.question where category_id = v_category_id1 and stem = '다음 중 CPU의 구성 요소가 아닌 것은?');

    insert into public.question (category_id, type, difficulty, stem, choices, answer, accepted_answers, rubric, explanation, source_page)
    select v_category_id1, 'SHORT', 2, 'CPU가 명령어를 해석하고 실행 순서를 제어하는 장치의 이름은?', null, '제어장치', '["제어장치", "control unit", "CU"]'::jsonb, null, '제어장치(Control Unit)는 명령어 해석과 실행 순서를 관장한다.', 7
    where not exists (select 1 from public.question where category_id = v_category_id1 and stem = 'CPU가 명령어를 해석하고 실행 순서를 제어하는 장치의 이름은?');

    insert into public.question (category_id, type, difficulty, stem, choices, answer, accepted_answers, rubric, explanation, source_page)
    select v_category_id2, 'OX', 1, '캐시 메모리는 주기억장치보다 느리다.', null, 'false', null, null, '캐시는 주기억장치보다 훨씬 빠르지만 용량이 작다.', 13
    where not exists (select 1 from public.question where category_id = v_category_id2 and stem = '캐시 메모리는 주기억장치보다 느리다.');

    insert into public.question (category_id, type, difficulty, stem, choices, answer, accepted_answers, rubric, explanation, source_page)
    select v_category_id2, 'MC', 2, '메모리 계층 구조에서 가장 빠른 저장장치는?', '["레지스터", "캐시", "주기억장치", "보조기억장치"]'::jsonb, '1', null, null, '레지스터는 CPU 내부에 있어 접근 속도가 가장 빠르다.', 15
    where not exists (select 1 from public.question where category_id = v_category_id2 and stem = '메모리 계층 구조에서 가장 빠른 저장장치는?');

    insert into public.question (category_id, type, difficulty, stem, choices, answer, accepted_answers, rubric, explanation, source_page)
    select v_category_id2, 'SHORT', 3, '캐시에 원하는 데이터가 없을 때를 가리키는 용어는?', null, '캐시 미스', '["캐시 미스", "cache miss"]'::jsonb, null, '캐시 미스는 캐시에 요청한 데이터가 없어 하위 메모리까지 접근해야 하는 상황이다.', 18
    where not exists (select 1 from public.question where category_id = v_category_id2 and stem = '캐시에 원하는 데이터가 없을 때를 가리키는 용어는?');

    insert into public.question (category_id, type, difficulty, stem, choices, answer, accepted_answers, rubric, explanation, source_page)
    select v_category_id3, 'OX', 1, '인터럽트는 CPU가 입출력 장치의 요청을 처리하는 방식 중 하나다.', null, 'true', null, null, '인터럽트는 입출력 장치가 CPU에 직접 신호를 보내 처리를 요청하는 방식이다.', 23
    where not exists (select 1 from public.question where category_id = v_category_id3 and stem = '인터럽트는 CPU가 입출력 장치의 요청을 처리하는 방식 중 하나다.');

    insert into public.question (category_id, type, difficulty, stem, choices, answer, accepted_answers, rubric, explanation, source_page)
    select v_category_id3, 'MC', 2, '다음 중 입출력 제어 방식이 아닌 것은?', '["폴링", "인터럽트", "DMA", "캐싱"]'::jsonb, '4', null, null, '캐싱은 메모리 계층 기법으로 입출력 제어 방식이 아니다.', 25
    where not exists (select 1 from public.question where category_id = v_category_id3 and stem = '다음 중 입출력 제어 방식이 아닌 것은?');

    insert into public.question (category_id, type, difficulty, stem, choices, answer, accepted_answers, rubric, explanation, source_page)
    select v_category_id3, 'SHORT', 2, 'CPU를 거치지 않고 메모리와 입출력 장치가 직접 데이터를 주고받는 방식은?', null, 'DMA', '["DMA", "Direct Memory Access"]'::jsonb, null, 'DMA(Direct Memory Access)는 CPU 개입 없이 메모리-입출력 장치 간 직접 전송을 수행한다.', 28
    where not exists (select 1 from public.question where category_id = v_category_id3 and stem = 'CPU를 거치지 않고 메모리와 입출력 장치가 직접 데이터를 주고받는 방식은?');

    -- 학습 기록 (세션 1건 + 응답 3건, 1단원 문제로 구성)
    select id into v_session_id from public.session where user_id = v_user_id and started_at = '2026-01-01 08:00:00+09';
    if v_session_id is null then
        select id into v_q_ox from public.question where category_id = v_category_id1 and type = 'OX' limit 1;
        select id into v_q_mc from public.question where category_id = v_category_id1 and type = 'MC' limit 1;
        select id into v_q_short from public.question where category_id = v_category_id1 and type = 'SHORT' limit 1;

        insert into public.session (user_id, category_ids, question_types, difficulty, study_min, mode, status, source, started_at, ended_at)
        values (v_user_id, array[v_category_id1], '["OX","MC","SHORT"]'::jsonb, 2, 15, 'CURRICULUM', 'COMPLETED', 'categories', '2026-01-01 08:00:00+09', '2026-01-01 08:15:00+09')
        returning id into v_session_id;

        insert into public.session_question (session_id, question_id, "order") values
            (v_session_id, v_q_ox, 1),
            (v_session_id, v_q_mc, 2),
            (v_session_id, v_q_short, 3);

        insert into public.attempt (user_id, question_id, session_id, answer, correct, score, answered_at) values
            (v_user_id, v_q_ox, v_session_id, 'true', true, 1, '2026-01-01 08:05:00+09'),
            (v_user_id, v_q_mc, v_session_id, '4', true, 1, '2026-01-01 08:10:00+09'),
            (v_user_id, v_q_short, v_session_id, '산술논리연산장치', false, 0, '2026-01-01 08:14:00+09');
    end if;
end $$;
