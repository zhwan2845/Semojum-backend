package com.semojum.backend.domain.support;

import com.semojum.backend.domain.support.entity.Inquiry;
import com.semojum.backend.domain.support.entity.RequestDetails;
import com.semojum.backend.domain.support.entity.Order;
import com.semojum.backend.domain.support.repository.InquiryRepository;
import com.semojum.backend.domain.support.repository.OrderRepository;
import com.semojum.backend.global.grpc.BrailleGrpcClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 문의 · 주문의 새 칸(jsonb 포함)이 실제 PostgreSQL에 저장되고 그대로 읽히는지 — 매핑 확인 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "JWT_SECRET=test-secret-key-for-context-load-only-32bytes+",
        "DB_PASSWORD=test",
        "GRPC_CERT_PATH=classpath:grpc/test-server.crt"
})
class SupportPersistenceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @MockitoBean
    BrailleGrpcClient brailleGrpcClient;

    @Autowired
    InquiryRepository inquiryRepository;

    @Autowired
    OrderRepository orderRepository;

    @Test
    void 요청_창의_칸과_문의하기_칸이_저장된다() {
        RequestDetails details = new RequestDetails("국어 담당", "name@kblib.or.kr", "SHARED", null, null);
        UUID id = inquiryRepository.save(Inquiry.builder().type(Inquiry.TYPE_ACCOUNT_ISSUE)
                .senderName("홍길동").senderOrgName("한국점자도서관").requestDetails(details).build()).getId();

        Inquiry loaded = inquiryRepository.findById(id).orElseThrow();
        assertEquals(details, loaded.getRequestDetails());
        assertEquals("홍길동", loaded.getSenderName());
        assertEquals("한국점자도서관", loaded.getSenderOrgName());
    }

    @Test
    void 주문의_요청과_견적서_칸이_저장된다() {
        UUID requestId = UUID.randomUUID();
        Order order = Order.builder().organizationId(UUID.randomUUID()).orderDate(LocalDate.now())
                .description("크레딧 추가 · 3,000").amountKrw(780_000).creditAmount(3000L).requestId(requestId).build();
        order.attachQuote("quotes/x/quote.pdf", "견적서.pdf");
        UUID id = orderRepository.save(order).getId();

        Order loaded = orderRepository.findById(id).orElseThrow();
        assertEquals(requestId, loaded.getRequestId());
        assertEquals("견적서.pdf", loaded.getQuoteFileName());
    }
}
