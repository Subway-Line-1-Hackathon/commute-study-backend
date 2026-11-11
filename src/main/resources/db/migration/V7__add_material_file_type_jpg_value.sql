-- material_file_type 열거형에 JPG 값을 추가한다 (#66, 폰 사진 업로드).
-- AI 서버 AI-01의 fileType도 PDF/PPT/PNG/JPG를 받는다(commute-study-ai PR #45).
-- PostgreSQL은 ALTER TYPE ... ADD VALUE로 추가한 값을 같은 트랜잭션 안에서
-- 바로 사용할 수 없으므로, V3와 같이 이 파일 하나만 둔다.
-- 값만 추가하므로 기존 행과 구버전 서버에는 영향이 없다(배포 순서 무관).
alter type material_file_type add value 'JPG';
