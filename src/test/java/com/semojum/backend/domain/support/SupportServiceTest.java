package com.semojum.backend.domain.support;

import com.semojum.backend.domain.auth.entity.User;
import com.semojum.backend.domain.auth.enums.Role;
import com.semojum.backend.domain.auth.repository.UserRepository;
import com.semojum.backend.domain.org.entity.Organization;
import com.semojum.backend.domain.org.repository.OrganizationRepository;
import com.semojum.backend.domain.support.dto.SupportDto;
import com.semojum.backend.domain.support.entity.Inquiry;
import com.semojum.backend.domain.support.entity.RequestDetails;
import com.semojum.backend.domain.support.entity.Notice;
import com.semojum.backend.domain.support.entity.Order;
import com.semojum.backend.domain.support.repository.InquiryRepository;
import com.semojum.backend.domain.support.repository.NoticeRepository;
import com.semojum.backend.domain.support.repository.OrderRepository;
import com.semojum.backend.domain.support.service.AdminSupportService;
import com.semojum.backend.domain.support.service.OrgSupportService;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import com.semojum.backend.domain.support.entity.InquiryAttachment;
import com.semojum.backend.domain.support.repository.InquiryAttachmentRepository;
import com.semojum.backend.global.s3.S3Service;
import java.util.List;
import org.springframework.mock.web.MockMultipartFile;

// 문의·공지·주문의 검증 규칙 (저장소 mock)
class SupportServiceTest {

    private UserRepository userRepository;
    private InquiryRepository inquiryRepository;
    private NoticeRepository noticeRepository;
    private OrganizationRepository organizationRepository;
    private OrgSupportService orgService;
    private AdminSupportService adminService;

    private Organization orgA;
    private Organization orgB;
    private User orgAdmin;
    private OrderRepository orderRepository;
    private InquiryAttachmentRepository inquiryAttachmentRepository;
    private S3Service s3Service;

    private static void setId(Object entity, Object value) throws Exception {
        var f = entity.getClass().getDeclaredField("id");
        f.setAccessible(true);
        f.set(entity, value);
    }

