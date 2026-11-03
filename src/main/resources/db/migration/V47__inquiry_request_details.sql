-- 기관 관리자 요청 창(피그마)의 칸 — 계정 발급: 별칭 · 받을 사람 메일 · 쓸 수 있는 크레딧 / 크레딧 추가: 요청할 양 · 세금계산서
-- 요청 종류마다 칸이 달라 jsonb 한 칸에 둔다. 칸이 생기기 전 요청과 그 밖의 문의는 null
ALTER TABLE inquiries ADD COLUMN request_details JSONB;
