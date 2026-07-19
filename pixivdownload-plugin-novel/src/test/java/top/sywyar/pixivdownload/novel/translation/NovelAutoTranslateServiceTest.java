package top.sywyar.pixivdownload.novel.translation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import top.sywyar.pixivdownload.ai.AiChatClient;
import top.sywyar.pixivdownload.plugin.api.download.queue.QueueGenerationDrain;
import top.sywyar.pixivdownload.plugin.api.download.queue.QueueTaskTracker;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import top.sywyar.pixivdownload.novel.download.NovelDownloadService;
import top.sywyar.pixivdownload.novel.download.NovelQueueTaskOwners;
import top.sywyar.pixivdownload.novel.export.NovelMergeService;

@DisplayName("下载即自动翻译服务端队列")
class NovelAutoTranslateServiceTest {

    private final NovelTranslationService translationService = mock(NovelTranslationService.class);
    private final NovelGlossaryService glossaryService = mock(NovelGlossaryService.class);
    private final NovelMergeService mergeService = mock(NovelMergeService.class);
    private final AiChatClient aiChatClient = mock(AiChatClient.class);
    // 同步执行器：让 submit 内联完成，测试可对终态做确定性断言（无需等待异步线程）。
    private final TaskExecutor directExecutor = Runnable::run;

    private NovelAutoTranslateService service() {
        return fixture(directExecutor).service();
    }

    private ServiceFixture fixture(TaskExecutor executor) {
        QueueTaskTracker taskTracker = new QueueTaskTracker("novel");
        return new ServiceFixture(
                new NovelAutoTranslateService(
                        translationService, glossaryService, mergeService, aiChatClient, executor, taskTracker),
                taskTracker);
    }

    private record ServiceFixture(
            NovelAutoTranslateService service,
            QueueTaskTracker taskTracker) {
    }

    private static QueueGenerationDrain quiesce(ServiceFixture fixture) {
        QueueGenerationDrain drain = fixture.taskTracker().prepareQuiesce();
        fixture.taskTracker().cancelQuiescedTasks();
        return drain;
    }

    private NovelTranslationService.Result ok(String lang) {
        return new NovelTranslationService.Result(NovelTranslationService.Status.OK, lang, "ok", false);
    }

