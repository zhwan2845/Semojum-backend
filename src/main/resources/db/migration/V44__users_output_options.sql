-- 출력용 점자문서 설정 — 계정 기본값 (2026-10-27, 기획 2-1 「설정 저장(기본값으로 저장)」).
-- 용지 · 시작 번호 · 번호 출력 면 · 양면/단면 · 줄바꿈 방식을 한 칸(jsonb)에 둔다. 비어 있으면 공장 기본값
ALTER TABLE users ADD COLUMN IF NOT EXISTS output_options jsonb;
