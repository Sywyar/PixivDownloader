package top.sywyar.pixivdownload.download;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.core.ffmpeg.FfmpegCommandResolver;
import top.sywyar.pixivdownload.core.ffmpeg.ResolvedFfmpegCommand;
import top.sywyar.pixivdownload.core.pixiv.PixivImageDownloader;
import top.sywyar.pixivdownload.download.request.DownloadRequest;
import top.sywyar.pixivdownload.download.testsupport.WorkbenchTestMessages;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
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
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Ugoira 稳定宿主端口")
class UgoiraServiceTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Ugoira 资源预算保持为显式固定值")
    void resourceBudgetsRemainExplicit() {
        assertThat(UgoiraService.MAX_ZIP_BYTES).isEqualTo(100L * 1024 * 1024);
        assertThat(UgoiraService.MAX_ZIP_ENTRIES).isEqualTo(500);
        assertThat(UgoiraService.MAX_ZIP_ENTRY_BYTES).isEqualTo(32L * 1024 * 1024);
        assertThat(UgoiraService.MAX_ZIP_UNCOMPRESSED_BYTES).isEqualTo(200L * 1024 * 1024);
        assertThat(UgoiraService.MAX_ZIP_COMPRESSION_RATIO).isEqualTo(100L);
        assertThat(UgoiraService.MAX_FRAME_COUNT).isEqualTo(500);
        assertThat(UgoiraService.MAX_FRAME_PIXELS).isEqualTo(25_000_000L);
        assertThat(UgoiraService.FFMPEG_TIMEOUT).isEqualTo(Duration.ofMinutes(10));
        assertThat(UgoiraService.MAX_FFMPEG_OUTPUT_BYTES).isEqualTo(100L * 1024 * 1024);
        assertThat(UgoiraService.MAX_FFMPEG_PROCESSES).isEqualTo(1);
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
    @DisplayName("高压缩比 ZIP bomb 在启动 ffmpeg 前终止并清理")
    void zipBombStopsBeforeFfmpegAndCleansTemporaryFiles() throws IOException {
        byte[] archive = zip("000000.jpg", new byte[1024 * 1024]);
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
    @DisplayName("ffmpeg 超时后结束进程树并清理部分输出")
    void ffmpegTimeoutTerminatesProcessTreeAndCleansPartialOutput() throws Exception {
        Path childPidFile = tempDir.resolve("ffmpeg-child.pid");
        ProcessFixtureUgoiraService service = new ProcessFixtureUgoiraService(
                archiveDownloader(zip("000000.jpg", jpegFrame())),
                fallbackResolver(),
                childPidFile,
                Duration.ofSeconds(5).toNanos(),
                Long.MAX_VALUE
        );

        assertThat(service.processUgoira(
                100L,
                ugoiraRequest("ffmpeg-timeout"),
                tempDir,
                "https://www.pixiv.net/artworks/100",
                null
        )).isZero();

        assertThat(childPidFile).exists();
        long childPid = Long.parseLong(Files.readString(childPidFile).trim());
        assertThat(awaitProcessExit(childPid, Duration.ofSeconds(5))).isTrue();
        assertThat(tempDir.resolve("_ugoira_frames.zip")).doesNotExist();
        assertThat(tempDir.resolve("_frames_tmp")).doesNotExist();
        assertThat(tempDir.resolve("ffmpeg-timeout.webp.part")).doesNotExist();
        assertThat(tempDir.resolve("ffmpeg-timeout.webp")).doesNotExist();
        assertThat(tempDir.resolve("ffmpeg-timeout_thumb.jpg")).doesNotExist();
    }

    @Test
    @DisplayName("受控来源和回退来源均应保留宿主解析命令")
    void resolvedAndFallbackSourcesPreserveHostCommand() {
        for (ResolvedFfmpegCommand.Source source : ResolvedFfmpegCommand.Source.values()) {
            String command = "ffmpeg-" + source.name().toLowerCase();
            TestUgoiraService service = service(
                    (sourceUri, refererUri, target, cookie, observer) -> false,
                    () -> new ResolvedFfmpegCommand(command, source)
            );

            assertThat(service.detectFfmpegCommand()).isEqualTo(command);
        }
    }

    private static TestUgoiraService service(
            PixivImageDownloader downloader,
            FfmpegCommandResolver resolver
    ) {
        return new TestUgoiraService(downloader, resolver);
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

    private static boolean awaitProcessExit(long pid, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) == false) {
                return true;
            }
            Thread.sleep(50);
        }
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) == false;
    }

    private static final class TestUgoiraService extends UgoiraService {
        private final List<Long> retryDelays = new ArrayList<>();

        private TestUgoiraService(
                PixivImageDownloader downloader,
                FfmpegCommandResolver resolver
        ) {
            super(downloader, resolver, WorkbenchTestMessages.messages());
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

    private static final class ProcessFixtureUgoiraService extends UgoiraService {
        private final Path childPidFile;
        private final long timeoutNanos;
        private final long maximumOutputBytes;

        private ProcessFixtureUgoiraService(
                PixivImageDownloader downloader,
                FfmpegCommandResolver resolver,
                Path childPidFile,
                long timeoutNanos,
                long maximumOutputBytes
        ) {
            super(downloader, resolver, WorkbenchTestMessages.messages());
            this.childPidFile = childPidFile;
            this.timeoutNanos = timeoutNanos;
            this.maximumOutputBytes = maximumOutputBytes;
        }

        @Override
        Process startFfmpeg(ProcessBuilder processBuilder) throws IOException {
            List<String> command = processBuilder.command();
            Path partialOutput = Path.of(command.get(command.size() - 1));
            return new ProcessBuilder(
                    javaCommand(),
                    "-cp",
                    System.getProperty("java.class.path"),
                    ProcessTreeFixture.class.getName(),
                    childPidFile.toString(),
                    partialOutput.toString()
            ).start();
        }

        @Override
        long ffmpegTimeoutNanos() {
            return timeoutNanos;
        }

        @Override
        long maxFfmpegOutputBytes() {
            return maximumOutputBytes;
        }
    }

    public static final class ProcessTreeFixture {
        private ProcessTreeFixture() {
        }

        public static void main(String[] args) throws Exception {
            if (args.length == 1 && "child".equals(args[0])) {
                Thread.sleep(Duration.ofMinutes(1).toMillis());
                return;
            }
            Files.write(Path.of(args[1]), new byte[16]);
            Process child = new ProcessBuilder(
                    javaCommand(),
                    "-cp",
                    System.getProperty("java.class.path"),
                    ProcessTreeFixture.class.getName(),
                    "child"
            ).start();
            Files.writeString(Path.of(args[0]), Long.toString(child.pid()));
            Thread.sleep(Duration.ofMinutes(1).toMillis());
        }
    }

    private static String javaCommand() {
        String executable = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "java.exe"
                : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }
}
