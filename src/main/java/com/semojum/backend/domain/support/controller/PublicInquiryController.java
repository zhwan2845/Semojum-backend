package com.semojum.backend.domain.support.controller;

import com.semojum.backend.domain.support.dto.SupportDto;
import com.semojum.backend.domain.support.service.PublicInquiryService;
import com.semojum.backend.global.exception.ApiResponse;
import com.semojum.backend.global.client.ClientInfoResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/** 공개 문의 접수 (무인증) — 홈페이지 문의와 앱 로그인 창 「문의하기」가 운영자 문의 목록으로 들어간다 */
@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class PublicInquiryController {

    private final PublicInquiryService publicInquiryService;
    private final ClientInfoResolver clientInfoResolver;

    /** 홈페이지 — JSON, 첨부 없음 */
    @PostMapping(value = "/inquiries", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Void> submit(@RequestBody @Valid SupportDto.SubmitInquiry request,
                                    HttpServletRequest httpRequest) {
        publicInquiryService.submit(request, List.of(), clientInfoResolver.resolve(httpRequest).ip());
        return ApiResponse.success(null);
    }

    /** 앱 문의하기 창 — 같은 칸을 폼 필드로, 첨부는 {@code files}를 반복(최대 3개 · 한 개 10MB) */
    @PostMapping(value = "/inquiries", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Void> submitWithFiles(@ModelAttribute @Valid SupportDto.SubmitInquiry request,
                                             @RequestPart(value = "files", required = false) List<MultipartFile> files,
                                             HttpServletRequest httpRequest) {
        publicInquiryService.submit(request, files, clientInfoResolver.resolve(httpRequest).ip());
        return ApiResponse.success(null);
    }
}
