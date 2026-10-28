package com.semojum.backend.global.s3;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import java.time.Duration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/** 파일 저장소 — 경로는 {@code s3://{버킷}/{key}} 형태로 DB에 담고, 읽을 때 key만 뽑아 쓴다 */
@Slf4j
@Service
@RequiredArgsConstructor
public class S3Service {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;

    @Value("${aws.s3.bucket}")
    private String bucketName;

    @Value("${aws.region}")
    private String region;

    public String uploadFile(String path, byte[] fileBytes, String contentType) {
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(path)
                .contentType(contentType)
                .build();
        s3Client.putObject(request, RequestBody.fromBytes(fileBytes));
        log.info("S3 업로드 완료: {}", path);
        return "s3://" + bucketName + "/" + path;
    }

    // 저장 경로를 공개 URL로 변환 (s3://bucket/key → https://bucket.s3.region.amazonaws.com/key)
    // 공개 읽기가 허용된 객체(썸네일)에만 쓸 것 — 원본 페이지는 getPresignedUrl 사용
    public String getPublicUrl(String storagePath) {
        return "https://" + bucketName + ".s3." + region + ".amazonaws.com/" + toKey(storagePath);
    }

    // 만료형 서명 URL 발급 — 비공개 객체(원본 쪽 PDF · 미리보기 이미지 · 첨부)를 FE가 직접 받을 때 쓴다.
    // 서명은 로컬 연산(HMAC)이라 S3 왕복 없음. 만료 후에는 페이지 조회 API를 다시 호출해 새 URL을 받는다.
    public String getPresignedUrl(String storagePath, Duration ttl) {
        GetObjectPresignRequest request =
                GetObjectPresignRequest.builder()
                        .signatureDuration(ttl)
                        .getObjectRequest(GetObjectRequest.builder()
                                .bucket(bucketName)
                                .key(toKey(storagePath))
                                .build())
                        .build();
        return s3Presigner.presignGetObject(request).url().toString();
    }

    public byte[] downloadFile(String storagePath) {
        String key = toKey(storagePath);
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build();
        byte[] content = s3Client.getObjectAsBytes(request).asByteArray();
        log.info("S3 다운로드 완료: {}", key);
        return content;
    }

    /**
     * 객체 하나 삭제 — 게시판 첨부 삭제가 쓴다. 저장된 경로 컬럼 값을 그대로 받는다
     * ({@code s3://…} · 순수 key). 없는 키를 지워도 S3는 성공으로 응답한다.
     */
    public void deleteObject(String storagePath) {
        String key = toKey(storagePath);
        s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build());
        log.info("S3 객체 삭제: {}", key);
    }

    // 프리픽스 아래 모든 객체 제거 — 주문 증빙 재업로드(교체)가 쓴다
    public void deleteByPrefix(String prefix) {
        ListObjectsV2Request listReq =
                ListObjectsV2Request.builder()
                        .bucket(bucketName)
                        .prefix(prefix)
                        .build();
        ListObjectsV2Response listRes;
        do {
            listRes = s3Client.listObjectsV2(listReq);
            for (S3Object obj : listRes.contents()) {
                s3Client.deleteObject(DeleteObjectRequest.builder()
                        .bucket(bucketName)
                        .key(obj.key())
                        .build());
            }
            listReq = listReq.toBuilder().continuationToken(listRes.nextContinuationToken()).build();
        } while (Boolean.TRUE.equals(listRes.isTruncated()));
        log.info("S3 프리픽스 삭제 완료: {}", prefix);
    }

    /** `s3://{버킷}/{key}` 또는 순수 key에서 객체 key만 뽑는다. */
    private String toKey(String storagePath) {
        if (storagePath.startsWith("s3://")) {
            String withoutScheme = storagePath.substring("s3://".length());
            return withoutScheme.substring(withoutScheme.indexOf('/') + 1);
        }
        return storagePath;
    }
}
