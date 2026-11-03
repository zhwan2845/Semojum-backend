package com.semojum.backend.domain.support.entity;

/**
 * 기관 관리자 요청 창의 칸 — {@code inquiries.request_details}(jsonb).
 *
 * <p>계정 발급은 앞의 세 칸, 크레딧 추가는 뒤의 두 칸만 쓴다. 모두 선택 — 칸을 모르는 구 앱은 메시지만 보낸다.
 *
 * @param accountAlias   별칭(예: 국어 담당)
 * @param recipientEmail 첫 비밀번호를 받을 메일
 * @param creditScope    쓸 수 있는 크레딧 — {@code SHARED}(기관 할당 공유) · {@code NONE}(크레딧 없음, 읽기만)
 * @param creditAmount   요청할 크레딧 양
 * @param invoiceType    세금계산서 — {@code ORG}(기관 명의) · {@code PERSONAL}(개인 명의) · {@code NONE}(발행 안 함)
 */
public record RequestDetails(
        String accountAlias,
        String recipientEmail,
        String creditScope,
        Long creditAmount,
        String invoiceType
) {}
