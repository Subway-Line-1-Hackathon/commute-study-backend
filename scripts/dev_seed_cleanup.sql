-- dev_seed.sql 로 넣은 개발용 시드 데이터를 제거한다 (이슈 #4).
-- app_user 행은 실제 계정이므로 지우지 않는다. subject '컴퓨터 구조'와 그 하위 데이터만 지운다.
--
-- 사용법:
--   1. 아래 v_user_id 값을 dev_seed.sql 실행 때 쓴 UUID와 동일하게 맞춘다.
--   2. 전체를 Supabase SQL Editor에 붙여넣고 실행한다.

do $$
declare
    v_user_id uuid := '<YOUR_SUPABASE_AUTH_UUID>'; -- TODO: dev_seed.sql과 동일한 UUID로 교체
    v_subject_id bigint;
begin
    select id into v_subject_id from public.subject where user_id = v_user_id and name = '컴퓨터 구조';
    if v_subject_id is null then
        raise notice '삭제할 시드 데이터가 없습니다 (subject를 찾지 못함)';
        return;
    end if;

    delete from public.attempt
    where session_id in (select id from public.session where user_id = v_user_id and started_at = '2026-01-01 08:00:00+09');

    delete from public.session_question
    where session_id in (select id from public.session where user_id = v_user_id and started_at = '2026-01-01 08:00:00+09');

    delete from public.session
    where user_id = v_user_id and started_at = '2026-01-01 08:00:00+09';

    delete from public.question where category_id in (select id from public.category where subject_id = v_subject_id);

    delete from public.category where subject_id = v_subject_id;

    delete from public.material where subject_id = v_subject_id;

    delete from public.subject where id = v_subject_id;

    raise notice '시드 데이터 삭제 완료 (subject_id=%)', v_subject_id;
end $$;
