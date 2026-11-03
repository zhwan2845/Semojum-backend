-- 주문 상세(피그마) — 「요청한 사람 · 요청한 날」과 「견적서 내려받기」
-- request_id: 이 주문을 만든 기관 요청(inquiries, 크레딧 추가). 운영자가 요청 없이 만든 주문 · 예전 주문은 null
-- quote_*: 운영자가 올린 견적서 파일(증빙 receipt_*와 같은 방식, 재업로드 = 교체)
ALTER TABLE orders ADD COLUMN request_id UUID;
ALTER TABLE orders ADD COLUMN quote_file_key VARCHAR(300);
ALTER TABLE orders ADD COLUMN quote_file_name VARCHAR(200);
ALTER TABLE orders ADD COLUMN quote_uploaded_at TIMESTAMPTZ;
