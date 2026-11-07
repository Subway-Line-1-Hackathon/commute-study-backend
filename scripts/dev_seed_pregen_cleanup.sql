-- dev_seed_pregen.sql로 넣은 데이터를 제거한다 (이슈 #24).
-- app_user 행은 실제 계정이므로 지우지 않는다. subject '자료구조 (사전 생성 시드)'와 그 하위 데이터만 지운다.
--
-- 사용법: v_user_id를 dev_seed_pregen.sql 실행 때 쓴 UUID와 같게 맞추고 전체를 실행한다.

do $$
declare
    v_user_id uuid := '<YOUR_SUPABASE_AUTH_UUID>'; -- TODO: dev_seed_pregen.sql과 동일한 UUID로 교체
    v_subject_id bigint;
begin
    select id into v_subject_id from public.subject where user_id = v_user_id and name = '자료구조 (사전 생성 시드)';
    if v_subject_id is null then
        raise notice '삭제할 시드 데이터가 없습니다 (subject를 찾지 못함)';
        return;
    end if;

    -- attempt.question_id에는 on delete cascade가 없어 생성된 문제로 푼 기록이 있으면 먼저 지운다.
    delete from public.attempt
    where question_id in (
        select q.id from public.question q
        join public.category c on c.id = q.category_id
        where c.subject_id = v_subject_id
    );

    -- subject 삭제가 material → category → question, material_page로 연쇄 삭제된다.
    delete from public.subject where id = v_subject_id;

    raise notice '사전 생성 시드 데이터 삭제 완료 (subject_id=%)', v_subject_id;
end $$;
