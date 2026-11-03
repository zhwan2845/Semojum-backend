package com.semojum.backend.global.hwp;

import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.tool.blankfilemaker.BlankFileMaker;
import kr.dogfoot.hwplib.writer.HWPWriter;
import org.apache.poi.poifs.filesystem.DocumentInputStream;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** HWP 머리말만 읽어 깨짐(JOB4007) · 암호 · 배포용 · 공인인증서 암호화(JOB4008)를 가린다 */
class HwpFileHeaderReaderTest {

    @TempDir
    Path dir;

    private Path hwp(String name, Consumer<HWPFile> flags) throws Exception {
        HWPFile file = BlankFileMaker.make();
        flags.accept(file);
        Path path = dir.resolve(name);
        HWPWriter.toFile(file, path.toString());
        return path;
    }

    @Test
    void 정상_HWP는_통과한다() throws Exception {
        assertDoesNotThrow(() -> HwpFileHeaderReader.validate(hwp("ok.hwp", f -> {})));
    }

    /** 정상 HWP를 만든 뒤 머리말 속성 비트만 켠다 — hwplib는 암호 비트가 있는 파일을 쓰지 못한다 */
    private Path withPropertyBit(String name, int bit) throws Exception {
        Path path = hwp(name, f -> {});
        try (POIFSFileSystem fs = new POIFSFileSystem(path.toFile(), false)) {
            byte[] header;
            try (DocumentInputStream in = fs.createDocumentInputStream("FileHeader")) {
                header = in.readAllBytes();
            }
            ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).putInt(36,
                    ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt(36) | (1 << bit));
            fs.getRoot().createOrUpdateDocument("FileHeader", new ByteArrayInputStream(header));
            fs.writeFilesystem();
        }
        return path;
    }

    @Test
    void 암호_배포용_공인인증서_암호화는_JOB4008() throws Exception {
        Path password = withPropertyBit("password.hwp", HwpFileHeaderReader.PASSWORD_BIT);
        Path distribution = withPropertyBit("dist.hwp", HwpFileHeaderReader.DISTRIBUTION_BIT);
        Path cert = withPropertyBit("cert.hwp", HwpFileHeaderReader.CERT_ENCRYPTION_BIT);
        for (Path p : new Path[]{password, distribution, cert}) {
            CustomException e = assertThrows(CustomException.class, () -> HwpFileHeaderReader.validate(p), p.toString());
            assertEquals(ErrorCode.JOB_HWP_UNSUPPORTED, e.getErrorCode());
        }
    }

    /** 다른 속성(스크립트 저장 · 문서 이력 등)은 막지 않는다 */
    @Test
    void 다른_속성은_막지_않는다() throws Exception {
        Path p = hwp("history.hwp", f -> {
            f.getFileHeader().setHasDocumentHistory(true);
            f.getFileHeader().setSaveScript(true);
        });
        assertDoesNotThrow(() -> HwpFileHeaderReader.validate(p));
    }

    @Test
    void HWP가_아니면_JOB4007() throws Exception {
        Path garbage = Files.write(dir.resolve("garbage.hwp"), "not a compound file".getBytes());
        CustomException e = assertThrows(CustomException.class, () -> HwpFileHeaderReader.validate(garbage));
        assertEquals(ErrorCode.JOB_HWP_PARSE_FAILED, e.getErrorCode());
    }
}
