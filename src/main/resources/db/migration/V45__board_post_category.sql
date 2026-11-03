-- 게시글 분류(계정 · 발급 / 점역 결과 / 크레딧 · 결제 / 사용법) — 피그마 게시판 글쓰기 「분류」
-- 이미 쓴 글은 분류가 없어 null로 둔다(지어내지 않는다). 새 글은 서버가 늘 채운다(안 보내면 RESULT)
ALTER TABLE board_posts ADD COLUMN category VARCHAR(20);