    @BeforeEach
    void setUp() throws Exception {
        userRepository = Mockito.mock(UserRepository.class);
        inquiryRepository = Mockito.mock(InquiryRepository.class);
        noticeRepository = Mockito.mock(NoticeRepository.class);
        organizationRepository = Mockito.mock(OrganizationRepository.class);
        OrderRepository orderRepository = Mockito.mock(OrderRepository.class);
        this.orderRepository = orderRepository;
        s3Service = Mockito.mock(S3Service.class);

        orgService = new OrgSupportService(userRepository, noticeRepository, orderRepository, inquiryRepository, s3Service);
        inquiryAttachmentRepository = Mockito.mock(
                InquiryAttachmentRepository.class);
        adminService = new AdminSupportService(noticeRepository, inquiryRepository, inquiryAttachmentRepository,
                orderRepository, organizationRepository, userRepository, s3Service);

        orgA = Organization.builder().name("기관A").code("orga").build();
        setId(orgA, UUID.randomUUID());
        orgB = Organization.builder().name("기관B").code("orgb").build();
        setId(orgB, UUID.randomUUID());

        orgAdmin = User.builder().loginId("orga01").organization(orgA).password("pw").build();
        orgAdmin.changeRole(Role.ROLE_ORG_ADMIN);
        setId(orgAdmin, UUID.randomUUID());
        when(userRepository.findById(orgAdmin.getId())).thenReturn(Optional.of(orgAdmin));
        // save가 넘긴 엔티티를 그대로 반환 (id는 미설정이어도 검증에 무관)
        when(inquiryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(inquiryRepository.findById(any())).thenReturn(Optional.empty());
    }

    @Test
    void 요청_유형은_크레딧_계정발급_오류신고_3종() {
        // ERROR_REPORT도 허용 — 접수 성공
        var item = orgService.createRequest(orgAdmin.getId().toString(),
                new SupportDto.CreateRequest("ERROR_REPORT", "변환이 멈춥니다"));
        assertEquals("ERROR_REPORT", item.type());

        // 그 외 유형은 여전히 거절
        CustomException e = assertThrows(CustomException.class, () ->
                orgService.createRequest(orgAdmin.getId().toString(),
                        new SupportDto.CreateRequest("ONBOARDING", "메시지")));
        assertEquals(ErrorCode.COMMON_BAD_REQUEST, e.getErrorCode());
    }

    /** 피그마 계정 발급 요청 창 — 별칭 · 받을 사람 메일 · 쓸 수 있는 크레딧 */
    @Test
    void 계정_발급_요청은_창의_칸을_저장한다() {
        var item = orgService.createRequest(orgAdmin.getId().toString(), new SupportDto.CreateRequest(
                "ACCOUNT_ISSUE", null, " 국어 담당 ", "name@kblib.or.kr", "SHARED", 3000L, "ORG"));
        assertEquals(new RequestDetails("국어 담당", "name@kblib.or.kr", "SHARED", null, null), item.details());
    }

    /** 피그마 크레딧 추가 요청 창 — 요청할 양 · 세금계산서. 다른 종류의 칸은 버린다 */
    @Test
    void 크레딧_추가_요청은_양과_세금계산서만_저장한다() {
        var item = orgService.createRequest(orgAdmin.getId().toString(), new SupportDto.CreateRequest(
                "CREDIT_ADD", null, "국어 담당", null, null, 3000L, "PERSONAL"));
        assertEquals(new RequestDetails(null, null, null, 3000L, "PERSONAL"), item.details());
    }

    /** 칸을 모르는 구 앱 요청은 칸 없이(null) 저장한다 */
    @Test
    void 칸이_없는_요청은_details가_null이다() {
        assertNull(orgService.createRequest(orgAdmin.getId().toString(),
                new SupportDto.CreateRequest("CREDIT_ADD", "3,000 크레딧 추가 요청드립니다")).details());
    }

    @Test
    void 요청_창의_허용값이_아니면_400이다() {
        for (SupportDto.CreateRequest bad : List.of(
                new SupportDto.CreateRequest("ACCOUNT_ISSUE", null, null, null, "ALL", null, null),
                new SupportDto.CreateRequest("CREDIT_ADD", null, null, null, null, 10L, "COMPANY"))) {
            CustomException e = assertThrows(CustomException.class,
                    () -> orgService.createRequest(orgAdmin.getId().toString(), bad));
            assertEquals(ErrorCode.COMMON_BAD_REQUEST, e.getErrorCode());
        }
    }

    @Test
    void 요청_취소는_자기_기관의_OPEN만() throws Exception {
        // 타 기관 요청 → 403
        Inquiry other = Inquiry.builder().type(Inquiry.TYPE_CREDIT_ADD)
                .organizationId(orgB.getId()).userId(UUID.randomUUID()).message(null).build();
        UUID otherId = UUID.randomUUID();
        setId(other, otherId);
        when(inquiryRepository.findById(otherId)).thenReturn(Optional.of(other));
        CustomException e1 = assertThrows(CustomException.class,
                () -> orgService.cancelRequest(orgAdmin.getId().toString(), otherId));
        assertEquals(ErrorCode.COMMON_FORBIDDEN, e1.getErrorCode());

        // 자기 기관이지만 이미 처리 중 → 400
        Inquiry reviewing = Inquiry.builder().type(Inquiry.TYPE_ACCOUNT_ISSUE)
                .organizationId(orgA.getId()).userId(orgAdmin.getId()).message(null).build();
        reviewing.changeStatus(Inquiry.STATUS_IN_REVIEW);
        UUID reviewingId = UUID.randomUUID();
        setId(reviewing, reviewingId);
        when(inquiryRepository.findById(reviewingId)).thenReturn(Optional.of(reviewing));
        CustomException e2 = assertThrows(CustomException.class,
                () -> orgService.cancelRequest(orgAdmin.getId().toString(), reviewingId));
        assertEquals(ErrorCode.COMMON_BAD_REQUEST, e2.getErrorCode());

        // 자기 기관 + OPEN → 삭제
        Inquiry open = Inquiry.builder().type(Inquiry.TYPE_CREDIT_ADD)
                .organizationId(orgA.getId()).userId(orgAdmin.getId()).message(null).build();
        UUID openId = UUID.randomUUID();
        setId(open, openId);
        when(inquiryRepository.findById(openId)).thenReturn(Optional.of(open));
        orgService.cancelRequest(orgAdmin.getId().toString(), openId);
        verify(inquiryRepository).delete(open);
    }

    @Test
    void 공지_기간_역전은_거절() {
        CustomException e = assertThrows(CustomException.class, () ->
                adminService.createNotice(new SupportDto.CreateNotice(null, "제목", "본문",
                        LocalDate.of(2026, 8, 20), LocalDate.of(2026, 8, 14))));
        assertEquals(ErrorCode.COMMON_BAD_REQUEST, e.getErrorCode());
    }

    @Test
    void 기관_공지는_기관_존재_확인() {
        UUID ghost = UUID.randomUUID();
        when(organizationRepository.findById(ghost)).thenReturn(Optional.empty());
        CustomException e = assertThrows(CustomException.class, () ->
                adminService.createNotice(new SupportDto.CreateNotice(ghost, "제목", "본문",
                        LocalDate.of(2026, 8, 14), LocalDate.of(2026, 8, 20))));
        assertEquals(ErrorCode.ORG_NOT_FOUND, e.getErrorCode());
    }

    /** 공지 수정 — 등록과 같은 항목을 통째로 덮어쓴다 */
    @Test
    void 공지_수정은_항목을_통째로_바꾼다() throws Exception {
        Notice n = Notice.builder().title("옛 제목").body("옛 본문")
                .startsOn(LocalDate.of(2026, 10, 1)).endsOn(LocalDate.of(2026, 10, 31)).build();
        UUID id = UUID.randomUUID();
        setId(n, id);
        when(noticeRepository.findById(id)).thenReturn(Optional.of(n));

        SupportDto.NoticeItem item = adminService.updateNotice(id, new SupportDto.CreateNotice(null, "10월 정기 점검 안내",
                "새 본문", LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 9)));

        assertEquals("10월 정기 점검 안내", item.title());
        assertEquals(LocalDate.of(2026, 10, 9), n.getEndsOn());
    }

