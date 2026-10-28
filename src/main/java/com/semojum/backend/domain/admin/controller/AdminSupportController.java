package com.semojum.backend.domain.admin.controller;

import com.semojum.backend.domain.admin.service.AdminAccessGuard;
import com.semojum.backend.domain.support.dto.SupportDto;
import com.semojum.backend.domain.support.service.AdminSupportService;
import com.semojum.backend.global.exception.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;
import org.springframework.web.multipart.MultipartFile;

/**
 * 운영자 API — 공지 · 문의 · 주문.
 *
 * <p>JWT(ROLE_ADMIN) 전용 — 운영자 콘솔(admin.example.com)이 로그인 후 Bearer로 호출한다.
 * 핸들러는 모두 첫 줄에서 {@link AdminAccessGuard#check()}를 부른다.
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminSupportController {

    private final AdminAccessGuard adminAccessGuard;
    private final AdminSupportService adminSupportService;

    // ── 공지 ──
    @PostMapping("/notices")
    public ApiResponse<SupportDto.NoticeItem> createNotice(
            @RequestBody @Valid SupportDto.CreateNotice request
    ) {
        adminAccessGuard.check();
        return ApiResponse.success(adminSupportService.createNotice(request));
    }

    @GetMapping("/notices")
    public ApiResponse<List<SupportDto.NoticeItem>> listNotices() {
        adminAccessGuard.check();
        return ApiResponse.success(adminSupportService.listNotices());
    }

    /** 공지 수정 — 등록과 같은 항목을 통째로 */
    @PatchMapping("/notices/{noticeId}")
    public ApiResponse<SupportDto.NoticeItem> updateNotice(
            @PathVariable UUID noticeId,
            @RequestBody @Valid SupportDto.CreateNotice request
    ) {
        adminAccessGuard.check();
        return ApiResponse.success(adminSupportService.updateNotice(noticeId, request));
    }

    /** 게시판 고정 · 고정 해제 — 해제하면 게시판 맨 위에서 내려간다 */
    @PatchMapping("/notices/{noticeId}/pinned")
    public ApiResponse<SupportDto.NoticeItem> updateNoticePinned(
            @PathVariable UUID noticeId,
            @RequestBody SupportDto.UpdatePinned request
    ) {
        adminAccessGuard.check();
        return ApiResponse.success(adminSupportService.updateNoticePinned(noticeId, request));
    }

    @DeleteMapping("/notices/{noticeId}")
    public ApiResponse<Void> deleteNotice(@PathVariable UUID noticeId) {
        adminAccessGuard.check();
        adminSupportService.deleteNotice(noticeId);
        return ApiResponse.success(null);
    }
    // ── 문의 ──
    @GetMapping("/inquiries")
    public ApiResponse<SupportDto.InquiryPage> listInquiries(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size
    ) {
        adminAccessGuard.check();
        return ApiResponse.success(adminSupportService.listInquiries(status, type, page, size));
    }

    // 문의 상세 — 본문 전문 + 인라인 이미지(presigned)·파일 첨부
    @GetMapping("/inquiries/{inquiryId}")
    public ApiResponse<SupportDto.InquiryDetail> getInquiry(
            @PathVariable UUID inquiryId
    ) {
        adminAccessGuard.check();
        return ApiResponse.success(adminSupportService.getInquiry(inquiryId));
    }

    @PatchMapping("/inquiries/{inquiryId}/status")
    public ApiResponse<SupportDto.InquiryDetail> updateInquiryStatus(
            @PathVariable UUID inquiryId,
            @RequestBody @Valid SupportDto.UpdateInquiryStatus request
    ) {
        adminAccessGuard.check();
        return ApiResponse.success(adminSupportService.updateInquiryStatus(inquiryId, request.status()));
    }

    // ── 주문·수납 ──
    @PostMapping("/orders")
    public ApiResponse<SupportDto.OrderItem> createOrder(
            @RequestBody @Valid SupportDto.CreateOrder request
    ) {
        adminAccessGuard.check();
        return ApiResponse.success(adminSupportService.createOrder(request));
    }

    @PatchMapping("/orders/{orderId}")
    public ApiResponse<SupportDto.OrderItem> updateOrder(
            @PathVariable UUID orderId,
            @RequestBody @Valid SupportDto.UpdateOrder request
    ) {
        adminAccessGuard.check();
        return ApiResponse.success(adminSupportService.updateOrder(orderId, request));
    }

    @GetMapping("/orders")
    public ApiResponse<List<SupportDto.OrderItem>> listOrders(
            @RequestParam(required = false) UUID organizationId
    ) {
        adminAccessGuard.check();
        return ApiResponse.success(adminSupportService.listOrders(organizationId));
    }

    // 증빙(계산서·전표) 파일 — 업로드(재업로드=교체) / 내려받기 presigned
    @PostMapping("/orders/{orderId}/receipt")
    public ApiResponse<SupportDto.OrderItem> uploadOrderReceipt(
            @PathVariable UUID orderId,
            @RequestParam("file") MultipartFile file
    ) {
        adminAccessGuard.check();
        return ApiResponse.success(adminSupportService.uploadOrderReceipt(orderId, file));
    }

    @GetMapping("/orders/{orderId}/receipt")
    public ApiResponse<SupportDto.ReceiptDownload> getOrderReceipt(
            @PathVariable UUID orderId
    ) {
        adminAccessGuard.check();
        return ApiResponse.success(adminSupportService.getOrderReceipt(orderId));
    }
}
