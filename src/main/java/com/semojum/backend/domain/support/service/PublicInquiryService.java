package com.semojum.backend.domain.support.service;

import com.semojum.backend.domain.support.dto.SupportDto;
import com.semojum.backend.domain.support.entity.Inquiry;
import com.semojum.backend.domain.support.entity.InquiryAttachment;
import com.semojum.backend.domain.support.repository.InquiryAttachmentRepository;
import com.semojum.backend.domain.support.repository.InquiryRepository;
import com.semojum.backend.global.category.QuestionCategory;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import com.semojum.backend.global.s3.S3Service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 공개 문의 접수 — 홈페이지(미가입)와 앱 로그인 창의 「문의하기」. 인증 없는 공개 엔드포인트라 남용 방어가 본체다:
 *  - 허니팟(website 필드): 봇이 채우면 성공한 척 응답하고 버린다 (봇에게 실패 신호를 주지 않음)
 *  - IP 레이트리밋: 시간당 5건 (Redis INCR+EXPIRE — 장애 시 접수는 허용, 방어보다 접수 유실이 더 나쁨)
 * 접수분은 운영자 문의 목록에 orgName·loginId 없이(미가입) 표시된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PublicInquiryService {

    /** 홈페이지 문의 유형 — 앱 분류(QuestionCategory)와 함께 받는다(홈페이지 폼을 깨지 않게) */
    private static final Set<String> HOMEPAGE_TYPES =
            Set.of(Inquiry.TYPE_ONBOARDING, Inquiry.TYPE_ERROR_REPORT, Inquiry.TYPE_ETC);
    private static final Set<String> PUBLIC_TYPES = union(HOMEPAGE_TYPES, QuestionCategory.ALL);
    private static final int RATE_LIMIT_PER_HOUR = 5;

    /** 피그마 문의하기 「최대 3개 · 10MB 제한」 — 게시판(5개)과 다르다 */
    public static final int MAX_ATTACHMENTS = 3;
    public static final long MAX_ATTACHMENT_BYTES = 10L * 1024 * 1024;
    /** 이미지·문서·zip — 실행 파일은 받지 않는다(게시판과 같은 목록) */
    static final Set<String> ALLOWED_EXTS = Set.of(
            "jpg", "jpeg", "png", "gif",
            "pdf", "hwp", "hwpx", "txt", "doc", "docx", "xls", "xlsx", "brf",
            "zip");

    private final InquiryRepository inquiryRepository;
    private final InquiryAttachmentRepository attachmentRepository;
    private final S3Service s3Service;
    private final RedisTemplate<String, String> redisTemplate;

    @Transactional
    public void submit(SupportDto.SubmitInquiry request, List<MultipartFile> files, String clientIp) {
        if (!PUBLIC_TYPES.contains(request.type())) {
            log.warn("공개 문의 유형 오류: {}", request.type());
            throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
        }
        List<MultipartFile> uploads = files == null ? List.of() : files.stream().filter(f -> !f.isEmpty()).toList();
        validateFiles(uploads);
        // 허니팟 — 사람 눈에 안 보이는 필드가 채워져 있으면 봇. 성공처럼 응답하고 버린다
        if (request.website() != null && !request.website().isBlank()) {
            log.warn("공개 문의 허니팟 감지 — 폐기: ip={}", clientIp);
            return;
        }
        if (clientIp != null && overRateLimit(clientIp)) {
            log.warn("공개 문의 레이트리밋 초과: ip={}", clientIp);
            throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
        }
        Inquiry inquiry = inquiryRepository.save(Inquiry.builder()
                .type(request.type())
                .senderEmail(request.email().trim())
                .subject(trimToNull(request.title()))
                .senderName(trimToNull(request.name()))
                .senderOrgName(trimToNull(request.orgName()))
                .message(request.message().trim())
                .build());
        uploadAll(inquiry.getId(), uploads);
        // 메일 주소는 로그에 남기지 않는다 — 개인정보라 보관 기간 없이 로그에 쌓이면 안 된다
        log.info("공개 문의 접수: inquiryId={}, type={}, 첨부 {}개", inquiry.getId(), request.type(), uploads.size());
    }

    private static void validateFiles(List<MultipartFile> files) {
        if (files.size() > MAX_ATTACHMENTS) throw new CustomException(ErrorCode.INQUIRY_ATTACHMENT_INVALID);
        for (MultipartFile f : files) {
            if (f.getSize() > MAX_ATTACHMENT_BYTES) throw new CustomException(ErrorCode.INQUIRY_ATTACHMENT_TOO_LARGE);
            if (!ALLOWED_EXTS.contains(extensionOf(f.getOriginalFilename()))) {
                throw new CustomException(ErrorCode.INQUIRY_ATTACHMENT_INVALID);
            }
        }
    }

    private void uploadAll(UUID inquiryId, List<MultipartFile> files) {
        for (MultipartFile f : files) {
            String name = f.getOriginalFilename() == null ? "file" : f.getOriginalFilename();
            // 메일 문의 첨부와 같은 자리. 키에 원래 이름을 쓰지 않는다 — 한글·공백·슬래시가 섞여도 경로가 깨지지 않게
            String key = "inquiries/" + inquiryId + "/" + UUID.randomUUID() + "." + extensionOf(name);
            try {
                s3Service.uploadFile(key, f.getBytes(), f.getContentType());
            } catch (IOException e) {
                throw new CustomException(ErrorCode.COMMON_INTERNAL_ERROR);
            }
            attachmentRepository.save(InquiryAttachment.builder().inquiryId(inquiryId).fileName(name)
                    .contentType(f.getContentType()).sizeBytes(f.getSize()).storagePath(key).isInline(false).build());
        }
    }

    private boolean overRateLimit(String ip) {
        try {
            String key = "public-inquiry:rate:" + ip;
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, Duration.ofHours(1));
            }
            return count != null && count > RATE_LIMIT_PER_HOUR;
        } catch (Exception e) {
            // Redis 장애 시 접수 허용 — 방어 실패보다 문의 유실이 더 나쁘다
            log.warn("레이트리밋 확인 실패(접수 허용): {}", e.getMessage());
            return false;
        }
    }

    private static String extensionOf(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> all = new HashSet<>(a);
        all.addAll(b);
        return Set.copyOf(all);
    }
}
