package top.sywyar.pixivdownload.download;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.core.ffmpeg.FfmpegCommandResolver;
import top.sywyar.pixivdownload.core.ffmpeg.FfmpegRunner;
import top.sywyar.pixivdownload.core.ffmpeg.ResolvedFfmpegCommand;
import top.sywyar.pixivdownload.core.pixiv.PixivImageDownloader;
import top.sywyar.pixivdownload.download.request.DownloadRequest;
import top.sywyar.pixivdownload.download.testsupport.WorkbenchTestMessages;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Ugoira 稳定宿主端口")
class UgoiraServiceTest {
    private final top.sywyar.pixivdownload.download.media.MemoryMediaStore mediaStore = new top.sywyar.pixivdownload.download.media.MemoryMediaStore();

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("长作品目录从可启动的父目录运行子进程")
    void launchesProcessForLongArtworkDirectory() throws Exception {
        Path artwork = tempDir.toAbsolutePath();
        while (artwork.toString().length() < 300) artwork = artwork.resolve("directory-123456789");
        Files.createDirectories(artwork);
        Path working = UgoiraService.ffmpegWorkingDirectory(artwork);
        assertThat(working.resolve(working.relativize(artwork))).isEqualTo(artwork);
        boolean windows = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).startsWith("windows");
        Path executable = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
        Process process = new ProcessBuilder(executable.toString(), "-version").directory(working.toFile())
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try {
            assertThat(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isZero();
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    @Test
    @DisplayName("Ugoira 资源预算保持为显式固定值")
    void resourceBudgetsRemainExplicit() {
        assertThat(UgoiraService.MAX_ZIP_BYTES).isEqualTo(100L * 1024 * 1024);
        assertThat(UgoiraService.MAX_ZIP_ENTRIES).isEqualTo(500);
        assertThat(UgoiraService.MAX_ZIP_ENTRY_BYTES).isEqualTo(32L * 1024 * 1024);
        assertThat(UgoiraService.MAX_ZIP_UNCOMPRESSED_BYTES).isEqualTo(200L * 1024 * 1024);
        assertThat(UgoiraService.MAX_FRAME_COUNT).isEqualTo(500);
        assertThat(UgoiraService.MAX_FRAME_PIXELS).isEqualTo(25_000_000L);
        assertThat(UgoiraService.FFMPEG_TIMEOUT).isEqualTo(Duration.ofMinutes(10));
        assertThat(UgoiraService.MAX_FFMPEG_OUTPUT_BYTES).isEqualTo(100L * 1024 * 1024);
    }

    @Test
    @DisplayName("ZIP 下载应委托图片端口并映射累计进度")
    void zipDownloadDelegatesToImagePortAndMapsProgress() throws IOException {
        byte[] payload = {1, 2, 3, 4};
        AtomicReference<URI> source = new AtomicReference<>();
        AtomicReference<URI> referer = new AtomicReference<>();
        AtomicReference<Path> target = new AtomicReference<>();
        AtomicReference<String> cookie = new AtomicReference<>();
        PixivImageDownloader downloader = (sourceUri, refererUri, targetPath, credential, observer) -> {
            source.set(sourceUri);
            referer.set(refererUri);
            target.set(targetPath);
            cookie.set(credential);
            observer.onContentLength(payload.length);
            observer.onBytesTransferred(0);
            Files.write(targetPath, payload);
            observer.onBytesTransferred(payload.length);
            return true;
        };
        TestUgoiraService service = service(downloader, fallbackResolver());
        Path zipPath = tempDir.resolve("_ugoira_frames.zip");
        List<UgoiraProgress> progress = new ArrayList<>();

        boolean downloaded = service.downloadZip(
                "https://public-img-zip.pximg.net/img-zip-ugoira/test.zip",
                zipPath,
                " ",
                "PHPSESSID=credential",
                2,
                3,
                progress::add,
                () -> false
        );

        assertThat(downloaded).isTrue();
        assertThat(source.get())
                .isEqualTo(URI.create("https://public-img-zip.pximg.net/img-zip-ugoira/test.zip"));
        assertThat(referer.get()).isEqualTo(URI.create("https://www.pixiv.net/"));
        assertThat(target.get()).isEqualTo(zipPath);
        assertThat(cookie.get()).isEqualTo("PHPSESSID=credential");
        assertThat(Files.readAllBytes(zipPath)).containsExactly(payload);
        assertThat(progress)
                .extracting(UgoiraProgress::getStatus)
                .containsExactly(UgoiraProgress.STATUS_RUNNING, UgoiraProgress.STATUS_COMPLETED);
        assertThat(progress)
                .extracting(UgoiraProgress::getZipProgress)
                .containsExactly(99, 100);
        assertThat(progress)
                .extracting(UgoiraProgress::getZipDownloadedBytes)
                .containsExactly(4L, 4L);
        assertThat(service.retryDelays()).isEmpty();
    }

    @Test
    @DisplayName("端口明确拒绝应由 Ugoira 即时重试")
    void rejectedTransfersRemainImmediatePluginOwnedRetries() {
        AtomicInteger calls = new AtomicInteger();
        PixivImageDownloader downloader = (source, referer, target, cookie, observer) -> {
            calls.incrementAndGet();
            return false;
        };
        TestUgoiraService service = service(downloader, fallbackResolver());

        assertThat(service.downloadZip(
                "https://public-img-zip.pximg.net/img-zip-ugoira/retry.zip",
                tempDir.resolve("_ugoira_frames.zip"),
                "https://www.pixiv.net/artworks/100",
                null,
                1,
                3,
                null,
                () -> false
        )).isFalse();
        assertThat(calls).hasValue(3);
        assertThat(service.retryDelays()).isEmpty();
    }

    @Test
    @DisplayName("瞬时异常应由 Ugoira 保序退避重试")
    void failedTransfersRemainBackedOffPluginOwnedRetries() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        PixivImageDownloader downloader = (source, referer, target, cookie, observer) -> {
            int call = calls.incrementAndGet();
            if (call < 3) {
                throw new IOException("temporary transfer failure");
            }
            Files.write(target, new byte[]{9});
            observer.onContentLength(1);
            observer.onBytesTransferred(1);
            return true;
        };
        TestUgoiraService service = service(downloader, fallbackResolver());

        assertThat(service.downloadZip(
                "https://public-img-zip.pximg.net/img-zip-ugoira/retry.zip",
                tempDir.resolve("_ugoira_frames.zip"),
                "https://www.pixiv.net/artworks/100",
                null,
                1,
                3,
                null,
                () -> false
        )).isTrue();
        assertThat(calls).hasValue(3);
        assertThat(service.retryDelays()).containsExactly(2000L, 4000L);
    }

    @Test
    @DisplayName("观察器取消应原样传播并清理 Ugoira 临时文件")
    void observerCancellationPropagatesAndCleansTemporaryFiles() throws IOException {
        PixivImageDownloader downloader = (source, referer, target, cookie, observer) -> {
            Files.write(target, new byte[]{1, 2});
            observer.checkCancelled();
            return true;
        };
        TestUgoiraService service = service(downloader, fallbackResolver());
        Path framesDir = tempDir.resolve("_frames_tmp");
        Files.createDirectories(framesDir);
        Files.writeString(framesDir.resolve("stale-frame.jpg"), "stale");
        DownloadRequest.Other other = new DownloadRequest.Other();
        other.setUgoira(true);
        other.setUgoiraZipUrl("https://public-img-zip.pximg.net/img-zip-ugoira/cancel.zip");
        AtomicInteger cancellationChecks = new AtomicInteger();
        BooleanSupplier cancellation = () -> cancellationChecks.incrementAndGet() >= 3;

        assertThatThrownBy(() -> service.processUgoira(
                100L,
                other,
                tempDir,
                "https://www.pixiv.net/artworks/100",
                null,
                null,
                cancellation
        )).isInstanceOf(CancellationException.class);
        assertThat(tempDir.resolve("_ugoira_frames.zip")).doesNotExist();
        assertThat(framesDir).doesNotExist();
    }

    @Test
    @DisplayName("ZIP 下载超过固定上限时立即终止且不重试")
    void oversizedZipStopsWithoutRetry() {
        AtomicInteger calls = new AtomicInteger();
        PixivImageDownloader downloader = (source, referer, target, cookie, observer) -> {
            calls.incrementAndGet();
            observer.onContentLength(UgoiraService.MAX_ZIP_BYTES + 1);
            return true;
        };
        TestUgoiraService service = service(downloader, fallbackResolver());

        assertThatThrownBy(() -> service.downloadZip(
                "https://public-img-zip.pximg.net/img-zip-ugoira/oversized.zip",
                tempDir.resolve("_ugoira_frames.zip"),
                "https://www.pixiv.net/artworks/100",
                null,
                1,
                3,
                null,
                () -> false
        )).isInstanceOf(RuntimeException.class);
        assertThat(calls).hasValue(1);
    }

    @Test
    @DisplayName("解压字节超限的 ZIP 在启动 ffmpeg 前终止并清理")
    void zipBombStopsBeforeFfmpegAndCleansTemporaryFiles() throws IOException {
        byte[] archive = zip("000000.jpg", new byte[Math.toIntExact(UgoiraService.MAX_ZIP_ENTRY_BYTES + 1)]);
        AtomicInteger resolverCalls = new AtomicInteger();
        TestUgoiraService service = service(
                archiveDownloader(archive),
                () -> {
                    resolverCalls.incrementAndGet();
                    return new ResolvedFfmpegCommand("ffmpeg", ResolvedFfmpegCommand.Source.FALLBACK);
                }
        );

        assertThat(service.processUgoira(
                100L,
                ugoiraRequest("zip-bomb"),
                tempDir,
                "https://www.pixiv.net/artworks/100",
                null
        )).isZero();
        assertThat(resolverCalls).hasValue(0);
        assertThat(tempDir.resolve("_ugoira_frames.zip")).doesNotExist();
        assertThat(tempDir.resolve("_frames_tmp")).doesNotExist();
        assertThat(tempDir.resolve("zip-bomb.webp.part")).doesNotExist();
    }

    @Test
    @DisplayName("合法高压缩比 PNG 帧可以通过解压校验进入转码")
    void highlyCompressiblePngReachesFfmpeg() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(6000, 4000, BufferedImage.TYPE_INT_RGB), "png", bytes);
        byte[] archive = zip("000000.png", bytes.toByteArray());
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry = input.getNextEntry();
            input.transferTo(java.io.OutputStream.nullOutputStream());
            input.closeEntry();
            assertThat(entry.getSize()).isGreaterThan(entry.getCompressedSize() * 100);
        }
        AtomicInteger resolverCalls = new AtomicInteger();
        TestUgoiraService service = service(archiveDownloader(archive), () -> {
            resolverCalls.incrementAndGet();
            throw new CancellationException("stop after validated frames reach the encoder");
        });

        assertThatThrownBy(() -> service.processUgoira(100L, ugoiraRequest("solid-frame"), tempDir,
                "https://www.pixiv.net/artworks/100", null)).isInstanceOf(CancellationException.class);
        assertThat(resolverCalls).hasValue(1);
        assertThat(tempDir.resolve("_frames_tmp")).doesNotExist();
        assertThat(tempDir.resolve("solid-frame.webp.part")).doesNotExist();
    }

    @Test
    @DisplayName("Ugoira 排队取消仅报告等待，不启动编码或发布成品")
    void cancelledUgoiraWaitDoesNotReportEncoding() throws Exception {
        var progress = new ArrayList<UgoiraProgress>();
        var service = new UgoiraService(archiveDownloader(zip("000000.jpg", jpegFrame())),
                (ProgressRunner) (tool, args, cwd, output, max, timeout, cancelled, phases, lines) -> {
                    phases.accept(FfmpegRunner.Phase.WAITING);
                    assertThat(progress.get(progress.size() - 1).getPhase()).isEqualTo("ffmpeg-waiting");
                    throw new CancellationException("cancel while waiting");
                }, WorkbenchTestMessages.messages(), mediaStore);
        assertThatThrownBy(() -> service.processUgoira(100L, ugoiraRequest("waiting"), tempDir,
                "https://www.pixiv.net/artworks/100", null, progress::add, () -> false))
                .isInstanceOf(CancellationException.class);
        assertThat(progress).filteredOn(p -> "ffmpeg-waiting".equals(p.getPhase())).singleElement()
                .satisfies(p -> {
                    assertThat(p.getFfmpegProgress()).isNull();
                    assertThat(p.getOutputFormat()).isEqualTo("webp");
                    assertThat(p.getOutputIndex()).isEqualTo(1);
                    assertThat(p.getOutputCount()).isEqualTo(1);
                });
        assertThat(progress).noneMatch(p -> "ffmpeg".equals(p.getPhase()));
        assertThat(tempDir.resolve("waiting.webp")).doesNotExist();
    }

    @Test
    @DisplayName("超大像素帧在启动 ffmpeg 前终止并清理")
    void oversizedFrameStopsBeforeFfmpegAndCleansTemporaryFiles() throws IOException {
        byte[] archive = zip("000000.png", pngWithDimensions(10_000, 5_000));
        AtomicInteger resolverCalls = new AtomicInteger();
        TestUgoiraService service = service(
                archiveDownloader(archive),
                () -> {
                    resolverCalls.incrementAndGet();
                    return new ResolvedFfmpegCommand("ffmpeg", ResolvedFfmpegCommand.Source.FALLBACK);
                }
        );

        assertThat(service.processUgoira(
                100L,
                ugoiraRequest("oversized-frame"),
                tempDir,
                "https://www.pixiv.net/artworks/100",
                null
        )).isZero();
        assertThat(resolverCalls).hasValue(0);
        assertThat(tempDir.resolve("_ugoira_frames.zip")).doesNotExist();
        assertThat(tempDir.resolve("_frames_tmp")).doesNotExist();
        assertThat(tempDir.resolve("oversized-frame.webp.part")).doesNotExist();
    }

    @Test
    @DisplayName("扩展名与实际帧格式不一致时在启动 ffmpeg 前终止")
    void mismatchedFrameFormatStopsBeforeFfmpeg() throws IOException {
        byte[] archive = zip("000000.jpg", imageFrame("gif"));
        AtomicInteger resolverCalls = new AtomicInteger();
        TestUgoiraService service = service(
                archiveDownloader(archive),
                () -> {
                    resolverCalls.incrementAndGet();
                    return new ResolvedFfmpegCommand("ffmpeg", ResolvedFfmpegCommand.Source.FALLBACK);
                }
        );

        assertThat(service.processUgoira(
                100L,
                ugoiraRequest("mismatched-frame"),
                tempDir,
                "https://www.pixiv.net/artworks/100",
                null
        )).isZero();
        assertThat(resolverCalls).hasValue(0);
    }

    @Test
    @DisplayName("宿主编码失败后清理实际格式的部分文件，保留诊断并报告失败")
    void runnerFailureCleansEveryFormat() throws Exception {
        for (String format : List.of("webp", "gif", "apng", "mp4")) {
            var progress = new ArrayList<UgoiraProgress>();
            ProgressRunner runner = (tool, args, cwd, output, max, timeout, cancelled, phases, lines) -> {
                assertThat(tool).isEqualTo(FfmpegRunner.Tool.FFMPEG);
                assertThat(args).contains("-progress", "pipe:1");
                assertThat(output.getFileName().toString()).isEqualTo("failed." + format + ".part");
                assertThat(max).isEqualTo(UgoiraService.MAX_FFMPEG_OUTPUT_BYTES);
                assertThat(timeout).isEqualTo(UgoiraService.FFMPEG_TIMEOUT);
                phases.accept(FfmpegRunner.Phase.WAITING);
                phases.accept(FfmpegRunner.Phase.RUNNING);
                lines.accept("out_time_ms=50000");
                Files.writeString(output, "partial");
                throw new IOException("encoder diagnostic");
            };
            var service = new UgoiraService(archiveDownloader(zip("000000.jpg", jpegFrame())),
                    runner, WorkbenchTestMessages.messages(), mediaStore);
            var request = ugoiraRequest("failed");
            request.setUgoiraFormats(format);
            assertThat(service.processUgoira(100L, request, tempDir, null, null, progress::add)).isZero();
            assertThat(progress).anyMatch(value -> Integer.valueOf(50).equals(value.getFfmpegProgress()));
            assertThat(progress.get(progress.size() - 1).getStatus()).isEqualTo(UgoiraProgress.STATUS_FAILED);
            assertThat(tempDir.resolve("failed." + format + ".part")).doesNotExist();
            assertThat(tempDir.resolve("failed." + format)).doesNotExist();
            assertThat(tempDir.resolve("_frames_tmp")).doesNotExist();
        }
    }

    private TestUgoiraService service(
            PixivImageDownloader downloader,
            FfmpegCommandResolver resolver
    ) {
        return new TestUgoiraService(downloader, resolver);
    }

    @Test
    @DisplayName("真实编码生成全部选定动画、保留原 ZIP 时序并写出 JPEG 缩略图")
    void realMultiFormatOutputsPreserveTimingAndZip() throws Exception {
        Process availability;
        try {
            availability = new ProcessBuilder("ffmpeg", "-version")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException absent) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "FFmpeg required");
            return;
        }
        assertThat(availability.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream archive = new ZipOutputStream(bytes)) {
            for (int i = 0; i < 2; i++) {
                BufferedImage frame = new BufferedImage(17, 13, BufferedImage.TYPE_INT_ARGB);
                frame.setRGB(8, 6, i == 0 ? 0xFFFF0000 : 0xFF0000FF);
                archive.putNextEntry(new ZipEntry("00000" + i + ".png"));
                assertThat(ImageIO.write(frame, "png", archive)).isTrue();
                archive.closeEntry();
            }
        }
        byte[] originalZip = bytes.toByteArray();
        var request = ugoiraRequest("animation");
        request.setUgoiraDelays(List.of(100, 150));
        request.setUgoiraFormats("webp,gif,apng,mp4,zip");
        var service = service(archiveDownloader(originalZip), fallbackResolver());
        assertThat(service.processUgoira(100L, request, tempDir, null, null)).isEqualTo(1);
        assertThat(Files.readAllBytes(tempDir.resolve("animation.zip"))).isEqualTo(originalZip);
        var timing = new java.util.Properties();
        try (var reader = Files.newBufferedReader(tempDir.resolve("animation.frames.properties"), java.nio.charset.StandardCharsets.UTF_8)) {
            timing.load(reader);
        }
        assertThat(timing.getProperty("000001.png")).isEqualTo("150");
        var manifest = mediaStore.find(100L, 0).orElseThrow();
        assertThat(manifest.extensions()).containsExactly("webp", "gif", "apng", "mp4", "zip");
        assertThat(ImageIO.read(tempDir.resolve("animation_thumb.jpg").toFile())).isNotNull();
        for (String format : List.of("gif", "apng", "mp4")) {
            Process probe = new ProcessBuilder("ffprobe", "-v", "error", "-show_entries", "packet=duration_time",
                    "-of", "csv=p=0", tempDir.resolve("animation." + format).toString())
                    .redirectErrorStream(true).start();
            String result = new String(probe.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(probe.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(probe.exitValue()).as(result).isZero();
            double duration = result.lines().filter(line -> !line.isBlank()).mapToDouble(Double::parseDouble).sum();
            assertThat(duration).as(format + " timing").isBetween(0.23, 0.27);
        }
        String webp = new String(Files.readAllBytes(tempDir.resolve("animation.webp")), java.nio.charset.StandardCharsets.ISO_8859_1);
        assertThat(webp).startsWith("RIFF").contains("ANIM").contains("ANMF");
        byte[] webpBytes = Files.readAllBytes(tempDir.resolve("animation.webp"));
        int animationDuration = 0;
        for (int offset = 12; offset + 8 <= webpBytes.length;) {
            int size = ByteBuffer.wrap(webpBytes, offset + 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt();
            String chunk = new String(webpBytes, offset, 4, java.nio.charset.StandardCharsets.US_ASCII);
            if (chunk.equals("ANMF")) {
                int delayOffset = offset + 20;
                animationDuration += (webpBytes[delayOffset] & 255) | (webpBytes[delayOffset + 1] & 255) << 8
                        | (webpBytes[delayOffset + 2] & 255) << 16;
            }
            offset += 8 + size + (size & 1);
        }
        assertThat(animationDuration).as("WebP timing").isBetween(230, 270);
        byte[] existingGif = Files.readAllBytes(tempDir.resolve("animation.gif"));
        Files.delete(tempDir.resolve("animation.mp4"));
        var offline = service((source, referer, target, cookie, observer) -> {
            throw new AssertionError("Historical conversion must not download");
        }, fallbackResolver());
        assertThat(offline.addMissingFormats(100L, tempDir.resolve("animation.webp"), "gif,mp4", () -> false)).isTrue();
        assertThat(tempDir.resolve("animation.mp4")).isNotEmptyFile();
        assertThat(Files.readAllBytes(tempDir.resolve("animation.gif"))).isEqualTo(existingGif);
        assertThat(Files.readAllBytes(tempDir.resolve("animation.zip"))).isEqualTo(originalZip);
        assertThat(mediaStore.find(100L, 0)
                .orElseThrow().extensions()).containsExactly("webp", "gif", "apng", "mp4", "zip");
    }

    @Test
    @DisplayName("编码失败仍保留用户选择的原始帧包与时序")
    void failedEncodingKeepsSelectedArchive() throws Exception {
        byte[] original = zip("000000.jpg", jpegFrame());
        var request = ugoiraRequest("retained");
        request.setUgoiraFormats("zip,webp");
        var service = service(archiveDownloader(original),
                () -> new ResolvedFfmpegCommand(tempDir.resolve("missing-ffmpeg").toString(), ResolvedFfmpegCommand.Source.FALLBACK));
        assertThat(service.processUgoira(100L, request, tempDir, null, null)).isZero();
        assertThat(Files.readAllBytes(tempDir.resolve("retained.zip"))).isEqualTo(original);
        assertThat(tempDir.resolve("retained.frames.properties")).isNotEmptyFile();
        assertThat(tempDir.resolve("retained.webp")).doesNotExist();
    }

    @Test
    @DisplayName("动图媒体记录保存失败不计成功，已选择的原始 ZIP 与时序仍保留")
    void metadataFailureKeepsSelectedArchive() throws Exception {
        var failedStore = org.mockito.Mockito.mock(top.sywyar.pixivdownload.core.asset.ArtworkMediaStore.class);
        org.mockito.Mockito.doThrow(new IOException("database unavailable")).when(failedStore)
                .save(org.mockito.ArgumentMatchers.eq(100L), org.mockito.ArgumentMatchers.eq(0), org.mockito.ArgumentMatchers.any());
        var service = new UgoiraService(archiveDownloader(zip("000000.jpg", jpegFrame())),
                testRunner(fallbackResolver()), WorkbenchTestMessages.messages(), failedStore);
        var request = ugoiraRequest("archive");
        request.setUgoiraFormats("zip");
        assertThat(service.processUgoira(100L, request, tempDir, null, null)).isZero();
        assertThat(tempDir.resolve("archive.zip")).isNotEmptyFile();
        assertThat(tempDir.resolve("archive.frames.properties")).isNotEmptyFile();
        assertThat(tempDir.resolve("archive.media.properties")).doesNotExist();
    }

    private static FfmpegCommandResolver fallbackResolver() {
        return () -> new ResolvedFfmpegCommand(
                "ffmpeg",
                ResolvedFfmpegCommand.Source.FALLBACK
        );
    }

    private static PixivImageDownloader archiveDownloader(byte[] archive) {
        return (source, referer, target, cookie, observer) -> {
            observer.onContentLength(archive.length);
            Files.write(target, archive);
            observer.onBytesTransferred(archive.length);
            return true;
        };
    }

    private static DownloadRequest.Other ugoiraRequest(String outputBaseName) {
        DownloadRequest.Other other = new DownloadRequest.Other();
        other.setUgoira(true);
        other.setUgoiraZipUrl("https://public-img-zip.pximg.net/img-zip-ugoira/test.zip");
        other.setUgoiraDelays(List.of(100));
        other.setFileNames(List.of(outputBaseName));
        return other;
    }

    private static byte[] zip(String name, byte[] contents) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(contents);
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static byte[] jpegFrame() throws IOException {
        return imageFrame("jpg");
    }

    private static byte[] imageFrame(String format) throws IOException {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, format, bytes)).isTrue();
        return bytes.toByteArray();
    }

    private static byte[] pngWithDimensions(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, "png", bytes)).isTrue();
        byte[] png = bytes.toByteArray();
        ByteBuffer.wrap(png, 16, 4).putInt(width);
        ByteBuffer.wrap(png, 20, 4).putInt(height);
        CRC32 crc = new CRC32();
        crc.update(png, 12, 17);
        ByteBuffer.wrap(png, 29, 4).putInt((int) crc.getValue());
        return png;
    }

    private final class TestUgoiraService extends UgoiraService {
        private final List<Long> retryDelays = new ArrayList<>();

        private TestUgoiraService(
                PixivImageDownloader downloader,
                FfmpegCommandResolver resolver
        ) {
            super(downloader, testRunner(resolver), WorkbenchTestMessages.messages(), mediaStore);
        }

        @Override
        void sleepCancellable(long millis, BooleanSupplier cancellationRequested) {
            retryDelays.add(millis);
            if (cancellationRequested != null && cancellationRequested.getAsBoolean()) {
                throw new CancellationException("download cancelled");
            }
        }

        private List<Long> retryDelays() {
            return List.copyOf(retryDelays);
        }
    }

    @FunctionalInterface
    private interface ProgressRunner extends FfmpegRunner {
        @Override
        default String run(Tool tool, List<String> args, Path cwd, Path output, long maximum,
                           Duration timeout, BooleanSupplier cancelled, java.util.function.Consumer<Phase> phases) throws IOException {
            return run(tool, args, cwd, output, maximum, timeout, cancelled, phases, null);
        }
        @Override
        String run(Tool tool, List<String> args, Path cwd, Path output, long maximum, Duration timeout,
                   BooleanSupplier cancelled, java.util.function.Consumer<Phase> phases,
                   java.util.function.Consumer<String> lines) throws IOException;
    }

    // 真实编码测试只提供进程夹具；宿主的取消、预算和诊断由 Runner 的测试验证。
    private static FfmpegRunner testRunner(FfmpegCommandResolver resolver) {
        return (ProgressRunner) (tool, args, cwd, output, maximum, timeout, cancelled, phases, lines) -> {
            var command = new ArrayList<String>();
            command.add(resolver.resolve().command());
            command.addAll(args);
            Path diagnostics = Files.createTempFile("ugoira-test-", ".log");
            Process process = null;
            try {
                if (phases != null) phases.accept(FfmpegRunner.Phase.WAITING);
                process = new ProcessBuilder(command).directory(cwd.toFile()).redirectError(diagnostics.toFile()).start();
                if (phases != null) phases.accept(FfmpegRunner.Phase.RUNNING);
                try (var reader = process.inputReader(java.nio.charset.StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = reader.readLine()) != null) if (lines != null) lines.accept(line);
                }
                if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) throw new IOException("Fixture timed out");
                String diagnostic = Files.readString(diagnostics);
                if (process.exitValue() != 0) throw new IOException(diagnostic);
                return diagnostic;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new CancellationException();
            } finally {
                if (process != null && process.isAlive()) process.destroyForcibly();
                Files.deleteIfExists(diagnostics);
            }
        };
    }
}
