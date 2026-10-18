package top.sywyar.pixivdownload.novel.export;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import top.sywyar.pixivdownload.config.DownloadSettings;
import top.sywyar.pixivdownload.core.work.service.WorkQueryService;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.novel.db.NovelRecord;
import top.sywyar.pixivdownload.novel.download.NovelDownloadService.NovelFormat;
import top.sywyar.pixivdownload.novel.testsupport.NovelTestMessages;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@DisplayName("小说系列合订")
class NovelMergeServiceTest {
    @TempDir Path root;
    private final NovelDatabase database = mock(NovelDatabase.class);
    private final DownloadSettings settings = mock(DownloadSettings.class);
    private final NovelMergeService service = new NovelMergeService(settings, database,
            mock(WorkQueryService.class), NovelTestMessages.messageResolver());

    @ParameterizedTest
    @EnumSource(NovelFormat.class)
    @DisplayName("空语言生成原文和全部变体，指定语言只更新对应文件")
    void preservesBaseAndVariantOutputs(NovelFormat format) throws Exception {
        when(settings.getRootFolder()).thenReturn(root.toString());
        NovelRecord chapter = mock(NovelRecord.class);
        when(chapter.novelId()).thenReturn(7L);
        when(chapter.title()).thenReturn("Chapter & title");
        when(chapter.rawContent()).thenReturn("Original body");
        when(database.getNovelsBySeriesId(42)).thenReturn(List.of(chapter));
        when(database.getSeriesTranslatedLangs(42)).thenReturn(List.of("en", "fr"));
        when(database.getTranslationContent(7, "en")).thenReturn("English body");
        when(database.getTranslationContent(7, "fr")).thenReturn("French body");

        var base = service.mergeVariant(42, format, "  ");
        assertThat(base.success()).isTrue();
        assertThat(base.chapterCount()).isEqualTo(1);
        Path baseFile = Path.of(base.mergedPath());
        Path english = baseFile.resolveSibling("42_en." + format.ext());
        Path french = baseFile.resolveSibling("42_fr." + format.ext());
        assertThat(body(baseFile, format)).contains("Original body");
        assertThat(body(english, format)).contains("English body");
        assertThat(body(french, format)).contains("French body");
        byte[] originalBytes = Files.readAllBytes(baseFile);
        byte[] frenchBytes = Files.readAllBytes(french);

        when(database.getTranslationContent(7, "en")).thenReturn("Updated English");
        var variant = service.mergeVariant(42, format, " en ");
        assertThat(Path.of(variant.mergedPath())).isEqualTo(english);
        assertThat(body(english, format)).contains("Updated English");
        assertThat(Files.readAllBytes(baseFile)).isEqualTo(originalBytes);
        assertThat(Files.readAllBytes(french)).isEqualTo(frenchBytes);
        verify(database, times(1)).getSeriesTranslatedLangs(42);
    }

    @Test
    @DisplayName("变体失败不阻止其它变体，直接变体失败仍向调用方传播")
    void preservesFailureBoundaries() throws Exception {
        when(settings.getRootFolder()).thenReturn(root.toString());
        NovelRecord chapter = mock(NovelRecord.class);
        when(chapter.novelId()).thenReturn(7L);
        when(chapter.rawContent()).thenReturn("Original body");
        when(database.getNovelsBySeriesId(42)).thenReturn(List.of(chapter));
        when(database.getSeriesTranslatedLangs(42)).thenReturn(List.of("broken", "en"));
        var failure = new IllegalStateException("translation unavailable");
        when(database.getTranslationContent(7, "broken")).thenThrow(failure);
        when(database.getTranslationContent(7, "en")).thenReturn("English body");
        assertThat(service.merge(42, NovelFormat.TXT).success()).isTrue();
        assertThat(Files.readString(root.resolve("novel-series-42/42_en.txt")))
                .contains("English body");
        assertThatThrownBy(() -> service.mergeVariant(42, NovelFormat.TXT, "broken"))
                .isSameAs(failure);
        assertThat(service.mergeVariant(42, NovelFormat.TXT, "en").success()).isTrue();
    }

    @Test
    @DisplayName("非法和空系列不生成文件，也不查询变体")
    void rejectsMissingSeriesWithoutWriting() throws Exception {
        assertThat(service.merge(-1, NovelFormat.TXT).success()).isFalse();
        assertThat(service.mergeVariant(42, NovelFormat.HTML, "en").success()).isFalse();
        verify(database, never()).getNovelsBySeriesId(-1);
        verify(database, never()).getSeriesTranslatedLangs(anyLong());
        verifyNoInteractions(settings);
        try (var files = Files.list(root)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    @DisplayName("同系列两个入口串行且跨系列可并行，失败后等待者仍能继续")
    void serializesBothEntrypointsAndReleasesAfterFailure() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var secondEntered = new CountDownLatch(1);
        var releaseSecond = new CountDownLatch(1);
        var firstThread = new AtomicReference<Thread>();
        var waiting = new AtomicReference<Thread>();
        var failure = new IllegalStateException("series unavailable");
        when(database.getNovelsBySeriesId(42)).thenAnswer(call -> {
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            throw failure;
        }).thenAnswer(call -> {
            secondEntered.countDown();
            assertThat(releaseSecond.await(5, TimeUnit.SECONDS)).isTrue();
            return List.of();
        }).thenReturn(List.of());
        var workers = Executors.newFixedThreadPool(3);
        try {
            var first = workers.submit(() -> {
                firstThread.set(Thread.currentThread());
                return service.merge(42, NovelFormat.TXT);
            });
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var second = workers.submit(() -> {
                waiting.set(Thread.currentThread());
                return service.mergeVariant(42, NovelFormat.HTML, "en");
            });
            awaitBlocked(waiting, firstThread.get());
            // 42 与 58 在初始并发表中会落入同一桶，文件工作不能在 map.compute 内执行。
            assertThat(workers.submit(() -> service.merge(58, NovelFormat.TXT))
                    .get(5, TimeUnit.SECONDS).success()).isFalse();
            release.countDown();
            assertThatThrownBy(() -> first.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class).hasCause(failure);
            assertThat(secondEntered.await(5, TimeUnit.SECONDS)).isTrue();
            Thread secondThread = waiting.getAndSet(null);
            var third = workers.submit(() -> {
                waiting.set(Thread.currentThread());
                return service.merge(42, NovelFormat.TXT);
            });
            awaitBlocked(waiting, secondThread);
            releaseSecond.countDown();
            assertThat(second.get(5, TimeUnit.SECONDS).success()).isFalse();
            assertThat(third.get(5, TimeUnit.SECONDS).success()).isFalse();
            assertThat(service.merge(42, NovelFormat.TXT).success()).isFalse();
        } finally {
            release.countDown();
            releaseSecond.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static void awaitBlocked(AtomicReference<Thread> waiting, Thread owner) {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            while (true) {
                Thread thread = waiting.get();
                var info = thread == null ? null : ManagementFactory.getThreadMXBean()
                        .getThreadInfo(thread.getId());
                if (info != null && info.getThreadState() == Thread.State.BLOCKED
                        && info.getLockOwnerId() == owner.getId()) return;
                Thread.sleep(1);
            }
        });
    }

    private static String body(Path file, NovelFormat format) throws Exception {
        if (format != NovelFormat.EPUB) return Files.readString(file, StandardCharsets.UTF_8);
        try (var zip = new ZipFile(file.toFile());
             var input = zip.getInputStream(zip.getEntry("OEBPS/chapter-1.xhtml"))) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
