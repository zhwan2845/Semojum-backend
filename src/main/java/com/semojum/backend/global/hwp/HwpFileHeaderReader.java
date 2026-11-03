package com.semojum.backend.global.hwp;

import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.poifs.filesystem.DocumentInputStream;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * HWP(5.0) 파일 머리말만 읽어 변환할 수 있는 파일인지 가린다 — 깨진 파일 JOB4007, 암호 · 배포용 · 공인인증서 암호화 JOB4008.
 *
 * <p>파일 전체를 읽지 않는다. hwplib({@code HWPReader.fromFile})는 그림까지 통째로 힙에 올려(28MB 파일에 73MB),
 * 변환 메모리 관문보다 앞에서 돌던 이 검사가 큰 HWP 20건 동시 업로드에서 힙 OOM을 냈다(2026-10-31 운영 이미지 재현).
 * Apache POI의 {@code POIFSFileSystem(File, readOnly)}는 파일을 열어 필요한 섹터만 읽는다.
 *
 * <p>머리말(FileHeader 스트림): 서명 32바이트 「HWP Document File」 · 버전 4바이트 · 속성 4바이트(little-endian).
 * 속성 비트 1 = 암호, 2 = 배포용, 8 = 공인인증서 암호화(hwplib {@code ForFileHeader}와 같은 자리).
 */
@Slf4j
public final class HwpFileHeaderReader {

    private static final byte[] SIGNATURE = "HWP Document File".getBytes(StandardCharsets.US_ASCII);
    static final int PASSWORD_BIT = 1;
    static final int DISTRIBUTION_BIT = 2;
    static final int CERT_ENCRYPTION_BIT = 8;

    private HwpFileHeaderReader() {}

    /** 변환할 수 없는 HWP면 예외 — 깨졌거나 HWP가 아니면 JOB4007, 암호 · 배포용이면 JOB4008 */
    public static void validate(Path source) {
        long properties;
        try (POIFSFileSystem fs = new POIFSFileSystem(source.toFile(), true);
             DocumentInputStream in = fs.createDocumentInputStream("FileHeader")) {
            byte[] signature = new byte[32];
            in.readFully(signature);
            if (!startsWith(signature, SIGNATURE)) throw new IllegalArgumentException("HWP 서명이 아니다");
            in.readInt();                                  // 버전
            properties = Integer.toUnsignedLong(in.readInt());
        } catch (Exception e) {
            log.warn("HWP 머리말 읽기 실패: {}", e.getMessage());
            throw new CustomException(ErrorCode.JOB_HWP_PARSE_FAILED);
        }
        if (bit(properties, PASSWORD_BIT) || bit(properties, DISTRIBUTION_BIT) || bit(properties, CERT_ENCRYPTION_BIT)) {
            throw new CustomException(ErrorCode.JOB_HWP_UNSUPPORTED);
        }
    }

    private static boolean bit(long value, int index) {
        return (value >> index & 1) == 1;
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        for (int i = 0; i < prefix.length; i++) if (data[i] != prefix[i]) return false;
        return true;
    }
}
