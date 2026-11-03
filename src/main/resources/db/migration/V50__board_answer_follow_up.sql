-- 글쓴이가 같은 글에 이어 묻기(피그마 마이페이지 문의 내역 「회신...」 · 보내기)
-- 운영자 답변과 한 줄로 이어지게 board_answers에 둔다. from_author = true면 글쓴이가 이어 쓴 글
-- 이미 있는 행은 전부 운영자 답변이다(false)
ALTER TABLE board_answers ADD COLUMN from_author BOOLEAN NOT NULL DEFAULT FALSE;
