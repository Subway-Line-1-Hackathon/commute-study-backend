-- R7: 피드백 실패와 정책상 생략을 feedback=null만으로 추정하지 않는다.
alter table public.session add column feedback_status varchar(16);
alter table public.session add column feedback_status_reason varchar(64);

-- 과거 종료 세션의 실패/생략 사유는 저장되지 않아 복원할 수 없다.
-- 기존 피드백은 보존하고, 피드백 없는 과거 종료 세션은 소급 생성하지 않는
-- 명시적 정책으로 SKIPPED 처리한다. 과거 AI 실패라고 추정하지 않는다.
update public.session
set feedback_status = case
        when feedback is not null and feedback ~ '\S' then 'READY'
        when status in ('COMPLETED', 'ENDED') then 'SKIPPED'
        else 'PENDING'
    end,
    feedback_status_reason = case
        when (feedback is null or feedback !~ '\S')
             and status in ('COMPLETED', 'ENDED') then 'LEGACY_NOT_REGENERATED'
        else null
    end;

-- 공백뿐인 과거 값은 유효한 생성 결과가 아니다.
update public.session set feedback = null
where feedback is not null and feedback !~ '\S';

alter table public.session alter column feedback_status set default 'PENDING';
alter table public.session alter column feedback_status set not null;
alter table public.session add constraint session_feedback_status_check
    check (feedback_status in ('PENDING', 'READY', 'SKIPPED', 'FAILED'));
