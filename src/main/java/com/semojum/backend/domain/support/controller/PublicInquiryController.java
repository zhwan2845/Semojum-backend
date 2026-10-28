package com.semojum.backend.domain.support.controller;

import com.semojum.backend.domain.support.dto.SupportDto;
import com.semojum.backend.domain.support.service.PublicInquiryService;
import com.semojum.backend.global.exception.ApiResponse;
import com.semojum.backend.global.client.ClientInfoResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 홈페이지 문의 접수 (무인증 공개) — 도입 문의·오류 신고가 운영자 문의 목록으로 들어간다 */
@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class PublicInquiryController {

    private final PublicInquiryService publicInquiryService;
    private final ClientInfoResolver clientInfoResolver;

    @PostMapping("/inquiries")
    public ApiResponse<Void> submit(@RequestBody @Valid SupportDto.SubmitInquiry request,
                                    HttpServletRequest httpRequest) {
        publicInquiryService.submit(request.type(), request.name(), request.email(),
                request.message(), request.website(),
                clientInfoResolver.resolve(httpRequest).ip());
        return ApiResponse.success(null);
    }
}
