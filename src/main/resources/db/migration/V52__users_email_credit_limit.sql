-- 기관 관리 · 계정 상세(피그마) — 메일과 계정별 크레딧 한도
-- credit_limit: 이 계정이 한 달(KST 달력 월)에 쓸 수 있는 크레딧. null = 제한 없음(기관 할당 공유), 0 = 크레딧 없음(읽기만)
-- 기관 잔여와 함께 본다 — 둘 중 먼저 바닥나는 쪽에서 변환이 멈춘다
ALTER TABLE users ADD COLUMN email VARCHAR(100);
ALTER TABLE users ADD COLUMN credit_limit BIGINT;
