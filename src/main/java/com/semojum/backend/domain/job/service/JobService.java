package com.semojum.backend.domain.job.service;

import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.semojum.backend.domain.auth.entity.User;
import com.semojum.backend.domain.auth.repository.UserRepository;
import com.semojum.backend.domain.job.dto.BrailleOptions;
import com.semojum.backend.domain.job.dto.JobRequestDto;
import com.semojum.backend.domain.job.dto.JobResponseDto;
import com.semojum.backend.domain.job.dto.LayoutOptions;
import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.domain.job.entity.Page;
import com.semojum.backend.domain.job.repository.JobRepository;
import com.semojum.backend.domain.job.repository.PageRepository;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import com.semojum.backend.global.hwp.HwpToPdfConverter;
import com.semojum.backend.global.s3.S3Service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.semojum.backend.domain.auth.enums.Role;
import com.semojum.backend.domain.job.scheduler.JobDispatcher;
import com.semojum.backend.global.thumbnail.ThumbnailService;
import com.semojum.backend.global.client.ClientInfoResolver;
import java.util.Set;

/**
 * 작업 생성 — 파일 업로드(PDF · HWP · HWPX · 이미지)와 새 문서 · TXT(텍스트 작업).
 *
 * <ul>
 *   <li>모드는 파일 종류가 정한다({@link #modeForExtension}) — 구 클라이언트의 {@code mode}는 받기만 한다</li>
 *   <li>⚠️ 업로드 원본을 힙에 올리지 않는다 — 임시 파일 경로로 쪽을 나눈다(100MB 4건 동시 OOM 실측)</li>
 *   <li>텍스트 작업은 AI를 거치지 않고 작업 기록만 남긴다</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobService {

    private final JobRepository jobRepository;
    private final PageRepository pageRepository;
    private final UserRepository userRepository;
    private final S3Service s3Service;
    private final RedisTemplate<String, String> redisTemplate;
    private final JobDispatcher jobDispatcher;
    private final ThumbnailService thumbnailService;
    private final HwpToPdfConverter hwpToPdfConverter;
    private final UploadOptionsResolver uploadOptionsResolver;


    /**
     * 파일 확장자 → 변환 모드. PDF·HWP·HWPX는 c(텍스트+점자), TXT는 b(점자).
     * HWP·HWPX는 업로드 시 PDF로 바꿔 PDF와 같은 길을 탄다 — 내용물이 HWPX인 .hwp도 변환기가 알아서 가른다.
     */
    static String modeForExtension(String ext) {
        return switch (ext) {
            case "pdf", "hwp", "hwpx" -> "c";
            case "jpg", "jpeg", "png" -> "c";
            case "txt" -> "b";
            default -> throw new CustomException(ErrorCode.JOB_INVALID_FILE);
        };
    }

    /**
     * 이미지 업로드 확장자. 화면은 JPG·PNG라고만 적었지만 .jpeg는 같은 형식이라 함께 받는다.
     *
     * <p>이미지도 모드는 c다 — 원본 | 텍스트 | 점자 세 패널이 나오는 건 PDF와 같고,
     * 다른 점은 쪽을 쪼갤 필요가 없다는 것뿐이다(한 장이 한 쪽).
     */
    static final Set<String> IMAGE_EXTS = Set.of("jpg", "jpeg", "png");

    static boolean isImage(String ext) {
        return IMAGE_EXTS.contains(ext);
    }

    /** 파일 이름의 확장자 (소문자, 점 없음). 확장자가 없으면 "" — modeForExtension이 JOB4002로 거절한다 */
    static String extensionOf(String fileName) {
        return fileName != null && fileName.contains(".")
                ? fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase()
                : "";
    }

    /**
     * 이미지의 S3 Content-Type. 확장자로만 정한다 — 검증은 modeForExtension이 이미 했다.
     *
     * <p>AI에 보내는 proto {@code image_mime_type}과 같은 값이다({@code PageWorker.imageMimeTypeOf}).
     *
     * <p>⚠️ {@code image/jpg}는 없는 타입이다 — 확장자만 .jpg로 줄여 쓰고 MIME은 {@code image/jpeg}다.
     * {@code "image/" + ext}로 만들면 엄격한 디코더가 거절한다.
     */
    static String imageMime(String ext) {
        return ext.equals("png") ? "image/png" : "image/jpeg";
    }

    /**
     * 여러 파일을 한 작업으로 받을 수 있는 건 이미지뿐이다 — 한 장이 한 쪽이라 N장이 N쪽이 된다.
     *
     * <p>PDF·HWP·HWPX·TXT는 한 파일이 문서 하나여서 여러 개를 이어붙일 기준이 없고,
     * 이미지와 섞이면 쪽 순서를 정할 수 없다. 둘 다 COMMON4000.
     */
    static void validateUploadFiles(List<String> exts) {
        if (exts.isEmpty()) {
            throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
        }
        if (exts.size() > 1 && !exts.stream().allMatch(JobService::isImage)) {
            throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
        }
    }

    public JobResponseDto.Create createJob(String userId, List<MultipartFile> files, String mode,
                                           boolean insertPageNumber, String footerText,
                                           LayoutOptions requestedOptions, String formId,
                                           BrailleOptions requestedBrailleOptions,
                                           ClientInfoResolver.ClientInfo clientInfo)
            throws Exception {

        // 꼬리말(묵자) 정리 — 빈 값은 null, 200자 초과는 거절 (예전 서버 점역의 입력 상한을 그대로 둔다)
        footerText = footerText == null || footerText.isBlank() ? null : footerText.trim();
        if (footerText != null && footerText.length() > 200) {
            throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
        }

        User user = userRepository.findById(UUID.fromString(userId))
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        // 기관 관리자는 점역(에디터) 기능 사용 불가 — 기관 관리 화면 전용
        if (user.getRole() == Role.ROLE_ORG_ADMIN) {
            log.warn("기관 관리자 계정의 Job 생성 시도 거부: user={}", user.getLoginId());
            throw new CustomException(ErrorCode.COMMON_FORBIDDEN);
        }

        // 조판·점역 옵션 — 양식이 바탕, 개별 항목이 그 위에 덮인다(UploadOptionsResolver).
        // 빠진 값은 기본값으로 채워 항상 완전한 형태로 저장한다(읽는 쪽에 null 분기를 안 남긴다)
        UploadOptionsResolver.Resolved resolved = uploadOptionsResolver.resolve(
                user, formId, requestedOptions, requestedBrailleOptions);
        LayoutOptions layoutOptions = resolved.layoutOptions().validated();
        BrailleOptions brailleOptions = resolved.brailleOptions();

        // 2. 모드는 파일 종류가 정한다 — 사용자가 고르지 않는다.
        //    PDF·HWP·HWPX·이미지 → c(원본 | 텍스트 | 점자 한 번에), TXT → b(원문 | 점자).
        //    구 클라이언트가 보내는 mode는 받기만 하고 쓰지 않는다 — a를 보내도 c로 만든다(텍스트 결과는 c에 다 들어 있다)
        List<String> exts = (files == null ? List.<MultipartFile>of() : files).stream()
                .map(f -> extensionOf(f.getOriginalFilename()))
                .toList();
        validateUploadFiles(exts);

        String requestedMode = mode;
        String ext = exts.get(0);
        mode = modeForExtension(ext);
        exts.forEach(JobService::modeForExtension);   // 섞인 목록도 확장자별로 검증한다

        MultipartFile file = files.get(0);
        log.info("Job 생성 시작: mode={}(요청 {}), file={} ({}KB){}, user={}",
                mode, requestedMode, file.getOriginalFilename(), file.getSize() / 1024,
                files.size() > 1 ? " 외 " + (files.size() - 1) + "장" : "", user.getLoginId());

        // 4. job_id 발급
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyMMddHHmmss"));
        String jobId = "job_" + timestamp + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);

        List<Page> pages = new ArrayList<>();
        List<String> tasks = new ArrayList<>();

        if (isImage(ext)) {
            return createFromImages(user, jobId, files,
                    insertPageNumber, footerText, layoutOptions, brailleOptions, clientInfo);

        } else if (mode.equals("b")) {
            // TXT 내용은 쓰지 않는다 — 앱이 이미 파일을 쥐고 있고 점역·쪽 나누기를 직접 한다.
            // 구 클라이언트 호환으로 받기만 한다. 새 클라이언트는 POST /api/jobs/text로 부르면 올릴 필요도 없다
            return createFromText(user, jobId,
                    resolveFileName(user.getId(), file.getOriginalFilename()),
                    insertPageNumber, footerText, layoutOptions, brailleOptions, clientInfo);

        } else {
            // 5. PDF 페이지별 분리 및 S3 업로드.
            // HWP · HWPX는 업로드 시점에 PDF로 바꾼다 — 이후는 PDF와 같다
            //
            // ⚠️ 원본을 힙(byte[])에 올리지 않는다. 업로드는 최대 100MB이고 운영 힙은 768MB라,
            // 큰 파일 몇 건이 겹치면 원본 배열만으로 OOM이 났다(실측: 100MB 4건 동시 → 12건 중 6건 OOM).
            // Spring이 이미 디스크에 받은 업로드를 임시 파일로 옮겨 경로로 읽고, 다 쓰면 finally에서 지운다
            Path upload = Files.createTempFile("upload-", "." + ext);
            HwpToPdfConverter.ConvertedPdf converted = null;
            try {
                file.transferTo(upload);
                Path pdfPath;
                if (ext.equals("hwp") || ext.equals("hwpx")) {
                    long convertStart = System.currentTimeMillis();
                    converted = hwpToPdfConverter.convert(upload);
                    pdfPath = converted.pdf();
                    log.info("HWP→PDF 변환 완료: {}KB → {}KB ({}ms)",
                            file.getSize() / 1024, Files.size(pdfPath) / 1024,
                            System.currentTimeMillis() - convertStart);
                } else {
                    pdfPath = upload;
                }

                // 파일 경로로 연다 — iText가 디스크에서 필요한 부분만 읽는다
                try (PdfReader reader = new PdfReader(pdfPath.toString());
                     PdfDocument pdfDoc = new PdfDocument(reader)) {

                    int totalPages = pdfDoc.getNumberOfPages();

                    // 6. Job 엔티티 생성 및 저장
                    Job job = Job.builder()
                            .id(jobId)
                            .user(user)
                            .mode(mode)
                            .totalPages(totalPages)
                            .originalFileName(resolveFileName(user.getId(), file.getOriginalFilename()))
                            .insertPageNumber(insertPageNumber)
                            .footerText(footerText)
                            .layoutOptions(layoutOptions)
                            .brailleOptions(brailleOptions)
                            .build();
                    // 꼬리말은 점역하지 않는다(앱이 점역). footer_braille은 null
                    if (clientInfo != null) {
                        job.recordClientInfo(clientInfo.ip(), clientInfo.os(), clientInfo.browser(), clientInfo.userAgent());
                    }
                    jobRepository.saveAndFlush(job);

                    try {
                        byte[] thumbnailBytes = thumbnailService.generatePdfThumbnail(pdfPath);
                        String thumbnailKey = jobId + "/thumbnail.png";
                        String thumbnailFullPath = s3Service.uploadFile(thumbnailKey, thumbnailBytes, "image/png");
                        job.updateThumbnailUrl(s3Service.getPublicUrl(thumbnailFullPath));
                        jobRepository.save(job);
                    } catch (Exception e) {
                        log.warn("썸네일 생성 실패: jobId={}", jobId, e);
                    }

                    redisTemplate.opsForHash().put(JobRedisKeys.pages(jobId), JobRedisKeys.TOTAL_PAGES, String.valueOf(totalPages));

                    // 7. 페이지별 처리
                    for (int i = 1; i <= totalPages; i++) {
                        ByteArrayOutputStream pageOut = new ByteArrayOutputStream();
                        try (PdfDocument pageDoc = new PdfDocument(new PdfWriter(pageOut))) {
                            pdfDoc.copyPagesTo(i, i, pageDoc);
                        }

                        String pageKey = jobId + "/pages/page-" + i + ".pdf";
                        String fullPath = s3Service.uploadFile(pageKey, pageOut.toByteArray(), "application/pdf");

                        Page page = Page.builder()
                                .job(job)
                                .pageNo(i)
                                .pdfPath(fullPath)
                                .build();
                        pages.add(page);

                        // 공정 스케줄러 태스크 (Job 저장 후 일괄 등록 — userId는 재시도 시 링 재등록용)
                        tasks.add(taskJson(jobId, i, fullPath, mode, totalPages,
                                user.getId(), layoutOptions.advancedAi(), brailleOptions));

                        redisTemplate.opsForHash().put(JobRedisKeys.pages(jobId), JobRedisKeys.pageField(i), "PENDING");
                    }

                    // 8. Page 일괄 저장 후 스케줄러 등록 (DB에 Page가 있어야 워커가 결과를 저장할 수 있음)
                    pageRepository.saveAll(pages);
                    jobDispatcher.enqueueJob(user.getId().toString(), jobId, tasks);

                    log.info("Job 생성 완료: jobId={}, totalPages={} — 큐 적재됨", jobId, totalPages);
                    return new JobResponseDto.Create(jobId, mode, totalPages, "PENDING", insertPageNumber, footerText, layoutOptions);
                }
            } finally {
                if (converted != null) converted.close();
                deleteQuietly(upload);
            }
        }
    }

    /** 업로드 임시 파일 정리 — 지우지 못해도 요청은 실패시키지 않는다(OS 임시 폴더 정리에 맡긴다) */
    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (Exception e) {
            log.warn("업로드 임시 파일 삭제 실패: {}", path);
        }
    }



    /**
     * 이미지 업로드 — 한 장이 한 쪽이다.
     *
     * <p>PDF 경로와 다른 점은 셋이다. 쪽을 쪼개지 않고(파일 하나가 쪽 하나), PDF로 변환하지 않고
     * 올라온 바이트를 그대로 S3에 둬서 워커가 그대로 AI에 보내며, 미리보기 렌더도 하지 않는다 —
     * 원본이 이미 이미지라 그 파일이 곧 미리보기다({@code imagePath}를 업로드 시점에 바로 채운다).
     *
     * <p><b>쪽 순서는 받은 순서다.</b> 서버가 파일 이름으로 다시 정렬하면 사용자가 고친 순서가
     * 뒤집힌다 — 이름순은 FE가 기본값으로 정렬해 보내고, 「순서 바꾸기」로 바꾼 결과도 그 순서로 온다.
     */
    private JobResponseDto.Create createFromImages(
            User user, String jobId, List<MultipartFile> files,
            boolean insertPageNumber, String footerText, LayoutOptions layoutOptions,
            BrailleOptions brailleOptions,
            ClientInfoResolver.ClientInfo clientInfo) throws Exception {

        int totalPages = files.size();
        String mode = modeForExtension(extensionOf(files.get(0).getOriginalFilename()));

        Job job = Job.builder()
                .id(jobId)
                .user(user)
                .mode(mode)
                .totalPages(totalPages)
                // 작업 이름은 첫 장의 파일 이름 — 여러 장이어도 작업은 하나다
                .originalFileName(resolveFileName(user.getId(), files.get(0).getOriginalFilename()))
                .insertPageNumber(insertPageNumber)
                .footerText(footerText)
                .layoutOptions(layoutOptions)
                .brailleOptions(brailleOptions)
                .build();
        // 꼬리말은 점역하지 않는다(앱이 점역). footer_braille은 null
        if (clientInfo != null) {
            job.recordClientInfo(clientInfo.ip(), clientInfo.os(), clientInfo.browser(), clientInfo.userAgent());
        }
        jobRepository.saveAndFlush(job);

        // 썸네일은 첫 장 — PDF처럼 렌더할 게 없어 PNG로 다시 인코딩만 한다. 실패는 삼킨다
        try {
            byte[] thumbnailBytes = thumbnailService.generateImageThumbnail(files.get(0).getBytes());
            String thumbnailKey = jobId + "/thumbnail.png";
            String thumbnailFullPath = s3Service.uploadFile(thumbnailKey, thumbnailBytes, "image/png");
            job.updateThumbnailUrl(s3Service.getPublicUrl(thumbnailFullPath));
            jobRepository.save(job);
        } catch (Exception e) {
            log.warn("썸네일 생성 실패: jobId={}", jobId, e);
        }

        redisTemplate.opsForHash().put(JobRedisKeys.pages(jobId), JobRedisKeys.TOTAL_PAGES, String.valueOf(totalPages));

        List<Page> pages = new ArrayList<>();
        List<String> tasks = new ArrayList<>();

        for (int i = 0; i < totalPages; i++) {
            int pageNo = i + 1;
            // 확장자는 그 자리의 파일에서 직접 뽑는다 — 목록과 따로 들고 다니면 어긋날 수 있다
            String ext = extensionOf(files.get(i).getOriginalFilename());

            String pageKey = jobId + "/pages/page-" + pageNo + "." + ext;
            String fullPath = s3Service.uploadFile(pageKey, files.get(i).getBytes(), imageMime(ext));

            Page page = Page.builder()
                    .job(job)
                    .pageNo(pageNo)
                    .pdfPath(fullPath)
                    .build();
            // 원본이 곧 미리보기 — 워커가 따로 렌더하지 않으므로 여기서 채운다
            page.updateImagePath(fullPath);
            pages.add(page);

            tasks.add(taskJson(jobId, pageNo, fullPath, mode, totalPages,
                    user.getId(), layoutOptions.advancedAi(), brailleOptions));

            redisTemplate.opsForHash().put(JobRedisKeys.pages(jobId), JobRedisKeys.pageField(pageNo), "PENDING");
        }

        pageRepository.saveAll(pages);
        jobDispatcher.enqueueJob(user.getId().toString(), jobId, tasks);

        log.info("Job 생성 완료(이미지 {}장): jobId={}, totalPages={} — 큐 적재됨", totalPages, jobId, totalPages);
        return new JobResponseDto.Create(jobId, mode, totalPages, "PENDING", insertPageNumber, footerText, layoutOptions);
    }

    /** 이름을 안 주면 「무제」 — 같은 이름이 있으면 resolveFileName이 (2), (3)을 붙인다 */
    static String resolveNewDocumentName(JobRequestDto.NewDocument request) {
        return request.fileName() == null || request.fileName().isBlank()
                ? "무제" : request.fileName().trim();
    }

    /**
     * 새 문서 — 파일 없이 본문 텍스트로 작업을 만든다.
     *
     * <p>앞단 검증은 파일 업로드와 같다(조판 옵션 · 꼬리말 200자 · 기관 관리자 차단).
     * 다른 점은 확장자로 모드를 정하지 않는다는 것뿐이다 — 언제나 텍스트 경로다.
     */
    public JobResponseDto.Create createNewDocument(
            String userId, JobRequestDto.NewDocument request,
            ClientInfoResolver.ClientInfo clientInfo) {

        boolean insertPageNumber = Boolean.TRUE.equals(request.insertPageNumber());

        String footerText = request.footerText() == null || request.footerText().isBlank()
                ? null : request.footerText().trim();
        if (footerText != null && footerText.length() > 200) {
            throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
        }

        User user = userRepository.findById(UUID.fromString(userId))
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        if (user.getRole() == Role.ROLE_ORG_ADMIN) {
            log.warn("기관 관리자 계정의 새 문서 생성 시도 거부: user={}", user.getLoginId());
            throw new CustomException(ErrorCode.COMMON_FORBIDDEN);
        }

        // 업로드와 같은 규칙 — 양식이 바탕, 개별 항목이 그 위에 덮인다
        UploadOptionsResolver.Resolved resolved = uploadOptionsResolver.resolve(
                user, request.formId(), request.layoutOptions(), request.brailleOptions());
        LayoutOptions layoutOptions = resolved.layoutOptions().validated();

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyMMddHHmmss"));
        String jobId = "job_" + timestamp + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);

        String name = resolveNewDocumentName(request);

        log.info("새 문서 생성 시작: user={}", user.getLoginId());

        return createFromText(user, jobId,
                resolveFileName(user.getId(), name),
                insertPageNumber, footerText, layoutOptions, resolved.brailleOptions(), clientInfo);
    }

    /** TXT 업로드와 「새 문서」가 공유하는 경로 — 둘 다 결과물이 본문 텍스트 하나다 */
    static final String MODE_TEXT = "b";

    /**
     * 공정 스케줄러 태스크 JSON — 워커가 읽는 키 이름이 여기 한 곳에만 있다.
     *
     * <p>PageWorker는 {@code mode}·{@code sourcePath}로 값을 꺼낸다. 키 이름이 틀리면 null이 되고
     * 그 쪽은 전부 BLOCKED로 떨어지는데 컴파일로는 안 잡힌다 — 파일 경로와 텍스트 경로가 같은
     * 포맷 문자열을 따로 들고 있다가 한쪽만 틀린 적이 있어 한 곳으로 모았다.
     */
    static String taskJson(String jobId, int pageNo, String sourcePath, String mode,
                           int totalPages, UUID userId, boolean advancedAi, BrailleOptions braille) {
        return String.format(
                "{\"jobId\":\"%s\",\"pageNo\":%d,\"sourcePath\":\"%s\",\"mode\":\"%s\",\"totalPages\":%d,\"userId\":\"%s\",\"advancedAi\":%b,\"koreanGrade\":%d,\"englishGrade\":%d,\"splitChoices\":%b}",
                jobId, pageNo, sourcePath, mode, totalPages, userId, advancedAi,
                koreanGradeOf(braille), englishGradeOf(braille), splitChoicesOf(braille));
    }

    /**
     * 점역 옵션 → proto {@code korean_grade}(10). 1 정자 · 2 약자 · 0 서버 기본(약자).
     *
     * <p>업로드 때 정해진 값이 태스크에 실린다 — 변환 중에는 저장이 막혀(JOB4010) 바뀔 수 없으므로
     * 한 작업의 모든 쪽이 같은 값으로 처리된다.
     */
    static int koreanGradeOf(BrailleOptions braille) {
        return braille == null ? 0 : gradeNumber(braille.hangulGrade());
    }

    /** 점역 옵션 → proto {@code english_grade}(11). 1 정자 · 2 약자 · 0 서버 기본(약자) */
    static int englishGradeOf(BrailleOptions braille) {
        return braille == null ? 0 : gradeNumber(braille.englishGrade());
    }

    private static int gradeNumber(String grade) {
        return switch (String.valueOf(grade)) {
            case "grade1" -> 1;
            case "grade2" -> 2;
            default -> 0;
        };
    }

    /** 점역 옵션 → proto {@code split_choices}(12). 「한 줄에 하나씩」이면 true */
    static boolean splitChoicesOf(BrailleOptions braille) {
        return braille != null && "onePerLine".equals(braille.choiceLayout());
    }

    /**
     * 텍스트 작업(TXT 업로드 · 「새 문서」)을 만든다 — <b>작업 기록만</b> 남긴다.
     *
     * <p>AI는 글을 받아도 점자를 주지 않아(proto에서 점역 결과 삭제) 텍스트 작업에서 AI가 해 줄 일이 없다. 점역·쪽 나누기·조판은 전부 앱이 원문으로 한다.
     * 그래서 쪽 나누기 · S3 업로드 · AI 큐 적재가 없고, 만들자마자 COMPLETED다 — 기다릴 변환이 없다.
     *
     * <p>그래도 작업을 만드는 이유: 최종 저장({@code POST /api/jobs/{jobId}/save})이 jobId를 요구한다.
     * 쪽과 쪽 결과는 <b>첫 저장 때 앱이 보낸 쪽 번호대로 만들어진다</b>({@code PageContentSaver}).
     * 서버가 미리 자르면 앱과 쪽 번호가 어긋날 위험만 생긴다.
     *
     * <p>크레딧은 차감하지 않는다 — 차감은 AI 결과가 올 때만 일어난다.
     */
    private JobResponseDto.Create createFromText(
            User user, String jobId,
            String fileName, boolean insertPageNumber, String footerText,
            LayoutOptions layoutOptions, BrailleOptions brailleOptions,
            ClientInfoResolver.ClientInfo clientInfo) {

        Job job = Job.builder()
                .id(jobId)
                .user(user)
                .mode(MODE_TEXT)
                .totalPages(0)          // 첫 저장이 앱이 보낸 쪽 수로 채운다
                .originalFileName(fileName)
                .insertPageNumber(insertPageNumber)
                .footerText(footerText)
                .layoutOptions(layoutOptions)
                .brailleOptions(brailleOptions)
                .build();
        if (clientInfo != null) {
            job.recordClientInfo(clientInfo.ip(), clientInfo.os(), clientInfo.browser(), clientInfo.userAgent());
        }
        job.complete(new int[]{});
        jobRepository.save(job);

        log.info("텍스트 작업 생성(변환 없음): jobId={}, name={}", jobId, fileName);
        return new JobResponseDto.Create(jobId, MODE_TEXT, 0, "COMPLETED", insertPageNumber, footerText, layoutOptions);
    }

    // 같은 계정의 작업(지운 것 제외)에 같은 이름이 있으면 "(2)", "(3)"…을 붙인다 — 예전 작업 목록에서 이름이 겹치지 않게 만든 규칙(지금은 목록이 없다)
    private String resolveFileName(UUID userId, String originalFileName) {
        String base = originalFileName != null ? originalFileName : "이름 없는 작업";
        if (base.length() > 100) base = base.substring(0, 100);
        if (!jobRepository.existsActiveFileName(userId, base)) {
            return base;
        }
        for (int n = 2; n <= 999; n++) {
            String candidate = base + " (" + n + ")";
            if (candidate.length() > 100) {
                candidate = base.substring(0, Math.max(1, 100 - (" (" + n + ")").length())) + " (" + n + ")";
            }
            if (!jobRepository.existsActiveFileName(userId, candidate)) {
                return candidate;
            }
        }
        return base + " (" + System.currentTimeMillis() + ")";
    }

}