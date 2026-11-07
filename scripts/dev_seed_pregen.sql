-- 문제 사전 생성(이슈 #24) 시험용 시드 데이터
-- Flyway 마이그레이션이 아니다. 필요할 때 Supabase 대시보드 > SQL Editor에서 수동으로 실행한다.
--
-- dev_seed.sql과 다른 점:
--   - material_page(쪽별 원문)가 들어 있다. 사전 생성은 이 텍스트를 AI-02에 보낸다.
--   - 단원에 문제가 하나도 없고 question_status가 PREPARING이다.
--     서버의 재시도 스케줄러가 "READY 자료의 PREPARING 단원"을 찾아 문제를 만든다.
--     (#23 자료 업로드가 머지되기 전에는 이 방식으로 이벤트 없이 사전 생성을 시험한다)
--
-- 사전 조건: dev_seed.sql과 같다. 본인 구글 계정으로 한 번 로그인해 auth.users에 UUID가 있어야 한다.
-- 사용법: v_user_id를 본인 UUID로 바꾸고 전체를 실행한다. 다시 실행해도 안전하다.
-- 삭제는 dev_seed_pregen_cleanup.sql 참고.

do $$
declare
    v_user_id uuid := '<YOUR_SUPABASE_AUTH_UUID>'; -- TODO: 본인 UUID로 교체
    v_subject_id bigint;
    v_material_id bigint;
begin
    insert into public.app_user (id, nickname)
    select v_user_id, '개발 시드 사용자'
    where not exists (select 1 from public.app_user where id = v_user_id);

    select id into v_subject_id from public.subject where user_id = v_user_id and name = '자료구조 (사전 생성 시드)';
    if v_subject_id is null then
        insert into public.subject (user_id, name, exam_date)
        values (v_user_id, '자료구조 (사전 생성 시드)', null)
        returning id into v_subject_id;
    end if;

    select id into v_material_id from public.material where subject_id = v_subject_id and file_url = 'seed://dev/pregen.pdf';
    if v_material_id is null then
        insert into public.material (subject_id, file_url, file_name, file_type, kind, size, page_count, status)
        values (v_subject_id, 'seed://dev/pregen.pdf', 'pregen.pdf', 'PDF', 'LECTURE', 1048576, 6, 'READY')
        returning id into v_material_id;
    end if;

    insert into public.material_page (material_id, page, text) values
        (v_material_id, 1, '스택은 나중에 넣은 데이터가 먼저 나오는 LIFO(Last In First Out) 구조다. 삽입을 push, 삭제를 pop이라고 한다.'),
        (v_material_id, 2, '큐는 먼저 넣은 데이터가 먼저 나오는 FIFO(First In First Out) 구조다. 삽입을 enqueue, 삭제를 dequeue라고 한다.'),
        (v_material_id, 3, '스택은 함수 호출 기록과 되돌리기 기능에, 큐는 프린터 대기열과 너비 우선 탐색(BFS)에 쓰인다.'),
        (v_material_id, 4, '트리는 노드와 간선으로 이루어진 계층 구조다. 맨 위 노드를 루트, 자식이 없는 노드를 리프라고 한다.'),
        (v_material_id, 5, '이진 트리는 각 노드의 자식이 최대 2개인 트리다. 이진 탐색 트리는 왼쪽 자식이 부모보다 작고 오른쪽 자식이 부모보다 크다.'),
        (v_material_id, 6, '트리 순회에는 전위(루트-왼쪽-오른쪽), 중위(왼쪽-루트-오른쪽), 후위(왼쪽-오른쪽-루트) 순회가 있다.')
    on conflict (material_id, page) do nothing;

    insert into public.category (subject_id, material_id, name, page_start, page_end, summary, "order", question_status)
    select v_subject_id, v_material_id, '1단원 스택과 큐', 1, 3, E'- 스택은 LIFO, 큐는 FIFO\n- 스택은 함수 호출, 큐는 BFS에 쓰인다', 1, 'PREPARING'
    where not exists (select 1 from public.category where material_id = v_material_id and name = '1단원 스택과 큐');

    insert into public.category (subject_id, material_id, name, page_start, page_end, summary, "order", question_status)
    select v_subject_id, v_material_id, '2단원 트리', 4, 6, E'- 트리는 계층 구조\n- 이진 탐색 트리와 세 가지 순회', 2, 'PREPARING'
    where not exists (select 1 from public.category where material_id = v_material_id and name = '2단원 트리');
end $$;
