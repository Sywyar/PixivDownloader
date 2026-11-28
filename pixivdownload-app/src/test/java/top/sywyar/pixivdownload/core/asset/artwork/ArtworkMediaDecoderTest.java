package top.sywyar.pixivdownload.core.asset.artwork;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.core.ffmpeg.FfmpegRunner;
import java.nio.file.*;
import java.util.zip.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ResourceLock("runtime-paths")
class ArtworkMediaDecoderTest {
    @TempDir Path directory;
    @Test @DisplayName("大 WebP 只读取首帧，保持预览颜色并拒绝超限首帧与容器")
    void previewsLargeWebpWithinFrameBudget() throws Exception {
        String before = System.getProperty(RuntimeFiles.DATA_DIR_PROPERTY);
        System.setProperty(RuntimeFiles.DATA_DIR_PROPERTY, directory.resolve("data").toString());
        try {
            Path fixture = Path.of(getClass().getResource("/images/gradient-animated.webp").toURI());
            Path large = directory.resolve("large.webp");
            Files.copy(fixture, large);
            long size = 400L * 1024 * 1024;
            try (var file = new java.io.RandomAccessFile(large.toFile(), "rw")) {
                long original = file.length();
                file.seek(original);
                file.writeInt(0x4a554e4b);
                file.writeInt(Integer.reverseBytes((int) (size - original - 8)));
                file.setLength(size);
                file.seek(4);
                file.writeInt(Integer.reverseBytes((int) (size - 8)));
            }
            var runner = mock(FfmpegRunner.class);
            var decoder = new ArtworkMediaDecoder(runner, JsonMapper.builderWithJackson2Defaults().build());
            for (boolean cover : new boolean[]{false, true}) {
                var expected = cover ? decoder.readCover(fixture, 64) : decoder.read(fixture, 64);
                var actual = cover ? decoder.readCover(large, 64) : decoder.read(large, 64);
                assertEquals(expected.getWidth(), actual.getWidth());
                assertEquals(expected.getHeight(), actual.getHeight());
                assertArrayEquals(expected.getRGB(0, 0, expected.getWidth(), expected.getHeight(), null, 0, expected.getWidth()),
                        actual.getRGB(0, 0, actual.getWidth(), actual.getHeight(), null, 0, actual.getWidth()));
            }
            Path extracted = directory.resolve("first.webp");
            WebpPreviewFrame.copy(large, extracted, 100L * 1024 * 1024);
            long firstFrameBytes = Files.size(extracted);
            assertTrue(firstFrameBytes < Files.size(fixture));
            WebpPreviewFrame.copy(large, extracted, firstFrameBytes);
            assertThrows(java.io.IOException.class, () -> WebpPreviewFrame.copy(large, extracted, firstFrameBytes - 1));
            Thread.currentThread().interrupt();
            try { assertThrows(java.io.InterruptedIOException.class, () -> decoder.read(large, 64)); }
            finally { Thread.interrupted(); }
            try (var file = new java.io.RandomAccessFile(large.toFile(), "rw")) {
                file.seek(4);
                file.writeInt(0);
            }
            assertThrows(java.io.IOException.class, () -> decoder.read(large, 64));
            assertEquals(size, Files.size(large));
            assertCacheEmpty();
            verifyNoInteractions(runner);
        } finally {
            if (before == null) System.clearProperty(RuntimeFiles.DATA_DIR_PROPERTY);
            else System.setProperty(RuntimeFiles.DATA_DIR_PROPERTY, before);
        }
    }

