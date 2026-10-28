package com.semojum.backend.global.thumbnail;

import com.semojum.backend.global.pdf.PdfPageRenderer;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.io.ByteArrayInputStream;
/** 작업 썸네일(첫 장 PNG) — PDF는 poppler pdftoppm 우선 · 실패 시 PDFBox, 이미지는 PNG로 다시 인코딩한다 */
@Slf4j
@Service
public class ThumbnailService {

    // ⚠️ pdftoppm(PdfPageRenderer)을 먼저 쓴다 — PDFBox는 JPEG 2000 스캔본을 백지로 그린다(이유는 PdfPageRenderer).
    // 로컬처럼 pdftoppm이 없으면 PDFBox로 폴백한다
    private static final int THUMBNAIL_DPI = 100;

    private final PdfPageRenderer pdfPageRenderer;

    public ThumbnailService(PdfPageRenderer pdfPageRenderer) {
        this.pdfPageRenderer = pdfPageRenderer;
    }

    // 원본은 디스크 경로로 받는다 — 업로드 PDF(최대 100MB)를 힙에 올리지 않기 위해서다
    public byte[] generatePdfThumbnail(Path pdf) throws IOException {
        try {
            return pdfPageRenderer.renderFirstPage(pdf, PdfPageRenderer.Format.PNG, THUMBNAIL_DPI);
        } catch (IOException | InterruptedException e) {
            log.info("pdftoppm 썸네일 실패, PDFBox 폴백: {}", e.getMessage());
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        }
        return renderFirstPageWithPdfBox(pdf);
    }

    private byte[] renderFirstPageWithPdfBox(Path pdf) throws IOException {
        // 파일에서 연다 — 바이트 배열로 열면 원본 전체가 힙에 올라간다
        try (PDDocument document = PDDocument.load(pdf.toFile())) {
            PDFRenderer renderer = new PDFRenderer(document);
            BufferedImage image = renderer.renderImageWithDPI(0, 100);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        }
    }

    /**
     * 이미지 업로드의 썸네일 — 첫 장을 PNG로 다시 인코딩한다.
     *
     * <p>PDF처럼 렌더할 게 없다. 그래도 그냥 올리지 않고 PNG로 바꾸는 이유는 S3 공개 정책이
     * thumbnail.png 하나뿐이어서다 — 키와 실제 형식을 어긋나게 두지 않는다.
     */
    public byte[] generateImageThumbnail(byte[] imageBytes) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
        if (image == null) throw new IOException("이미지를 읽을 수 없습니다");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
