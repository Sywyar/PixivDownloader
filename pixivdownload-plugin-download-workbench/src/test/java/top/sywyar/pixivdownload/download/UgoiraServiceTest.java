package top.sywyar.pixivdownload.download;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.core.ffmpeg.FfmpegCommandResolver;
import top.sywyar.pixivdownload.core.ffmpeg.ResolvedFfmpegCommand;
import top.sywyar.pixivdownload.core.pixiv.PixivImageDownloader;
import top.sywyar.pixivdownload.download.request.DownloadRequest;
import top.sywyar.pixivdownload.download.testsupport.WorkbenchTestMessages;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Ugoira 稳定宿主端口")
class UgoiraServiceTest {

    @TempDir
    Path tempDir;

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
}
