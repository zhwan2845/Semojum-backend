-- proto 협의판(2026-11-01)의 새 칸을 저장한다 — 앱이 쪽 조회 · SSE로 받는다
-- review_flag: 줄 번호 칸 신호 "open"(경고) · "sug"(고침 제안) · "note"(메모). 없으면 null
-- table_source · table_box_level: 표 요소의 칸 글(<!표><!행><!칸>)과 글상자 단계. proto에 이미 있었지만 저장하지 않던 칸
-- axis: 규정 좌표(col_start · col_end)를 센 글. 이 응답은 늘 "print"(묵자 글자). 예전 행은 null(= print)
ALTER TABLE text_elements ADD COLUMN review_flag VARCHAR(10);
ALTER TABLE text_elements ADD COLUMN table_source TEXT;
ALTER TABLE text_elements ADD COLUMN table_box_level INTEGER;
ALTER TABLE rule_trails ADD COLUMN axis VARCHAR(10);
