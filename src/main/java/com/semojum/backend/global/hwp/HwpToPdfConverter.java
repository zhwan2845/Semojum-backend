package com.semojum.backend.global.hwp;

import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.bodytext.Section;
import kr.dogfoot.hwplib.object.bodytext.control.Control;
import kr.dogfoot.hwplib.object.bodytext.control.ControlFooter;
import kr.dogfoot.hwplib.object.bodytext.control.ControlHeader;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;
import kr.dogfoot.hwplib.object.bodytext.paragraph.ParagraphList;
import kr.dogfoot.hwplib.reader.HWPReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * HWP를 "보이는 그대로"의 PDF로 바꿔 PDF 파이프라인에 태운다.
 *
 * <p>먼저 rhwp({@code rhwp export-pdf})로 바꾼다. 한글 조판을 따라 쪽을 나누고 그려서 쪽 수 · 2단 ·
 * 머리말 · 꼬리말이 원본대로 나온다. rhwp가 실패하거나 결과가 너무 크면 아래 기존 경로로 바꾼다.
 * <ul>
 *   <li>HWP: ODT(pyhwp, scripts/hwp2odt.py — RelaxNG 검증 우회) → PDF(LibreOffice headless)</li>
 *   <li>HWPX: HTML({@link HwpxToHtml}) → PDF(LibreOffice)</li>
 *   <li>기존 경로의 확인된 한계(실측): 다단(2단)이 1단으로 풀리고 쪽 나눔·조판이 원본과 다르다.
 *       머리말·꼬리말은 유실되므로 hwplib로 직접 읽어 ODT 본문 시작/끝에 [머리말]/[꼬리말] 마커로 주입한다</li>
 * </ul>
 *
 * <p>LibreOffice는 호출마다 전용 프로필 디렉터리를 써서 병렬 변환 시 프로필 잠금 충돌을 피한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HwpToPdfConverter {

    static final String HEADER_MARK = "[머리말]";
    static final String FOOTER_MARK = "[꼬리말]";

    // 변환 도구는 JVM 밖 프로세스라 -Xmx가 안 걸린다 — 단계마다 예상 메모리만큼 예산을 잡고 돌린다
    private final ConversionMemoryGate memoryGate;

    @Value("${hwp2pdf.python:python3}")
    private String python;

    @Value("${hwp2pdf.script:/app/hwp2odt.py}")
    private String script;

    @Value("${hwp2pdf.soffice:soffice}")
    private String soffice;

    // 실제 값은 application.yaml 의 300 — 이 기본값은 yaml 에 키가 없을 때만 쓰인다
    @Value("${hwp2pdf.timeout-seconds:120}")
    private long timeoutSeconds;

    // 비어 있으면 rhwp를 건너뛴다 — 실행 파일은 운영 이미지에만 있다(Dockerfile)
    @Value("${hwp2pdf.rhwp:}")
    private String rhwp;

    // 실패해도 기존 경로가 이어 받으므로 기존 단계보다 짧게 끊는다
    @Value("${hwp2pdf.rhwp-timeout-seconds:120}")
    private long rhwpTimeoutSeconds;

    /**
     * rhwp 결과에서 한 쪽이라도 이보다 크면 쓰지 않는다 — AI 서버 gRPC 수신 한도(20MB). AI에는 쪽 단위로 간다.
     * rhwp는 그림을 원본 해상도 그대로 넣어서(실측: 28MB HWP → 25MB PDF, 가장 큰 쪽 13MB) 한도를 넘을 수 있다.
     * 기존 경로는 그림을 300dpi로 줄인다.
     */
    private long rhwpMaxPageBytes = 20L * 1024 * 1024;

    /**
     * 변환 결과 PDF — 임시 폴더째 들고 다닌다. 다 쓰면 {@link #close()}로 폴더를 지운다(try-with-resources).
     *
     * <p>결과를 byte[]로 읽어 돌려주지 않는 이유: 업로드는 최대 100MB라 원본과 결과를 둘 다 힙에 올리면
     * -Xmx768m에서 몇 건만 겹쳐도 OOM이 난다. 호출부는 경로로 쪽을 나눈다.
     */
    public static final class ConvertedPdf implements AutoCloseable {
        private final Path pdf;
        private final Path dir;

        public ConvertedPdf(Path pdf, Path dir) {
            this.pdf = pdf;
            this.dir = dir;
        }

        public Path pdf() {
            return pdf;
        }

        @Override
        public void close() {
            cleanup(dir);
        }
    }

    /**
     * 디스크의 HWP·HWPX를 PDF로 바꾼다. 원본 파일은 호출부 소유라 지우지 않는다.
     * 실패하면 임시 폴더를 지우고 예외를 던진다(JOB4007 · JOB4008 · JOB4013).
     */
    public ConvertedPdf convert(Path source) {
        // HWPX(ZIP)는 확장자와 무관하게 내용물로 가른다 — 최신 한글은 .hwp로도 HWPX를 저장한다
        if (HwpxToHtml.isZip(head(source))) {
            return convertHwpx(source);
        }
        // 깨짐 · 암호 · 배포용 검사는 머리말만 읽는다 — 파일 전체 파싱(hwplib)은 메모리 관문 앞에서 돌면 힙 OOM을 낸다
        HwpFileHeaderReader.validate(source);
        ConvertedPdf byRhwp = convertWithRhwp(source);
        if (byRhwp != null) return byRhwp;

        ConversionMemoryGate.Permit permit = memoryGate.acquire(ConversionMemoryGate.Stage.FALLBACK, fileSize(source));
        Path dir = null;
        boolean ok = false;
        try {
            // 기존 경로만 파일 전체를 읽는다(머리말 · 꼬리말 수집) — 관문 안이라 동시에 몇 건만 돈다
            HWPFile hwp = readAndValidate(source);
            List<String> headers = collectNotes(hwp, true);
            List<String> footers = collectNotes(hwp, false);
            dir = Files.createTempDirectory("hwp2pdf");
            Path odtPath = dir.resolve("in.odt");

            run(dir, python, script, source.toAbsolutePath().toString(), odtPath.toString());

            if (!headers.isEmpty() || !footers.isEmpty()) {
                byte[] injected = injectTexts(Files.readAllBytes(odtPath), headers, footers);
                Files.write(odtPath, injected);
            }

            run(dir, soffice, "--headless",
                    "-env:UserInstallation=file://" + dir.resolve("lo-profile"),
                    "--convert-to", "pdf", "--outdir", dir.toString(), odtPath.toString());

            Path pdfPath = dir.resolve("in.pdf");
            if (!Files.exists(pdfPath) || Files.size(pdfPath) == 0) {
                throw new CustomException(ErrorCode.JOB_HWP_CONVERT_FAILED);
            }
            ok = true;
            return new ConvertedPdf(pdfPath, dir);
        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            log.warn("HWP→PDF 변환 실패: {}", e.getMessage());
            throw new CustomException(ErrorCode.JOB_HWP_CONVERT_FAILED);
        } finally {
            permit.close();
            if (!ok) cleanup(dir);
        }
    }

    /**
     * HWPX → HTML(HwpxToHtml) → PDF(LibreOffice).
     * HTML은 Writer 필터로 열어야 @page 용지 크기가 적용된다(기본인 Writer/Web은 쪽 개념이 없다).
     * ⚠️ HWPX는 ZIP을 풀어야 해서 원본을 힙에 읽는다(HwpxToHtml이 byte[]를 받는다) — 결과 PDF만 경로로 돌려준다.
     */
    private ConvertedPdf convertHwpx(Path source) {
        ConvertedPdf byRhwp = convertWithRhwp(source);
        if (byRhwp != null) return byRhwp;
        HwpxToHtml.Result html;
        try {
            html = HwpxToHtml.convert(Files.readAllBytes(source));
        } catch (IOException e) {
            log.warn("HWPX 읽기 실패: {}", e.getMessage());
            throw new CustomException(ErrorCode.JOB_HWP_PARSE_FAILED);
        }
        ConversionMemoryGate.Permit permit = memoryGate.acquire(ConversionMemoryGate.Stage.FALLBACK, fileSize(source));
        Path dir = null;
        boolean ok = false;
        try {
            dir = Files.createTempDirectory("hwpx2pdf");
            Path htmlPath = dir.resolve("in.html");
            Files.writeString(htmlPath, html.html(), StandardCharsets.UTF_8);
            for (var img : html.images().entrySet()) {
                Files.write(dir.resolve(img.getKey()), img.getValue());
            }

            run(dir, soffice, "--headless",
                    "-env:UserInstallation=file://" + dir.resolve("lo-profile"),
                    "--infilter=HTML (StarWriter)",
                    "--convert-to", "pdf:writer_pdf_Export", "--outdir", dir.toString(), htmlPath.toString());

            Path pdfPath = dir.resolve("in.pdf");
            if (!Files.exists(pdfPath) || Files.size(pdfPath) == 0) {
                throw new CustomException(ErrorCode.JOB_HWP_CONVERT_FAILED);
            }
            ok = true;
            return new ConvertedPdf(pdfPath, dir);
        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            log.warn("HWPX→PDF 변환 실패: {}", e.getMessage());
            throw new CustomException(ErrorCode.JOB_HWP_CONVERT_FAILED);
        } finally {
            permit.close();
            if (!ok) cleanup(dir);
        }
    }

    /**
     * rhwp로 PDF를 만든다. 실패하면 null — 호출부가 기존 경로로 바꾼다.
     *
     * <p>{@code --backend direct}로 그린다. 기본 엔진(svg)은 쪽마다 한글 폰트(Noto CJK)를 새로 넣어서
     * 운영 서버에서 27쪽 HWPX가 24초 걸렸다(direct 0.3초, 실측). direct는 본문을 고딕 한 서체로 그린다.
     */
    private ConvertedPdf convertWithRhwp(Path source) {
        if (rhwp == null || rhwp.isBlank()) return null;
        // 예산 대기 초과(JOB4017)는 아래 catch 밖에서 던진다 — 삼키면 메모리를 더 쓰는 기존 경로로 간다
        ConversionMemoryGate.Permit permit = memoryGate.acquire(ConversionMemoryGate.Stage.RHWP, fileSize(source));
        Path dir = null;
        boolean ok = false;
        try {
            dir = Files.createTempDirectory("rhwp2pdf");
            Path pdfPath = dir.resolve("out.pdf");
            run(dir, rhwpTimeoutSeconds, rhwp, "export-pdf", source.toAbsolutePath().toString(),
                    "--backend", "direct", "-o", pdfPath.toString());
            long largest = Files.exists(pdfPath) && Files.size(pdfPath) > 0 ? largestPageBytes(pdfPath) : 0;
            if (largest == 0 || largest > rhwpMaxPageBytes) {
                log.warn("rhwp 결과를 쓰지 않음 — 기존 변환기로 바꾼다: largestPage={}KB", largest / 1024);
                return null;
            }
            ok = true;
            return new ConvertedPdf(pdfPath, dir);
        } catch (Exception e) {
            log.warn("rhwp 변환 실패 — 기존 변환기로 바꾼다: {}", e.getMessage());
            return null;
        } finally {
            permit.close();
            if (!ok) cleanup(dir);
        }
    }

    /**
     * 쪽 하나로 떼어 낸 PDF 중 가장 큰 크기. 업로드가 쪽을 나누는 방식(iText copyPagesTo)과 같게 재고,
     * 결과는 버린다(크기만 센다). 쪽이 없으면 0.
     */
    static long largestPageBytes(Path pdf) throws IOException {
        long largest = 0;
        try (PdfDocument doc = new PdfDocument(new PdfReader(pdf.toString()))) {
            for (int i = 1; i <= doc.getNumberOfPages(); i++) {
                ByteCounter counter = new ByteCounter();
                try (PdfDocument page = new PdfDocument(new PdfWriter(counter))) {
                    doc.copyPagesTo(i, i, page);
                }
                largest = Math.max(largest, counter.count);
            }
        }
        return largest;
    }

    private static final class ByteCounter extends OutputStream {
        private long count;

        @Override
        public void write(int b) {
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) {
            count += len;
        }
    }

    private static long fileSize(Path source) {
        try {
            return Files.size(source);
        } catch (IOException e) {
            throw new CustomException(ErrorCode.JOB_HWP_PARSE_FAILED);
        }
    }

    /** 앞 4바이트 — ZIP 시그니처(HWPX) 판별용. 파일 전체를 읽지 않는다 */
    private static byte[] head(Path source) {
        try (var in = Files.newInputStream(source)) {
            return in.readNBytes(4);
        } catch (IOException e) {
            throw new CustomException(ErrorCode.JOB_HWP_PARSE_FAILED);
        }
    }

    /**
     * 파싱 예외를 에러 코드로 — 암호 HWP는 아래 머리말 검사까지 가지 못하고 hwplib가 읽는 도중
     * 「Files with passwords are not supported.」로 던진다(1.1.9 실측). 깨진 파일(JOB4007)이 아니라 JOB4008로 알린다.
     */
    static ErrorCode parseFailureCode(Exception e) {
        String message = e.getMessage();
        return message != null && message.toLowerCase().contains("password")
                ? ErrorCode.JOB_HWP_UNSUPPORTED : ErrorCode.JOB_HWP_PARSE_FAILED;
    }

    private HWPFile readAndValidate(Path source) {
        HWPFile hwp;
        try {
            hwp = HWPReader.fromFile(source.toFile());
        } catch (Exception e) {
            log.warn("HWP 파싱 실패: {}", e.getMessage());
            throw new CustomException(parseFailureCode(e));
        }
        if (hwp == null) {
            throw new CustomException(ErrorCode.JOB_HWP_PARSE_FAILED);
        }
        if (hwp.getFileHeader().hasPassword()
                || hwp.getFileHeader().isDistribution()
                || hwp.getFileHeader().isEncryptPublicCertification()) {
            throw new CustomException(ErrorCode.JOB_HWP_UNSUPPORTED);
        }
        return hwp;
    }

    // 문서에 정의된 머리말/꼬리말 텍스트를 정의 순서대로, 중복 없이 수집
    private List<String> collectNotes(HWPFile hwp, boolean header) {
        LinkedHashSet<String> notes = new LinkedHashSet<>();
        for (Section section : hwp.getBodyText().getSectionList()) {
            for (int i = 0; i < section.getParagraphCount(); i++) {
                Paragraph p = section.getParagraph(i);
                if (p.getControlList() == null) continue;
                for (Control control : p.getControlList()) {
                    ParagraphList body = null;
                    if (header && control instanceof ControlHeader h) body = h.getParagraphList();
                    if (!header && control instanceof ControlFooter f) body = f.getParagraphList();
                    if (body == null) continue;
                    String text = noteText(body);
                    if (!text.isBlank()) notes.add(text);
                }
            }
        }
        return new ArrayList<>(notes);
    }

    private String noteText(ParagraphList paragraphs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < paragraphs.getParagraphCount(); i++) {
            try {
                String t = paragraphs.getParagraph(i).getNormalString();
                if (t != null && !t.isBlank()) {
                    if (sb.length() > 0) sb.append(' ');
                    sb.append(t.strip());
                }
            } catch (Exception ignored) { }
        }
        return sb.toString();
    }

    /**
     * ODT의 content.xml에 머리말/꼬리말 문단을 주입한다 — 머리말은 본문 시작, 꼬리말은 본문 끝.
     * mimetype 엔트리는 ODF 규격상 무압축(STORED) 첫 엔트리여야 하므로 그대로 보존한다.
     */
    static byte[] injectTexts(byte[] odt, List<String> headers, List<String> footers) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(odt.length + 4096);
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(odt));
             ZipOutputStream zip = new ZipOutputStream(out)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                byte[] data = in.readAllBytes();
                if (entry.getName().equals("content.xml")) {
                    String xml = new String(data, StandardCharsets.UTF_8);
                    StringBuilder head = new StringBuilder();
                    for (String h : headers) head.append(paragraph(HEADER_MARK + " " + h));
                    StringBuilder tail = new StringBuilder();
                    for (String f : footers) tail.append(paragraph(FOOTER_MARK + " " + f));
                    int bodyOpen = xml.indexOf("<office:text");
                    if (bodyOpen >= 0) {
                        int afterOpen = xml.indexOf('>', bodyOpen) + 1;
                        int bodyClose = xml.lastIndexOf("</office:text>");
                        xml = xml.substring(0, afterOpen) + head
                                + xml.substring(afterOpen, bodyClose) + tail
                                + xml.substring(bodyClose);
                    }
                    data = xml.getBytes(StandardCharsets.UTF_8);
                }
                ZipEntry copy = new ZipEntry(entry.getName());
                if (entry.getName().equals("mimetype")) {
                    copy.setMethod(ZipEntry.STORED);
                    copy.setSize(data.length);
                    CRC32 crc = new CRC32();
                    crc.update(data);
                    copy.setCrc(crc.getValue());
                }
                zip.putNextEntry(copy);
                zip.write(data);
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static String paragraph(String text) {
        return "<text:p>" + text
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                + "</text:p>";
    }

    private void run(Path dir, String... command) throws IOException, InterruptedException {
        run(dir, timeoutSeconds, command);
    }

    // ⚠️ 출력은 파일로 받는다 — 스트림을 끝까지 읽은 뒤 waitFor 하면 프로세스가 멈췄을 때 읽기에서
    // 영원히 기다려 시간 제한이 걸리지 않는다
    private void run(Path dir, long timeoutSeconds, String... command) throws IOException, InterruptedException {
        Path logFile = Files.createTempFile(dir, "process-", ".log");
        Process process = new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(logFile.toFile())
                .start();
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            log.warn("HWP→PDF 단계 타임아웃({}s): {}", timeoutSeconds, command[0]);
            throw new CustomException(ErrorCode.JOB_HWP_CONVERT_FAILED);
        }
        String output = new String(Files.readAllBytes(logFile), StandardCharsets.UTF_8);   // 깨진 바이트도 예외 없이
        if (process.exitValue() != 0) {
            log.warn("HWP→PDF 단계 실패({}): {}", command[0],
                    output.substring(0, Math.min(output.length(), 500)));
            throw new CustomException(ErrorCode.JOB_HWP_CONVERT_FAILED);
        }
    }

    private static void cleanup(Path dir) {
        if (dir == null) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }
}