    @Test @DisplayName("ZIP 只读首帧，不使用归档路径并清理超限临时文件")
    void boundedFirstFrame() throws Exception {
        String before = System.getProperty(RuntimeFiles.DATA_DIR_PROPERTY);
        System.setProperty(RuntimeFiles.DATA_DIR_PROPERTY, directory.resolve("data").toString());
        try {
            var runner = mock(FfmpegRunner.class);
            var decoder = new ArtworkMediaDecoder(runner, JsonMapper.builderWithJackson2Defaults().build());
            Path archive = directory.resolve("animation.zip");
            try (var out = new ZipOutputStream(Files.newOutputStream(archive))) {
                out.putNextEntry(new ZipEntry("../../escape.png"));
                ImageIO.write(new BufferedImage(2, 4, BufferedImage.TYPE_INT_RGB), "png", out);
                out.closeEntry();
            }
            byte[] bytes = Files.readAllBytes(archive);
            assertEquals(2, decoder.read(archive, 512).getWidth());
            assertEquals(4, decoder.read(archive, 512).getHeight());
            assertEquals(2, decoder.readCover(archive, 512).getHeight());
            assertArrayEquals(bytes, Files.readAllBytes(archive));
            try (var out = new ZipOutputStream(Files.newOutputStream(archive))) {
                out.putNextEntry(new ZipEntry("frame.png"));
                byte[] buffer = new byte[1024 * 1024];
                for (int i = 0; i < 33; i++) out.write(buffer);
            }
            assertThrows(java.io.IOException.class, () -> decoder.read(archive, 512));
            assertCacheEmpty();
            verifyNoInteractions(runner);
        } finally {
            if (before == null) System.clearProperty(RuntimeFiles.DATA_DIR_PROPERTY);
            else System.setProperty(RuntimeFiles.DATA_DIR_PROPERTY, before);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"ordered", "reversed", "directory-first"})
    void selectsTheFirstFrameByFileName(String order) throws Exception {
        Path archive = directory.resolve("animation.zip");
        Path selected = directory.resolve("selected.png");
        var image = new BufferedImage(12, 10, BufferedImage.TYPE_INT_RGB);
        image.setRGB(3, 5, 0xcc1122);
        var frame = new java.io.ByteArrayOutputStream();
        ImageIO.write(image, "png", frame);
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            if (order.equals("directory-first")) {
                zip.putNextEntry(new ZipEntry("frames/"));
                zip.closeEntry();
            }
            for (int page : order.equals("reversed") ? new int[]{1, 0} : new int[]{0, 1}) {
                zip.putNextEntry(new ZipEntry(page == 0 ? "000000.png" : "000001.png"));
                if (page == 0) zip.write(frame.toByteArray());
                else {
                    image.setRGB(3, 5, 0x2233dd);
                    ImageIO.write(image, "png", zip);
                }
                zip.closeEntry();
            }
        }
        ZipPreviewFrame.copy(archive, selected, frame.size());
        assertArrayEquals(frame.toByteArray(), Files.readAllBytes(selected));
        assertThrows(java.io.IOException.class, () -> ZipPreviewFrame.copy(archive, selected, frame.size() - 1L));
    }

    @Test
    void readsOnlyTheSelectedFrameAndBoundsTheIndex() throws Exception {
        Path archive = directory.resolve("many.zip");
        Path selected = directory.resolve("frame");
        byte[] pixel = {42};
        for (int entries : new int[]{500, 501}) {
            try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
                zip.putNextEntry(new ZipEntry("000001.png"));
                zip.write(new byte[64 * 1024]);
                zip.closeEntry();
                for (int i = 2; i < entries; i++) {
                    zip.putNextEntry(new ZipEntry("directory-" + i + "/"));
                    zip.closeEntry();
                }
                zip.putNextEntry(new ZipEntry("000000.png"));
                zip.write(pixel);
                zip.closeEntry();
            }
            if (entries == 500) {
                ZipPreviewFrame.copy(archive, selected, 1);
                assertArrayEquals(pixel, Files.readAllBytes(selected));
                Thread.currentThread().interrupt();
                try { assertThrows(java.io.InterruptedIOException.class, () -> ZipPreviewFrame.copy(archive, selected, 1)); }
                finally { Thread.interrupted(); }
            } else {
                assertThrows(java.io.IOException.class, () -> ZipPreviewFrame.copy(archive, selected, 1));
            }
        }
    }

    @Test
    void ignoresUnselectedFrameDataAndChecksTheSelectedFrameCrc() throws Exception {
        Path archive = directory.resolve("stored.zip");
        Path selected = directory.resolve("frame");
        var offsets = new java.util.ArrayList<Integer>();
        var bytes = new java.io.ByteArrayOutputStream();
        byte[] data = {1, 2, 3};
        var checksum = new CRC32();
        checksum.update(data);
        try (var zip = new ZipOutputStream(bytes)) {
            for (String name : new String[]{"b.jpg", "a.jpg"}) {
                var entry = new ZipEntry(name);
                entry.setMethod(ZipEntry.STORED);
                entry.setSize(data.length);
                entry.setCrc(checksum.getValue());
                zip.putNextEntry(entry);
                offsets.add(bytes.size());
                zip.write(data);
                zip.closeEntry();
            }
        }
        byte[] contents = bytes.toByteArray();
        contents[offsets.getFirst()] ^= 1;
        Files.write(archive, contents);
        ZipPreviewFrame.copy(archive, selected, data.length);
        assertArrayEquals(data, Files.readAllBytes(selected));
        contents[offsets.getLast()] ^= 1;
        Files.write(archive, contents);
        assertThrows(ZipException.class, () -> ZipPreviewFrame.copy(archive, selected, data.length));
    }

    @Test
    void acceptsForceZip64AndRejectsDirectoryOnlyArchive() throws Exception {
        Path archive = directory.resolve("zip64.zip");
        Path selected = directory.resolve("frame");
        try (var zip = FileSystems.newFileSystem(archive, java.util.Map.of("create", "true", "forceZIP64End", "true"))) {
            Files.createDirectory(zip.getPath("/frames"));
        }
        assertThrows(java.io.IOException.class, () -> ZipPreviewFrame.copy(archive, selected, 1));
        try (var zip = FileSystems.newFileSystem(archive, java.util.Map.of("forceZIP64End", "true"))) {
            Files.write(zip.getPath("/1.png"), new byte[]{1});
            Files.write(zip.getPath("/0.png"), new byte[]{0});
        }
        ZipPreviewFrame.copy(archive, selected, 1);
        assertArrayEquals(new byte[]{0}, Files.readAllBytes(selected));
    }

    private void assertCacheEmpty() {
        // Windows 删除成功后可能短暂枚举 .tmp 条目；等待目录收敛，不主动清理被测结果。
        org.awaitility.Awaitility.await().pollDelay(java.time.Duration.ZERO)
                .pollInterval(java.time.Duration.ofMillis(25)).atMost(java.time.Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    try (var entries = Files.list(RuntimeFiles.galleryThumbnailDirectory())) {
                        assertEquals(java.util.List.of(), entries.toList());
                    }
                });
    }

    @Test @DisplayName("缩略图取消后不启动 FFmpeg 兜底")
    void doesNotFallbackAfterCancellation() throws Exception {
        Path source = directory.resolve("source.png");
        ImageIO.write(new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB), "png", source.toFile());
        var runner = mock(FfmpegRunner.class);
        var decoder = new ArtworkMediaDecoder(runner, JsonMapper.builderWithJackson2Defaults().build());
        Thread.currentThread().interrupt();
        try {
            assertThrows(java.io.InterruptedIOException.class, () -> decoder.read(source, 16));
            verifyNoInteractions(runner);
        } finally {
            Thread.interrupted();
        }
    }
}
