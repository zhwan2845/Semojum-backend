-- 공지 분류 — 피그마 공지사항 창의 탭(전체 / 점검 / 새 판 / 자료)
-- MAINTENANCE(점검) · RELEASE(새 판) · MATERIAL(자료). 이미 쓴 공지와 분류를 고르지 않은 공지는 null(「전체」 탭에만 보인다)
ALTER TABLE notices ADD COLUMN category VARCHAR(20);
