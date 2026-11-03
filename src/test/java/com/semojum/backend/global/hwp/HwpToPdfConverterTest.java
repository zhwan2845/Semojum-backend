package com.semojum.backend.global.hwp;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;

import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;

class HwpToPdfConverterTest {

    private static HwpToPdfConverter newConverter() {
        return new HwpToPdfConverter(new ConversionMemoryGate(new ConversionMemoryProperties()));
    }

    // ── ODT 머리말/꼬리말 주입 (순수 로직) ──

    private byte[] sampleOdt(String contentXml) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            byte[] mime = "application/vnd.oasis.opendocument.text".getBytes(StandardCharsets.UTF_8);
            ZipEntry mimeEntry = new ZipEntry("mimetype");
            mimeEntry.setMethod(ZipEntry.STORED);
            mimeEntry.setSize(mime.length);
            CRC32 crc = new CRC32();
            crc.update(mime);
            mimeEntry.setCrc(crc.getValue());
            zip.putNextEntry(mimeEntry);
            zip.write(mime);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("content.xml"));
            zip.write(contentXml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    private String readEntry(byte[] zip, String name) throws Exception {
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (entry.getName().equals(name)) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        return null;
    }

    @Test
    void 머리말은_본문_시작_꼬리말은_본문_끝에_마커와_함께_주입된다() throws Exception {
        String content = "<office:document-content>"
                + "<office:body><office:text text:use-soft-page-breaks=\"true\">"
                + "<text:p>본문 첫 문단</text:p><text:p>본문 끝 문단</text:p>"
                + "</office:text></office:body></office:document-content>";
        byte[] result = HwpToPdfConverter.injectTexts(sampleOdt(content),
                List.of("저널명 <2026>"), List.of("쪽 하단 안내"));

        String xml = readEntry(result, "content.xml");
        int header = xml.indexOf("[머리말] 저널명 &lt;2026&gt;");     // XML 이스케이프 확인
        int first = xml.indexOf("본문 첫 문단");
        int last = xml.indexOf("본문 끝 문단");
        int footer = xml.indexOf("[꼬리말] 쪽 하단 안내");
        assertTrue(header > 0 && first > header, "머리말이 본문 앞에 있어야 함");
        assertTrue(footer > last, "꼬리말이 본문 뒤에 있어야 함");
        assertTrue(xml.indexOf("</office:text>") > footer, "꼬리말은 본문 태그 안쪽");
    }

    @Test
    void mimetype_엔트리는_무압축_그대로_보존된다() throws Exception {
        String content = "<office:document-content><office:body><office:text>"
                + "<text:p>x</text:p></office:text></office:body></office:document-content>";
        byte[] result = HwpToPdfConverter.injectTexts(sampleOdt(content), List.of("h"), List.of());

        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(result))) {
            ZipEntry first = in.getNextEntry();
            assertEquals("mimetype", first.getName());
            assertEquals(ZipEntry.STORED, first.getMethod());
            assertEquals("application/vnd.oasis.opendocument.text",
                    new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    // ── 경로 입력 — 원본을 힙에 올리지 않는다 ──

    /** 깨진 HWP — 종전과 같은 에러(JOB4007)이고, 원본 파일은 호출부 소유라 지우지 않는다 */
    @Test
    void 깨진_HWP는_JOB4007이고_원본은_남는다() throws Exception {
        Path src = Files.createTempFile("broken-", ".hwp");
        try {
            Files.write(src, "not a hwp".getBytes(StandardCharsets.UTF_8));
            CustomException e = assertThrows(
                    CustomException.class,
                    () -> newConverter().convert(src));
            assertEquals(ErrorCode.JOB_HWP_PARSE_FAILED, e.getErrorCode());
            assertTrue(Files.exists(src));
        } finally {
            Files.deleteIfExists(src);
        }
    }

    /** 내용물이 ZIP이면 확장자와 무관하게 HWPX 경로로 간다 — HWPX가 아닌 ZIP(docx 등)은 JOB4007 */
    @Test
    void ZIP_시그니처는_앞_4바이트로_가른다() throws Exception {
        Path src = Files.createTempFile("fake-", ".hwp");
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bos)) {
                zip.putNextEntry(new ZipEntry("word/document.xml"));
                zip.write("<w/>".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            Files.write(src, bos.toByteArray());
            CustomException e = assertThrows(
                    CustomException.class,
                    () -> newConverter().convert(src));
            assertEquals(ErrorCode.JOB_HWP_PARSE_FAILED, e.getErrorCode());
        } finally {
            Files.deleteIfExists(src);
        }
    }

    /** 결과 PDF 폴더는 close()로 지운다 — 업로드 처리가 try-with-resources로 쓴다 */
    @Test
    void 변환_결과는_닫으면_폴더째_지워진다() throws Exception {
        Path dir = Files.createTempDirectory("hwp2pdf-close");
        Path pdf = dir.resolve("in.pdf");
        Files.write(pdf, new byte[]{'%', 'P', 'D', 'F'});
        try (HwpToPdfConverter.ConvertedPdf converted = new HwpToPdfConverter.ConvertedPdf(pdf, dir)) {
            assertTrue(Files.exists(converted.pdf()));
        }
        assertFalse(Files.exists(dir));
    }

    // ── rhwp 우선 · 실패 시 기존 경로 (가짜 rhwp 스크립트로 확인) ──

    /** rhwp 자리에 둘 셸 스크립트 — 마지막 인자(-o 뒤 출력 경로)에 body를 실행한 결과를 쓴다 */
    private Path fakeRhwp(String body) throws Exception {
        Path script = Files.createTempFile("fake-rhwp-", ".sh");
        Files.writeString(script, "#!/bin/bash\nout=\"${@: -1}\"\n" + body + "\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        return script;
    }

    /** 빈 쪽 n장짜리 진짜 PDF — 가짜 rhwp가 출력 자리에 복사한다 */
    private Path blankPdf(int pages) throws Exception {
        Path pdf = Files.createTempFile("blank-", ".pdf");
        try (PdfDocument doc = new PdfDocument(new PdfWriter(pdf.toString()))) {
            for (int i = 0; i < pages; i++) doc.addNewPage(PageSize.A4);
        }
        return pdf;
    }

    private HwpToPdfConverter converterWith(Path rhwp) {
        return converterWith(rhwp, new ConversionMemoryGate(new ConversionMemoryProperties()));
    }

    private HwpToPdfConverter converterWith(Path rhwp, ConversionMemoryGate gate) {
        HwpToPdfConverter converter = new HwpToPdfConverter(gate);
        ReflectionTestUtils.setField(converter, "rhwp", rhwp.toString());
        ReflectionTestUtils.setField(converter, "rhwpTimeoutSeconds", 10L);
        return converter;
    }

    /** HWPX가 아닌 ZIP — rhwp를 건너뛰어 기존 경로로 가면 JOB4007이 나므로 어느 길로 갔는지 드러난다 */
    private Path notHwpxZip() throws Exception {
        Path src = Files.createTempFile("zip-", ".hwpx");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bos)) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write("<w/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        Files.write(src, bos.toByteArray());
        return src;
    }

    @Test
    void rhwp가_만든_PDF를_먼저_쓴다() throws Exception {
        Path src = notHwpxZip();
        // 운영에서 실측한 호출 모양 그대로인지 확인한다 — 다르면 실패로 끝나 기존 경로로 간다
        Path made = blankPdf(2);
        Path rhwp = fakeRhwp("[ \"$1 $2 $3 $4 $5\" = \"export-pdf " + src.toAbsolutePath()
                + " --backend direct -o\" ] || exit 9\ncp '" + made + "' \"$out\"");
        try (HwpToPdfConverter.ConvertedPdf converted = converterWith(rhwp).convert(src)) {
            assertArrayEquals(Files.readAllBytes(made), Files.readAllBytes(converted.pdf()));
        } finally {
            Files.deleteIfExists(src);
            Files.deleteIfExists(rhwp);
            Files.deleteIfExists(made);
        }
    }

    @Test
    void rhwp가_실패하면_기존_변환기로_바꾼다() throws Exception {
        Path src = notHwpxZip();
        Path rhwp = fakeRhwp("echo 'panic' >&2; exit 1");
        try {
            CustomException e = assertThrows(CustomException.class, () -> converterWith(rhwp).convert(src));
            assertEquals(ErrorCode.JOB_HWP_PARSE_FAILED, e.getErrorCode());   // 기존 HWPX 경로까지 갔다
        } finally {
            Files.deleteIfExists(src);
            Files.deleteIfExists(rhwp);
        }
    }

    @Test
    void rhwp_결과의_한_쪽이_한도보다_크면_기존_변환기로_바꾼다() throws Exception {
        Path src = notHwpxZip();
        Path made = blankPdf(1);
        Path rhwp = fakeRhwp("cp '" + made + "' \"$out\"");
        HwpToPdfConverter converter = converterWith(rhwp);
        ReflectionTestUtils.setField(converter, "rhwpMaxPageBytes", 10L);
        try {
            CustomException e = assertThrows(CustomException.class, () -> converter.convert(src));
            assertEquals(ErrorCode.JOB_HWP_PARSE_FAILED, e.getErrorCode());
        } finally {
            Files.deleteIfExists(src);
            Files.deleteIfExists(rhwp);
            Files.deleteIfExists(made);
        }
    }

    /** 한도는 쪽마다 본다 — 전체 파일이 커도 쪽이 작으면 rhwp 결과를 쓴다(그림 많은 2단 논문 25MB · 가장 큰 쪽 13MB 실측) */
    @Test
    void 전체가_커도_쪽마다_한도_안이면_rhwp_결과를_쓴다() throws Exception {
        Path src = notHwpxZip();
        Path made = blankPdf(3);
        Path rhwp = fakeRhwp("cp '" + made + "' \"$out\"");
        HwpToPdfConverter converter = converterWith(rhwp);
        long onePage = HwpToPdfConverter.largestPageBytes(made);
        assertTrue(Files.size(made) > onePage, "3쪽 파일이 한 쪽보다 커야 시험이 된다");
        ReflectionTestUtils.setField(converter, "rhwpMaxPageBytes", onePage);
        try (HwpToPdfConverter.ConvertedPdf converted = converter.convert(src)) {
            assertEquals(Files.size(made), Files.size(converted.pdf()));
        } finally {
            Files.deleteIfExists(src);
            Files.deleteIfExists(rhwp);
            Files.deleteIfExists(made);
        }
    }

    @Test
    void PDF가_아닌_rhwp_결과는_기존_변환기로_바꾼다() throws Exception {
        Path src = notHwpxZip();
        Path rhwp = fakeRhwp("printf 'not a pdf' > \"$out\"");
        try {
            CustomException e = assertThrows(CustomException.class, () -> converterWith(rhwp).convert(src));
            assertEquals(ErrorCode.JOB_HWP_PARSE_FAILED, e.getErrorCode());
        } finally {
            Files.deleteIfExists(src);
            Files.deleteIfExists(rhwp);
        }
    }

    /** 멈춘 rhwp는 시간 제한에서 끊긴다 — 출력을 스트림으로 끝까지 읽던 때는 여기서 영원히 기다렸다 */
    @Test
    void 멈춘_rhwp는_시간_제한에서_끊고_기존_변환기로_바꾼다() throws Exception {
        Path src = notHwpxZip();
        Path rhwp = fakeRhwp("echo started; sleep 30");
        HwpToPdfConverter converter = converterWith(rhwp);
        ReflectionTestUtils.setField(converter, "rhwpTimeoutSeconds", 1L);
        try {
            CustomException e = assertTimeoutPreemptively(Duration.ofSeconds(10),
                    () -> assertThrows(CustomException.class, () -> converter.convert(src)));
            assertEquals(ErrorCode.JOB_HWP_PARSE_FAILED, e.getErrorCode());
        } finally {
            Files.deleteIfExists(src);
            Files.deleteIfExists(rhwp);
        }
    }

    /** 깨진 · 암호 HWP는 rhwp에 넘기기 전에 거른다 — 사유가 분명한 오류 코드를 지킨다 */
    @Test
    void 깨진_HWP는_rhwp를_부르지_않고_JOB4007이다() throws Exception {
        Path src = Files.createTempFile("broken-", ".hwp");
        Path called = Files.createTempFile("rhwp-called-", ".txt");
        Files.delete(called);
        Path rhwp = fakeRhwp("touch '" + called + "'; printf '%%PDF' > \"$out\"");
        try {
            Files.write(src, "not a hwp".getBytes(StandardCharsets.UTF_8));
            CustomException e = assertThrows(CustomException.class, () -> converterWith(rhwp).convert(src));
            assertEquals(ErrorCode.JOB_HWP_PARSE_FAILED, e.getErrorCode());
            assertFalse(Files.exists(called));
        } finally {
            Files.deleteIfExists(src);
            Files.deleteIfExists(rhwp);
            Files.deleteIfExists(called);
        }
    }

    // ── 변환 메모리 예산 ──

    @Test
    void 변환이_끝나면_예산을_돌려준다() throws Exception {
        Path src = notHwpxZip();
        Path made = blankPdf(1);
        Path rhwp = fakeRhwp("cp '" + made + "' \"$out\"");
        ConversionMemoryGate gate = new ConversionMemoryGate(new ConversionMemoryProperties());
        try (HwpToPdfConverter.ConvertedPdf converted = converterWith(rhwp, gate).convert(src)) {
            assertTrue(Files.exists(converted.pdf()));
        } finally {
            Files.deleteIfExists(src);
            Files.deleteIfExists(rhwp);
            Files.deleteIfExists(made);
        }
        assertEquals(1200, gate.availableMb());
    }

    /** rhwp도 기존 변환기(LibreOffice)도 실패하는 HWPX — 두 단계 모두 예산을 잡았다가 돌려줘야 한다 */
    @Test
    void 실패해_기존_변환기까지_가도_예산을_모두_돌려준다() throws Exception {
        Path src = Files.createTempFile("min-", ".hwpx");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bos)) {
            zip.putNextEntry(new ZipEntry("mimetype"));
            zip.write("application/hwp+zip".getBytes(StandardCharsets.US_ASCII));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("Contents/section0.xml"));
            zip.write("<sec/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        Files.write(src, bos.toByteArray());
        Path rhwp = fakeRhwp("exit 1");
        Path soffice = fakeRhwp("exit 1");
        ConversionMemoryGate gate = new ConversionMemoryGate(new ConversionMemoryProperties());
        HwpToPdfConverter converter = converterWith(rhwp, gate);
        ReflectionTestUtils.setField(converter, "soffice", soffice.toString());
        ReflectionTestUtils.setField(converter, "timeoutSeconds", 10L);
        try {
            CustomException e = assertThrows(CustomException.class, () -> converter.convert(src));
            assertEquals(ErrorCode.JOB_HWP_CONVERT_FAILED, e.getErrorCode());   // LibreOffice 단계까지 갔다
        } finally {
            Files.deleteIfExists(src);
            Files.deleteIfExists(rhwp);
            Files.deleteIfExists(soffice);
        }
        assertEquals(1200, gate.availableMb());
    }

    /** 예산이 꽉 차 rhwp 자리를 못 잡으면 JOB4017 — 삼켜서 메모리를 더 쓰는 기존 경로로 가면 안 된다 */
    @Test
    void 예산이_꽉_차면_기존_변환기로_가지_않고_JOB4017() throws Exception {
        Path src = notHwpxZip();
        Path called = Files.createTempFile("rhwp-called-", ".txt");
        Files.delete(called);
        Path rhwp = fakeRhwp("touch '" + called + "'; exit 1");
        ConversionMemoryProperties props = new ConversionMemoryProperties();
        props.setWaitSeconds(1);
        ConversionMemoryGate gate = new ConversionMemoryGate(props);
        ConversionMemoryGate.Permit all = gate.acquire(ConversionMemoryGate.Stage.FALLBACK, 1L << 40);   // 예산 전체
        try {
            CustomException e = assertThrows(CustomException.class, () -> converterWith(rhwp, gate).convert(src));
            assertEquals(ErrorCode.JOB_CONVERT_BUSY, e.getErrorCode());
            assertFalse(Files.exists(called), "자리를 못 잡았으면 rhwp를 부르지 않는다");
        } finally {
            all.close();
            Files.deleteIfExists(src);
            Files.deleteIfExists(rhwp);
            Files.deleteIfExists(called);
        }
    }

    // ── 로컬 실측 E2E (도구 경로를 env로 받아 실행 — CI에서는 건너뜀) ──
    // 실행: HWP2PDF_E2E_FILE=<hwp> HWP2PDF_PYTHON=<python> HWP2PDF_SCRIPT=<hwp2odt.py> ./gradlew test --tests HwpToPdfConverterTest
    @Test
    @EnabledIfEnvironmentVariable(named = "HWP2PDF_E2E_FILE", matches = ".+")
    void 실파일_E2E_PDF가_생성된다() throws Exception {
        HwpToPdfConverter converter = newConverter();
        ReflectionTestUtils.setField(converter, "python", System.getenv("HWP2PDF_PYTHON"));
        ReflectionTestUtils.setField(converter, "script", System.getenv("HWP2PDF_SCRIPT"));
        ReflectionTestUtils.setField(converter, "soffice",
                System.getenv().getOrDefault("HWP2PDF_SOFFICE", "soffice"));
        ReflectionTestUtils.setField(converter, "timeoutSeconds", 180L);

        byte[] pdf;
        try (HwpToPdfConverter.ConvertedPdf converted = converter.convert(Path.of(System.getenv("HWP2PDF_E2E_FILE")))) {
            pdf = Files.readAllBytes(converted.pdf());
        }

        assertTrue(pdf.length > 1000, "PDF 크기");
        assertEquals("%PDF", new String(pdf, 0, 4, StandardCharsets.US_ASCII));
        String out = System.getenv("HWP2PDF_E2E_OUT");
        if (out != null) Files.write(Path.of(out), pdf);
        System.out.println("E2E 변환 성공: " + pdf.length + " bytes");
    }

    /** 암호 HWP는 hwplib가 읽는 도중 던진다 — 깨진 파일(JOB4007)이 아니라 JOB4008로 알려야 앱 안내가 맞다 */
    @Test
    void 암호_HWP_파싱_예외는_JOB4008이다() {
        assertEquals(ErrorCode.JOB_HWP_UNSUPPORTED,
                HwpToPdfConverter.parseFailureCode(new Exception("Files with passwords are not supported.")));
        assertEquals(ErrorCode.JOB_HWP_PARSE_FAILED,
                HwpToPdfConverter.parseFailureCode(new java.io.IOException("Invalid header signature")));
        assertEquals(ErrorCode.JOB_HWP_PARSE_FAILED, HwpToPdfConverter.parseFailureCode(new NullPointerException()));
    }
}
