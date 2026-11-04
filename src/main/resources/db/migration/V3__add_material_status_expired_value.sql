-- material_status 열거형에 EXPIRED 값을 추가한다 (9.11장, 자료 만료).
-- PostgreSQL은 ALTER TYPE ... ADD VALUE로 추가한 값을 같은 트랜잭션 안에서
-- 바로 사용할 수 없으므로, 다른 변경이 섞인 V2와 분리해 이 파일 하나만 둔다.
alter type material_status add value 'EXPIRED';
