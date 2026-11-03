package com.semojum.backend.domain.support.controller;

import com.semojum.backend.domain.support.dto.SupportDto;
import com.semojum.backend.domain.support.service.PublicInquiryService;
import com.semojum.backend.global.grpc.BrailleGrpcClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MultipartFile;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 공개 문의 — 홈페이지 JSON과 앱 multipart가 같은 경로에서 같은 칸으로 들어오는지 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "JWT_SECRET=test-secret-key-for-context-load-only-32bytes+",
        "DB_PASSWORD=test",
        "GRPC_CERT_PATH=classpath:grpc/test-server.crt"
})
class PublicInquiryControllerTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @MockitoBean
    BrailleGrpcClient brailleGrpcClient;

    @MockitoBean
    PublicInquiryService publicInquiryService;

    @Autowired
    MockMvc mockMvc;

    @Test
    @SuppressWarnings("unchecked")
    void 앱_문의하기는_multipart로_칸과_첨부를_받는다() throws Exception {
        mockMvc.perform(multipart("/api/public/inquiries")
                        .file(new MockMultipartFile("files", "작업정보.txt", "text/plain", "x".getBytes()))
                        .param("type", "ACCOUNT").param("email", "name@example.com")
                        .param("title", "계정 두 개 받을 수 있을까요").param("orgName", "한국점자도서관")
                        .param("message", "본문"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSuccess").value(true));

        ArgumentCaptor<SupportDto.SubmitInquiry> req = ArgumentCaptor.forClass(SupportDto.SubmitInquiry.class);
        ArgumentCaptor<List<MultipartFile>> files = ArgumentCaptor.forClass(List.class);
        verify(publicInquiryService).submit(req.capture(), files.capture(), any());
        assertEquals("ACCOUNT", req.getValue().type());
        assertEquals("계정 두 개 받을 수 있을까요", req.getValue().title());
        assertEquals("한국점자도서관", req.getValue().orgName());
        assertEquals("작업정보.txt", files.getValue().get(0).getOriginalFilename());
    }

    @Test
    void 홈페이지_JSON은_그대로_받는다() throws Exception {
        mockMvc.perform(post("/api/public/inquiries").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"ONBOARDING\",\"name\":\"홍길동\",\"email\":\"a@b.com\",\"message\":\"도입 문의\"}"))
                .andExpect(status().isOk());
        verify(publicInquiryService).submit(any(), any(), any());
    }

    @Test
    void multipart도_필수_칸이_없으면_400이다() throws Exception {
        mockMvc.perform(multipart("/api/public/inquiries").param("type", "ACCOUNT").param("message", "본문"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON4000"));
        verify(publicInquiryService, never()).submit(any(), any(), any());
    }
}