    /** 공지사항 창의 탭(점검 · 새 판 · 자료) — 등록한 분류가 공개 · 기관 · 콘솔 응답에 실린다 */
    @Test
    void 공지_분류를_저장하고_응답에_싣는다() {
        when(noticeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        SupportDto.NoticeItem item = adminService.createNotice(new SupportDto.CreateNotice(null, "10/15 서버 점검",
                "본문", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), "MAINTENANCE"));
        assertEquals("MAINTENANCE", item.category());

        Notice n = Notice.builder().title("1.0.0 정식 판").body("본문").category("RELEASE")
                .startsOn(LocalDate.of(2026, 10, 1)).endsOn(LocalDate.of(2026, 12, 31)).build();
        when(noticeRepository.findVisibleForAll(any())).thenReturn(List.of(n));
        assertEquals("RELEASE", orgService.getPublicNotices().get(0).category());
    }

    @Test
    void 모르는_공지_분류는_400이다() {
        CustomException e = assertThrows(CustomException.class, () -> adminService.createNotice(
                new SupportDto.CreateNotice(null, "제목", "본문", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2), "EVENT")));
        assertEquals(ErrorCode.COMMON_BAD_REQUEST, e.getErrorCode());
    }

    /** 분류 칸이 없는 운영자 콘솔이 공지를 고쳐도 분류가 지워지지 않는다 */
    @Test
    void 공지_수정에서_분류를_안_보내면_그대로_둔다() throws Exception {
        Notice n = Notice.builder().title("옛 제목").body("옛 본문").category("MATERIAL")
                .startsOn(LocalDate.of(2026, 10, 1)).endsOn(LocalDate.of(2026, 10, 31)).build();
        UUID id = UUID.randomUUID();
        setId(n, id);
        when(noticeRepository.findById(id)).thenReturn(Optional.of(n));

        adminService.updateNotice(id, new SupportDto.CreateNotice(null, "새 제목", "새 본문",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)));
        assertEquals("MATERIAL", n.getCategory());
    }

    /** 고정 해제 — 게시판 맨 위에서 내려간다. 다시 고정할 수도 있다 */
    @Test
    void 공지_고정을_풀고_다시_건다() throws Exception {
        Notice n = Notice.builder().title("10월 정기 점검 안내").body("본문")
                .startsOn(LocalDate.of(2026, 10, 1)).endsOn(LocalDate.of(2026, 10, 31)).build();
        UUID id = UUID.randomUUID();
        setId(n, id);
        when(noticeRepository.findById(id)).thenReturn(Optional.of(n));
        assertTrue(n.isPinned(), "새 공지는 고정 상태로 시작한다");

        assertFalse(adminService.updateNoticePinned(id, new SupportDto.UpdatePinned(false)).pinned());
        assertFalse(n.isPinned());
        assertTrue(adminService.updateNoticePinned(id, new SupportDto.UpdatePinned(true)).pinned());

        CustomException e = assertThrows(CustomException.class,
                () -> adminService.updateNoticePinned(id, new SupportDto.UpdatePinned(null)));
        assertEquals(ErrorCode.COMMON_BAD_REQUEST, e.getErrorCode());
    }

    @Test
    void 없는_공지는_고치거나_지울_수_없다() {
        UUID ghost = UUID.randomUUID();
        when(noticeRepository.findById(ghost)).thenReturn(Optional.empty());

        CustomException e = assertThrows(CustomException.class, () -> adminService.updateNotice(ghost,
                new SupportDto.CreateNotice(null, "제목", "본문", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2))));
        assertEquals(ErrorCode.BOARD_NOTICE_NOT_FOUND, e.getErrorCode());
        e = assertThrows(CustomException.class, () -> adminService.deleteNotice(ghost));
        assertEquals(ErrorCode.BOARD_NOTICE_NOT_FOUND, e.getErrorCode());
    }

    @Test
    void 문의_상태값_검증() {
        CustomException e = assertThrows(CustomException.class,
                () -> adminService.updateInquiryStatus(UUID.randomUUID(), "CANCELED"));
        assertEquals(ErrorCode.COMMON_BAD_REQUEST, e.getErrorCode());
    }

    @Test
    void 주문_갱신은_변경_항목이_하나는_있어야() {
        CustomException e = assertThrows(CustomException.class,
                () -> adminService.updateOrder(UUID.randomUUID(), new SupportDto.UpdateOrder(null, null)));
        assertEquals(ErrorCode.COMMON_BAD_REQUEST, e.getErrorCode());
    }

    // ── 증빙 파일 ──
    private Order receiptOrder(Organization owner) throws Exception {
        Order order = Order.builder().organizationId(owner.getId()).orderDate(LocalDate.now())
                .description("연간 계약").amountKrw(1000).build();
        setId(order, UUID.randomUUID());
        Mockito.when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        return order;
    }

    @Test
    void 증빙_업로드_교체_후_파일명이_남는다() throws Exception {
        Order order = receiptOrder(orgA);
        Mockito.when(organizationRepository.findById(orgA.getId())).thenReturn(Optional.of(orgA));
        var file = new MockMultipartFile("file", "계산서_2월.pdf",
                "application/pdf", new byte[]{1, 2});
        SupportDto.OrderItem item = adminService.uploadOrderReceipt(order.getId(), file);
        assertEquals("계산서_2월.pdf", item.receiptFileName());
        Mockito.verify(s3Service).deleteByPrefix("receipts/" + order.getId() + "/");
        Mockito.verify(s3Service).uploadFile(Mockito.eq("receipts/" + order.getId() + "/receipt.pdf"),
                Mockito.any(), Mockito.eq("application/pdf"));
    }

    @Test
    void 증빙_허용_외_형식은_거절() throws Exception {
        Order order = receiptOrder(orgA);
        var file = new MockMultipartFile("file", "virus.exe",
                "application/octet-stream", new byte[]{1});
        CustomException e = assertThrows(CustomException.class,
                () -> adminService.uploadOrderReceipt(order.getId(), file));
        assertEquals(ErrorCode.COMMON_BAD_REQUEST, e.getErrorCode());
    }

    // ── 주문 상세: 요청한 사람 · 견적서 ──

    @Test
    void 주문_내역에_요청한_사람과_요청한_날을_싣는다() throws Exception {
        Inquiry request = Inquiry.builder().type(Inquiry.TYPE_CREDIT_ADD)
                .organizationId(orgA.getId()).userId(orgAdmin.getId()).build();
        setId(request, UUID.randomUUID());
        java.time.Instant requestedAt = java.time.Instant.parse("2026-06-02T02:20:00Z");
        org.springframework.test.util.ReflectionTestUtils.setField(request, "createdAt", requestedAt);
        Order linked = Order.builder().organizationId(orgA.getId()).orderDate(LocalDate.now())
                .description("크레딧 추가 · 3,000").amountKrw(780_000).creditAmount(3000L).requestId(request.getId()).build();
        Order plain = Order.builder().organizationId(orgA.getId()).orderDate(LocalDate.now())
                .description("연간 계약").amountKrw(1000).build();
        Mockito.when(orderRepository.findByOrganizationIdOrderByOrderDateDesc(orgA.getId())).thenReturn(List.of(linked, plain));
        Mockito.when(inquiryRepository.findAllById(List.of(request.getId()))).thenReturn(List.of(request));
        Mockito.when(userRepository.findAllById(List.of(orgAdmin.getId()))).thenReturn(List.of(orgAdmin));

        var items = orgService.getOrders(orgAdmin.getId().toString()).items();
        assertEquals("orga01", items.get(0).requestedBy());
        assertEquals("ROLE_ORG_ADMIN", items.get(0).requestedByRole());
        assertEquals(requestedAt, items.get(0).requestedAt());
        assertNull(items.get(1).requestedBy());
    }

    /** 주문에 잇는 요청은 같은 기관의 크레딧 추가 요청만 — 남의 기관 요청을 이으면 요청자가 새어 나간다 */
    @Test
    void 주문에는_같은_기관의_크레딧_추가_요청만_잇는다() throws Exception {
        Mockito.when(organizationRepository.findById(orgA.getId())).thenReturn(Optional.of(orgA));
        Inquiry otherOrg = Inquiry.builder().type(Inquiry.TYPE_CREDIT_ADD).organizationId(orgB.getId()).build();
        Inquiry accountIssue = Inquiry.builder().type(Inquiry.TYPE_ACCOUNT_ISSUE).organizationId(orgA.getId()).build();
        for (Inquiry bad : List.of(otherOrg, accountIssue)) {
            UUID id = UUID.randomUUID();
            setId(bad, id);
            Mockito.when(inquiryRepository.findById(id)).thenReturn(Optional.of(bad));
            CustomException e = assertThrows(CustomException.class, () -> adminService.createOrder(
                    new SupportDto.CreateOrder(orgA.getId(), LocalDate.now(), "크레딧 추가", 780_000L, 3000L, id)));
            assertEquals(ErrorCode.COMMON_BAD_REQUEST, e.getErrorCode());
        }
        Mockito.verify(orderRepository, Mockito.never()).save(Mockito.any());
    }

    @Test
    void 견적서는_증빙과_다른_자리에_올리고_자기_기관만_내려받는다() throws Exception {
        Order order = receiptOrder(orgA);
        Mockito.when(organizationRepository.findById(orgA.getId())).thenReturn(Optional.of(orgA));
        SupportDto.OrderItem item = adminService.uploadOrderQuote(order.getId(),
                new MockMultipartFile("file", "견적서.pdf", "application/pdf", new byte[]{1}));
        assertEquals("견적서.pdf", item.quoteFileName());
        Mockito.verify(s3Service).uploadFile(Mockito.eq("quotes/" + order.getId() + "/quote.pdf"),
                Mockito.any(), Mockito.eq("application/pdf"));
        Mockito.verify(s3Service, Mockito.never()).deleteByPrefix("receipts/" + order.getId() + "/");

        Mockito.when(s3Service.getPresignedUrl(Mockito.anyString(), Mockito.any())).thenReturn("https://signed");
        assertEquals("견적서.pdf", orgService.getOrderQuote(orgAdmin.getId().toString(), order.getId()).fileName());

        Order others = receiptOrder(orgB);
        CustomException e = assertThrows(CustomException.class,
                () -> orgService.getOrderQuote(orgAdmin.getId().toString(), others.getId()));
        assertEquals(ErrorCode.COMMON_FORBIDDEN, e.getErrorCode());
    }

    @Test
    void 증빙_타_기관_주문은_403() throws Exception {
        Order order = receiptOrder(orgB);   // orgAdmin은 orgA 소속
        Mockito.when(userRepository.findById(orgAdmin.getId())).thenReturn(Optional.of(orgAdmin));
        CustomException e = assertThrows(CustomException.class,
                () -> orgService.getOrderReceipt(orgAdmin.getId().toString(), order.getId()));
        assertEquals(ErrorCode.COMMON_FORBIDDEN, e.getErrorCode());
    }

    @Test
    void 증빙_없는_주문은_404() throws Exception {
        Order order = receiptOrder(orgA);
        Mockito.when(userRepository.findById(orgAdmin.getId())).thenReturn(Optional.of(orgAdmin));
        CustomException e = assertThrows(CustomException.class,
                () -> orgService.getOrderReceipt(orgAdmin.getId().toString(), order.getId()));
        assertEquals(ErrorCode.COMMON_NOT_FOUND, e.getErrorCode());
    }

    @Test
    void 증빙_내려받기_presigned() throws Exception {
        Order order = receiptOrder(orgA);
        order.attachReceipt("receipts/" + order.getId() + "/receipt.pdf", "계산서.pdf");
        Mockito.when(userRepository.findById(orgAdmin.getId())).thenReturn(Optional.of(orgAdmin));
        Mockito.when(s3Service.getPresignedUrl(Mockito.anyString(), Mockito.any()))
                .thenReturn("https://presigned.example/x");
        SupportDto.ReceiptDownload dl = orgService.getOrderReceipt(orgAdmin.getId().toString(), order.getId());
        assertEquals("계산서.pdf", dl.fileName());
        assertEquals("https://presigned.example/x", dl.url());
    }

    // ── 공개 공지 (무인증, 로그인 전) ──
    @Test
    void 공개_공지는_전체_대상_기간_내만() throws Exception {
        Notice all = Notice.builder().title("서버 점검").body("8/25 새벽")
                .startsOn(LocalDate.now().minusDays(1)).endsOn(LocalDate.now().plusDays(1)).build();
        setId(all, UUID.randomUUID());
        Mockito.when(noticeRepository.findVisibleForAll(Mockito.any())).thenReturn(List.of(all));
        var result = orgService.getPublicNotices();
        assertEquals(1, result.size());
        assertEquals("서버 점검", result.get(0).title());
        Mockito.verify(noticeRepository).findVisibleForAll(Mockito.any());  // 전체 대상·기간 필터는 쿼리가 담당
    }

    @Test
    void 요청_접수는_점역사도_가능_기관_미소속은_403() throws Exception {
        User member = User.builder().loginId("orga02").organization(orgA).password("pw").build();
        setId(member, UUID.randomUUID());   // 기본 ROLE_USER
        Mockito.when(userRepository.findById(member.getId())).thenReturn(Optional.of(member));
        Mockito.when(inquiryRepository.save(Mockito.any())).thenAnswer(inv -> {
            Inquiry i = inv.getArgument(0); setId(i, UUID.randomUUID()); return i;
        });
        Mockito.when(inquiryRepository.findById(Mockito.any())).thenReturn(Optional.empty());
        var item = orgService.createRequest(member.getId().toString(),
                new SupportDto.CreateRequest("CREDIT_ADD", "요청"));
        assertEquals("CREDIT_ADD", item.type());

        User orphan = User.builder().loginId("noorg01").password("pw").build();
        setId(orphan, UUID.randomUUID());
        Mockito.when(userRepository.findById(orphan.getId())).thenReturn(Optional.of(orphan));
        CustomException e = assertThrows(CustomException.class, () ->
                orgService.createRequest(orphan.getId().toString(),
                        new SupportDto.CreateRequest("CREDIT_ADD", "요청")));
        assertEquals(ErrorCode.COMMON_FORBIDDEN, e.getErrorCode());
    }

    // ── 문의 상세 첨부 ──
    @Test
    void 상세는_인라인과_파일_첨부를_나누고_둘_다_presigned_url을_싣는다() throws Exception {
        UUID inquiryId = UUID.randomUUID();
        Inquiry inquiry = Inquiry.builder().type(Inquiry.TYPE_EMAIL)
                .senderEmail("a@b.c").subject("제목").message("본문").mailUid("1:1").build();
        setId(inquiry, inquiryId);
        Mockito.when(inquiryRepository.findById(inquiryId)).thenReturn(Optional.of(inquiry));
        Mockito.when(organizationRepository.findAll()).thenReturn(List.of());

        var inline = InquiryAttachment.builder()
                .inquiryId(inquiryId).fileName("스크린샷.png").contentType("image/png")
                .sizeBytes(1234).storagePath("inquiries/x/a.png").isInline(true).build();
        setId(inline, UUID.randomUUID());
        var file = InquiryAttachment.builder()
                .inquiryId(inquiryId).fileName("원본.hwp").contentType("application/octet-stream")
                .sizeBytes(5678).storagePath("inquiries/x/b.hwp").isInline(false).build();
        setId(file, UUID.randomUUID());
        Mockito.when(inquiryAttachmentRepository.findByInquiryIdInOrderByCreatedAtAsc(Mockito.anyList()))
                .thenReturn(List.of(inline, file));
        Mockito.when(s3Service.getPresignedUrl(Mockito.anyString(), Mockito.any())).thenReturn("https://p/x");

        var detail = adminService.getInquiry(inquiryId);
        assertEquals(1, detail.inlineImages().size());
        assertEquals("https://p/x", detail.inlineImages().get(0).url());
        assertEquals(1, detail.attachments().size());
        assertEquals("원본.hwp", detail.attachments().get(0).fileName());
        assertEquals("https://p/x", detail.attachments().get(0).url());   // 다운로드 API 없이 즉시 저장

        CustomException e = assertThrows(CustomException.class,
                () -> adminService.getInquiry(UUID.randomUUID()));
        assertEquals(ErrorCode.COMMON_NOT_FOUND, e.getErrorCode());
    }
}
