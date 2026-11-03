-- 공개 문의하기 창(피그마) — 제목 · 기관명(선택)을 받는다
-- 제목은 subject에 둔다(메일 문의의 제목과 같은 자리). 그동안 subject에 넣던 보낸 사람 이름은 sender_name으로 옮겨 받는다
-- 이미 받은 홈페이지 문의의 subject(이름)는 그대로 둔다 — 옮기면 운영자가 본 기록이 바뀐다
ALTER TABLE inquiries ADD COLUMN sender_name VARCHAR(50);
ALTER TABLE inquiries ADD COLUMN sender_org_name VARCHAR(100);