    @Test
    @DisplayName("运行中的翻译在 quiesce 后必须等作业协作退出才归零")
    void translationDrainWaitsForRunningJobToExit() throws Exception {
        when(aiChatClient.isConfigured()).thenReturn(true);
        when(translationService.resolveLangCode("english")).thenReturn("en-US");
        when(glossaryService.getOrCreateNovelDefaultId(100L)).thenReturn(7L);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch allowFinish = new CountDownLatch(1);
        when(translationService.translateChapter(eq(100L), eq("english"), eq(0), eq(false),
                eq("en-US"), eq(7L), eq(true), eq(true), eq(true))).thenAnswer(invocation -> {
                    started.countDown();
                    assertTrue(allowFinish.await(5, TimeUnit.SECONDS));
                    return ok("en-US");
                });
        TaskExecutor async = task -> new Thread(task, "novel-translate-lease-test").start();
        ServiceFixture fixture = fixture(async);
        CompletableFuture<Void> future = fixture.service()
                .submit(100L, null, "english", 0, false, "epub");
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            QueueGenerationDrain drain = quiesce(fixture);
            assertFalse(drain.awaitDrained(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(50)));
            assertEquals(1, drain.activeCount());

            allowFinish.countDown();
            future.get(5, TimeUnit.SECONDS);

            assertTrue(drain.awaitDrained(System.nanoTime() + TimeUnit.SECONDS.toNanos(1)));
            NovelAutoTranslateService.StatusView status = fixture.service().getStatus(100L);
            assertEquals("FAILED", status.phase());
            assertEquals("plugin-quiesced", status.failureReason());
        } finally {
            allowFinish.countDown();
            future.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("词汇表查询期间 quiesce 后不再启动长耗时翻译")
    void quiesceDuringGlossaryLookupStopsBeforeTranslation() throws Exception {
        when(aiChatClient.isConfigured()).thenReturn(true);
        when(translationService.resolveLangCode("english")).thenReturn("en-US");
        CountDownLatch glossaryStarted = new CountDownLatch(1);
        CountDownLatch allowGlossaryReturn = new CountDownLatch(1);
        when(glossaryService.getOrCreateNovelDefaultId(101L)).thenAnswer(invocation -> {
            glossaryStarted.countDown();
            assertTrue(allowGlossaryReturn.await(5, TimeUnit.SECONDS));
            return 7L;
        });
        TaskExecutor async = task -> new Thread(task, "novel-glossary-cancellation-test").start();
        ServiceFixture fixture = fixture(async);
        CompletableFuture<Void> future = fixture.service()
                .submit(101L, null, "english", 0, false, "epub");
        try {
            assertTrue(glossaryStarted.await(5, TimeUnit.SECONDS));
            QueueGenerationDrain drain = quiesce(fixture);
            assertEquals(1, drain.activeCount());

            allowGlossaryReturn.countDown();
            future.get(5, TimeUnit.SECONDS);

            assertTrue(drain.awaitDrained(System.nanoTime() + TimeUnit.SECONDS.toNanos(1)));
            verify(translationService, never()).translateChapter(anyLong(), anyString(), anyInt(),
                    anyBoolean(), nullable(String.class), nullable(Long.class),
                    anyBoolean(), anyBoolean(), anyBoolean());
            NovelAutoTranslateService.StatusView status = fixture.service().getStatus(101L);
            assertEquals("FAILED", status.phase());
            assertEquals("plugin-quiesced", status.failureReason());
        } finally {
            allowGlossaryReturn.countDown();
            future.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("执行器拒绝翻译时以失败终结状态并归还共享任务计数")
    void rejectedExecutorFailsStatusAndReleasesTaskCount() {
        TaskExecutor rejecting = task -> {
            throw new RejectedExecutionException("executor stopped");
        };
        ServiceFixture fixture = fixture(rejecting);

        fixture.service().submit(102L, null, "english", 0, false, "epub").join();
        fixture.service().submit(103L, 500L, "english", 0, false, "epub").join();

        NovelAutoTranslateService.StatusView standalone = fixture.service().getStatus(102L);
        NovelAutoTranslateService.StatusView series = fixture.service().getStatus(103L);
        assertEquals("FAILED", standalone.phase());
        assertEquals("executor-rejected", standalone.failureReason());
        assertEquals("FAILED", series.phase());
        assertEquals("executor-rejected", series.failureReason());
        assertEquals(0, series.seriesPending());

        QueueGenerationDrain drain = quiesce(fixture);
        assertTrue(drain.isDrained());
        assertEquals(0, drain.activeCount());
    }

    @Test
    @DisplayName("执行器接纳前致命失败时释放独立与系列任务计数")
    void fatalBeforeExecutorAcceptanceReleasesTaskCount() {
        long novelId = 200L;
        for (Long seriesId : new Long[]{null, 500L}) {
            for (Error expected : new Error[]{
                    new OutOfMemoryError("novel-executor-fatal"), new ThreadDeath()}) {
                TaskExecutor fatalExecutor = task -> {
                    throw expected;
                };
                ServiceFixture fixture = fixture(fatalExecutor);
                long currentNovelId = novelId++;

                if (seriesId == null) {
                    Error observed = assertThrows(Error.class, () -> fixture.service().submit(
                            currentNovelId, null, "english", 0, false, "epub"));
                    assertSame(expected, observed);
                } else {
                    fixture.service().submit(
                            currentNovelId, seriesId, "english", 0, false, "epub").join();
                }
                NovelAutoTranslateService.StatusView status = fixture.service().getStatus(currentNovelId);
                assertEquals("FAILED", status.phase());
                assertEquals("executor-rejected", status.failureReason());
                assertEquals(0, status.seriesPending());
                QueueGenerationDrain drain = quiesce(fixture);
                assertTrue(drain.isDrained());
                assertEquals(0, drain.activeCount());
            }
        }
    }

    @Test
    @DisplayName("同语言合订期间 quiesce 后以插件静默失败终结")
    void quiesceDuringSameLanguageMergeFailsInsteadOfReportingSuccess() throws Exception {
        when(aiChatClient.isConfigured()).thenReturn(true);
        when(translationService.resolveLangCode("english")).thenReturn("en-US");
        when(glossaryService.getOrCreateNovelDefaultId(104L)).thenReturn(7L);
        when(translationService.translateChapter(eq(104L), eq("english"), eq(0), eq(false),
                eq("en-US"), eq(7L), eq(true), eq(true), eq(true))).thenReturn(
                new NovelTranslationService.Result(
                        NovelTranslationService.Status.SAME_LANGUAGE, "en-US", "same", false));
        CountDownLatch mergeStarted = new CountDownLatch(1);
        CountDownLatch allowMergeReturn = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            mergeStarted.countDown();
            assertTrue(allowMergeReturn.await(5, TimeUnit.SECONDS));
            return null;
        }).when(mergeService).merge(500L, NovelDownloadService.NovelFormat.EPUB);
        TaskExecutor async = task -> new Thread(task, "novel-same-language-cancellation-test").start();
        ServiceFixture fixture = fixture(async);
        CompletableFuture<Void> future = fixture.service()
                .submit(104L, 500L, "english", 0, true, "epub");
        try {
            assertTrue(mergeStarted.await(5, TimeUnit.SECONDS));
            QueueGenerationDrain drain = quiesce(fixture);
            assertEquals(1, drain.activeCount());

            allowMergeReturn.countDown();
            future.get(5, TimeUnit.SECONDS);

            assertTrue(drain.awaitDrained(System.nanoTime() + TimeUnit.SECONDS.toNanos(1)));
            NovelAutoTranslateService.StatusView status = fixture.service().getStatus(104L);
            assertEquals("FAILED", status.phase());
            assertEquals("plugin-quiesced", status.failureReason());
        } finally {
            allowMergeReturn.countDown();
            future.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("quiesce 后的新翻译以插件不可用终结且不进入执行器")
    void quiesceRejectsNewTranslation() {
        ServiceFixture fixture = fixture(directExecutor);
        QueueGenerationDrain drain = quiesce(fixture);

        fixture.service().submit(105L, 500L, "english", 0, false, "epub").join();

        NovelAutoTranslateService.StatusView status = fixture.service().getStatus(105L);
        assertEquals("FAILED", status.phase());
        assertEquals("plugin-unavailable", status.failureReason());
        assertEquals(0, status.seriesPending());
        assertTrue(drain.isDrained());
        verify(translationService, never()).translateChapter(anyLong(), anyString(), anyInt(),
                anyBoolean(), nullable(String.class), nullable(Long.class),
                anyBoolean(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("同系列等待任务计入 drain，quiesce 不启动任何后继章节")
    void quiesceCancelsWaitingSeriesWithoutLaunchingSuccessors() throws Exception {
        when(aiChatClient.isConfigured()).thenReturn(true);
        when(translationService.resolveLangCode("english")).thenReturn("en-US");
        when(glossaryService.getOrCreateNovelDefaultId(anyLong())).thenReturn(7L);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch allowFirstReturn = new CountDownLatch(1);
        AtomicInteger translations = new AtomicInteger();
        when(translationService.translateChapter(anyLong(), anyString(), anyInt(), anyBoolean(),
                nullable(String.class), nullable(Long.class), anyBoolean(), anyBoolean(), anyBoolean()))
                .thenAnswer(invocation -> {
                    translations.incrementAndGet();
                    firstStarted.countDown();
                    assertTrue(allowFirstReturn.await(5, TimeUnit.SECONDS));
                    return ok("en-US");
                });
        TaskExecutor async = task -> {
            Thread worker = new Thread(task, "novel-series-quiesce-test");
            worker.setDaemon(true);
            worker.start();
        };
        ServiceFixture fixture = fixture(async);
        CompletableFuture<Void> first = fixture.service()
                .submit(106L, 500L, "english", 0, false, "epub");
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
        CompletableFuture<Void> second = fixture.service()
                .submit(107L, 500L, "english", 0, false, "epub");
        CompletableFuture<Void> third = fixture.service()
                .submit(108L, 500L, "english", 0, false, "epub");
        assertEquals(3, fixture.taskTracker().activeTaskCount());

        QueueGenerationDrain drain = quiesce(fixture);
        try {
            second.get(5, TimeUnit.SECONDS);
            third.get(5, TimeUnit.SECONDS);
            assertEquals(1, drain.activeCount());
            assertFalse(drain.awaitDrained(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(50)));
            assertEquals(1, translations.get());
            assertEquals("plugin-quiesced", fixture.service().getStatus(107L).failureReason());
            assertEquals("plugin-quiesced", fixture.service().getStatus(108L).failureReason());
            assertEquals(0, fixture.service().getStatus(108L).seriesPending());

            allowFirstReturn.countDown();
            first.get(5, TimeUnit.SECONDS);
            assertTrue(drain.awaitDrained(System.nanoTime() + TimeUnit.SECONDS.toNanos(1)));
            assertEquals("plugin-quiesced", fixture.service().getStatus(106L).failureReason());
            assertEquals(1, translations.get());
        } finally {
            allowFirstReturn.countDown();
            first.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("同系列异步翻译严格按提交顺序执行")
    void seriesRunsStrictlyInSubmissionOrder() throws Exception {
        when(aiChatClient.isConfigured()).thenReturn(true);
        when(translationService.resolveLangCode("english")).thenReturn("en-US");
        when(glossaryService.getOrCreateNovelDefaultId(anyLong())).thenReturn(7L);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch allowFirstReturn = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        when(translationService.translateChapter(anyLong(), anyString(), anyInt(), anyBoolean(),
                nullable(String.class), nullable(Long.class), anyBoolean(), anyBoolean(), anyBoolean()))
                .thenAnswer(invocation -> {
                    long novelId = invocation.getArgument(0);
                    if (novelId == 109L) {
                        firstStarted.countDown();
                        assertTrue(allowFirstReturn.await(5, TimeUnit.SECONDS));
                    } else if (novelId == 110L) {
                        secondStarted.countDown();
                    }
                    return ok("en-US");
                });
        TaskExecutor async = task -> {
            Thread worker = new Thread(task, "novel-series-order-test");
            worker.setDaemon(true);
            worker.start();
        };
        ServiceFixture fixture = fixture(async);
        CompletableFuture<Void> first = fixture.service()
                .submit(109L, 501L, "english", 0, false, "epub");
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
        CompletableFuture<Void> second = fixture.service()
                .submit(110L, 501L, "english", 0, false, "epub");
        try {
            assertFalse(secondStarted.await(50, TimeUnit.MILLISECONDS));
            assertEquals(2, fixture.taskTracker().activeTaskCount());
            allowFirstReturn.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            assertTrue(secondStarted.await(1, TimeUnit.SECONDS));
            assertEquals("DONE", fixture.service().getStatus(109L).phase());
            assertEquals("DONE", fixture.service().getStatus(110L).phase());
            QueueGenerationDrain drain = fixture.taskTracker().prepareQuiesce();
            assertTrue(drain.awaitDrained(System.nanoTime() + TimeUnit.SECONDS.toNanos(1)));
            assertEquals(0, drain.activeCount());
        } finally {
            allowFirstReturn.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("等待项提前取消时后继仍等待正在运行的同系列任务")
    void cancelledWaitingTranslationKeepsPredecessorBarrier() throws Exception {
        when(aiChatClient.isConfigured()).thenReturn(true);
        when(translationService.resolveLangCode("english")).thenReturn("en-US");
        when(glossaryService.getOrCreateNovelDefaultId(anyLong())).thenReturn(7L);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch allowFirstReturn = new CountDownLatch(1);
        CountDownLatch thirdStarted = new CountDownLatch(1);
        when(translationService.translateChapter(anyLong(), anyString(), anyInt(), anyBoolean(),
                nullable(String.class), nullable(Long.class), anyBoolean(), anyBoolean(), anyBoolean()))
                .thenAnswer(invocation -> {
                    long novelId = invocation.getArgument(0);
                    if (novelId == 113L) {
                        firstStarted.countDown();
                        assertTrue(allowFirstReturn.await(5, TimeUnit.SECONDS));
                    } else if (novelId == 115L) {
                        thirdStarted.countDown();
                    }
                    return ok("en-US");
                });
        TaskExecutor async = task -> {
            Thread worker = new Thread(task, "novel-series-cancel-barrier-test");
            worker.setDaemon(true);
            worker.start();
        };
        ServiceFixture fixture = fixture(async);
        CompletableFuture<Void> first = fixture.service()
                .submit(113L, 504L, "english", 0, false, "epub");
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
        CompletableFuture<Void> second = fixture.service()
                .submit(114L, 504L, "english", 0, false, "epub");
        assertEquals(1, fixture.taskTracker().cancelForOwner(NovelQueueTaskOwners.autoTranslate(114L)));
        second.get(5, TimeUnit.SECONDS);
        CompletableFuture<Void> third = fixture.service()
                .submit(115L, 504L, "english", 0, false, "epub");
        try {
            assertEquals("plugin-quiesced", fixture.service().getStatus(114L).failureReason());
            assertFalse(thirdStarted.await(50, TimeUnit.MILLISECONDS));
            allowFirstReturn.countDown();
            first.get(5, TimeUnit.SECONDS);
            third.get(5, TimeUnit.SECONDS);
            assertTrue(thirdStarted.await(1, TimeUnit.SECONDS));
            QueueGenerationDrain drain = fixture.taskTracker().prepareQuiesce();
            assertTrue(drain.awaitDrained(System.nanoTime() + TimeUnit.SECONDS.toNanos(1)));
            assertEquals(0, drain.activeCount());
        } finally {
            allowFirstReturn.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            third.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("不同系列可同时进入翻译执行阶段")
    void differentSeriesRunConcurrently() throws Exception {
        when(aiChatClient.isConfigured()).thenReturn(true);
        when(translationService.resolveLangCode("english")).thenReturn("en-US");
        when(glossaryService.getOrCreateNovelDefaultId(anyLong())).thenReturn(7L);
        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch allowReturn = new CountDownLatch(1);
        when(translationService.translateChapter(anyLong(), anyString(), anyInt(), anyBoolean(),
                nullable(String.class), nullable(Long.class), anyBoolean(), anyBoolean(), anyBoolean()))
                .thenAnswer(invocation -> {
                    bothStarted.countDown();
                    assertTrue(allowReturn.await(5, TimeUnit.SECONDS));
                    return ok("en-US");
                });
        TaskExecutor async = task -> {
            Thread worker = new Thread(task, "novel-cross-series-test");
            worker.setDaemon(true);
            worker.start();
        };
        ServiceFixture fixture = fixture(async);
        CompletableFuture<Void> first = fixture.service()
                .submit(111L, 502L, "english", 0, false, "epub");
        CompletableFuture<Void> second = fixture.service()
                .submit(112L, 503L, "english", 0, false, "epub");
        try {
            assertTrue(bothStarted.await(5, TimeUnit.SECONDS));
            assertEquals(2, fixture.taskTracker().activeTaskCount());
            allowReturn.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            QueueGenerationDrain drain = fixture.taskTracker().prepareQuiesce();
            assertTrue(drain.awaitDrained(System.nanoTime() + TimeUnit.SECONDS.toNanos(1)));
            assertEquals(0, drain.activeCount());
        } finally {
            allowReturn.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("AI 未启用时标记为 FAILED 且不调用翻译")
    void aiDisabledMarksFailed() {
        when(aiChatClient.isConfigured()).thenReturn(false);
        NovelAutoTranslateService s = service();

        s.submit(100L, null, "english", 0, false, "epub");

        NovelAutoTranslateService.StatusView v = s.getStatus(100L);
        assertNotNull(v);
        assertEquals("FAILED", v.phase());
        assertTrue(v.failed());
        verify(translationService, never()).translateChapter(anyLong(), anyString(), anyInt(),
                anyBoolean(), nullable(String.class), nullable(Long.class),
                anyBoolean(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("单本成功翻译后状态为 DONE 且不触发系列合订")
    void standaloneSuccessNoMerge() throws Exception {
        when(aiChatClient.isConfigured()).thenReturn(true);
        when(translationService.resolveLangCode("english")).thenReturn("en-US");
        when(glossaryService.getOrCreateNovelDefaultId(100L)).thenReturn(7L);
        when(translationService.translateChapter(eq(100L), eq("english"), eq(0), eq(false),
                eq("en-US"), eq(7L), eq(true), eq(true), eq(true))).thenReturn(ok("en-US"));
        NovelAutoTranslateService s = service();

        s.submit(100L, null, "english", 0, true, "epub");

        NovelAutoTranslateService.StatusView v = s.getStatus(100L);
        assertEquals("DONE", v.phase());
        assertTrue(v.done());
        assertEquals("en-US", v.langCode());
        verify(mergeService, never()).merge(anyLong(), any());
        verify(translationService, never()).translateSeriesTitle(anyLong(), anyString(),
                nullable(String.class), nullable(Long.class), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("系列成功翻译后翻译系列名并按设置重生译文合订")
    void seriesSuccessTriggersMerge() throws Exception {
        when(aiChatClient.isConfigured()).thenReturn(true);
        when(translationService.resolveLangCode("english")).thenReturn("en-US");
        when(glossaryService.getOrCreateNovelDefaultId(anyLong())).thenReturn(7L);
        when(translationService.translateChapter(anyLong(), anyString(), anyInt(), anyBoolean(),
                nullable(String.class), nullable(Long.class), anyBoolean(), anyBoolean(), anyBoolean()))
                .thenReturn(ok("en-US"));
        NovelAutoTranslateService s = service();

        s.submit(100L, 500L, "english", 0, true, "epub");

        assertEquals("DONE", s.getStatus(100L).phase());
        verify(translationService).translateSeriesTitle(
                eq(500L), eq("english"), eq("en-US"), eq(7L), eq(true), eq(true));
        verify(mergeService).merge(eq(500L), eq(NovelDownloadService.NovelFormat.EPUB));
    }

    @Test
    @DisplayName("同系列两章都完成翻译（串行链不丢任务）")
    void seriesSerialCompletesAll() throws Exception {
        when(aiChatClient.isConfigured()).thenReturn(true);
        when(translationService.resolveLangCode(anyString())).thenReturn("en-US");
        when(glossaryService.getOrCreateNovelDefaultId(anyLong())).thenReturn(7L);
        when(translationService.translateChapter(anyLong(), anyString(), anyInt(), anyBoolean(),
                nullable(String.class), nullable(Long.class), anyBoolean(), anyBoolean(), anyBoolean()))
                .thenReturn(ok("en-US"));
        NovelAutoTranslateService s = service();

        s.submit(100L, 500L, "english", 0, false, "epub");
        s.submit(101L, 500L, "english", 0, false, "epub");

        assertEquals("DONE", s.getStatus(100L).phase());
        assertEquals("DONE", s.getStatus(101L).phase());
        verify(translationService, times(2)).translateChapter(anyLong(), anyString(), anyInt(),
                anyBoolean(), nullable(String.class), nullable(Long.class),
                anyBoolean(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("同一目标语言只探测一次语言代码")
    void langCodeProbedOnce() throws Exception {
        when(aiChatClient.isConfigured()).thenReturn(true);
        when(translationService.resolveLangCode("english")).thenReturn("en-US");
        when(glossaryService.getOrCreateNovelDefaultId(anyLong())).thenReturn(null);
        when(translationService.translateChapter(anyLong(), anyString(), anyInt(), anyBoolean(),
                nullable(String.class), nullable(Long.class), anyBoolean(), anyBoolean(), anyBoolean()))
                .thenReturn(ok("en-US"));
        NovelAutoTranslateService s = service();

        s.submit(100L, null, "english", 0, false, "epub");
        s.submit(101L, null, "English", 0, false, "epub"); // 大小写归一化后命中缓存

        verify(translationService, times(1)).resolveLangCode(anyString());
    }

    @Test
    @DisplayName("终态状态超过保留期后被惰性清理（返回 null 并移除条目）")
    void terminalStatusExpires() throws Exception {
        when(aiChatClient.isConfigured()).thenReturn(true);
        when(translationService.resolveLangCode(anyString())).thenReturn("en-US");
        when(glossaryService.getOrCreateNovelDefaultId(anyLong())).thenReturn(null);
        when(translationService.translateChapter(anyLong(), anyString(), anyInt(), anyBoolean(),
                nullable(String.class), nullable(Long.class), anyBoolean(), anyBoolean(), anyBoolean()))
                .thenReturn(ok("en-US"));
        NovelAutoTranslateService s = service();

        s.submit(100L, null, "english", 0, false, "epub");

        long now = System.currentTimeMillis();
        assertEquals("DONE", s.getStatus(100L, now).phase());
        // 进入终态超过保留期：惰性清理，视为无翻译记录
        assertNull(s.getStatus(100L, now + Duration.ofMinutes(31).toMillis()));
        // 条目已被移除：后续读取仍为 null
        assertNull(s.getStatus(100L));
    }

    @Test
    @DisplayName("源语言与目标一致时标记为 SAME_LANGUAGE 终态并按设置合订，但不翻译系列名")
    void sameLanguageRefreshesMerge() throws Exception {
        when(aiChatClient.isConfigured()).thenReturn(true);
        when(translationService.resolveLangCode(anyString())).thenReturn("en-US");
        when(glossaryService.getOrCreateNovelDefaultId(anyLong())).thenReturn(7L);
        when(translationService.translateChapter(anyLong(), anyString(), anyInt(), anyBoolean(),
                nullable(String.class), nullable(Long.class), anyBoolean(), anyBoolean(), anyBoolean()))
                .thenReturn(new NovelTranslationService.Result(
                        NovelTranslationService.Status.SAME_LANGUAGE, "en-US", "same", false));
        NovelAutoTranslateService s = service();

        s.submit(100L, 500L, "english", 0, true, "epub");

        NovelAutoTranslateService.StatusView v = s.getStatus(100L);
        assertEquals("SAME_LANGUAGE", v.phase());
        assertTrue(v.done());
        assertEquals("en-US", v.langCode());
        verify(mergeService).merge(eq(500L), eq(NovelDownloadService.NovelFormat.EPUB));
        verify(translationService, never()).translateSeriesTitle(anyLong(), anyString(),
                nullable(String.class), nullable(Long.class), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("翻译返回错误状态时标记为 FAILED 且不合订")
    void translateErrorMarksFailed() throws Exception {
        when(aiChatClient.isConfigured()).thenReturn(true);
        when(translationService.resolveLangCode(anyString())).thenReturn("en-US");
        when(glossaryService.getOrCreateNovelDefaultId(anyLong())).thenReturn(null);
        when(translationService.translateChapter(anyLong(), anyString(), anyInt(), anyBoolean(),
                nullable(String.class), nullable(Long.class), anyBoolean(), anyBoolean(), anyBoolean()))
                .thenReturn(new NovelTranslationService.Result(
                        NovelTranslationService.Status.ERROR, null, "err", false));
        NovelAutoTranslateService s = service();

        s.submit(100L, 500L, "english", 0, true, "epub");

        assertEquals("FAILED", s.getStatus(100L).phase());
        assertTrue(s.getStatus(100L).failed());
        verify(mergeService, never()).merge(anyLong(), any());
    }
}
