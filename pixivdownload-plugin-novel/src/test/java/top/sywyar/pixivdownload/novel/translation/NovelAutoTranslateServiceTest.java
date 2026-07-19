package top.sywyar.pixivdownload.novel.translation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import top.sywyar.pixivdownload.ai.AiChatClient;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityOwner;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityPublication;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistry;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistryTestAccess;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleOwnerBundle;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledWork;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledWorkKind;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledWorkRunner;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledWorkSettings;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

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
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        ScheduleCapabilityPublication publication = ScheduleCapabilityRegistryTestAccess.publish(
                registry, ScheduleOwnerBundle.prepare(
                        new ScheduleCapabilityOwner("novel", "novel", 1L),
                        List.of(),
                        List.of(new ScheduledWorkRunner() {
                            @Override public String kind() { return ScheduledWorkKind.NOVEL; }
                            @Override
                            public boolean download(
                                    ScheduledWork work, ScheduledWorkSettings settings, String cookie) {
                                return true;
                            }
                        }),
                        List.of(), List.of(), List.of(), List.of(), List.of()));
        return new ServiceFixture(
                new NovelAutoTranslateService(
                        translationService, glossaryService, mergeService, aiChatClient, executor, registry),
                registry,
                publication);
    }

    private record ServiceFixture(
            NovelAutoTranslateService service,
            ScheduleCapabilityRegistry registry,
            ScheduleCapabilityPublication publication) {
    }

    private NovelTranslationService.Result ok(String lang) {
        return new NovelTranslationService.Result(NovelTranslationService.Status.OK, lang, "ok", false);
    }

    @Test
    @DisplayName("翻译 future 持有 novel owner lease，撤回后 drain 等待作业协作退出")
    void translationFutureKeepsOwnerLeaseUntilCompletion() throws Exception {
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
            var drain = ScheduleCapabilityRegistryTestAccess.withdraw(
                    fixture.registry(), fixture.publication()).orElseThrow();
            assertFalse(drain.awaitDrained(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(50)));
            assertEquals(1, drain.activeLeaseCount());

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
    @DisplayName("词汇表查询期间撤回 owner 后不再启动长耗时翻译")
    void withdrawalDuringGlossaryLookupStopsBeforeTranslation() throws Exception {
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
            var drain = ScheduleCapabilityRegistryTestAccess.withdraw(
                    fixture.registry(), fixture.publication()).orElseThrow();
            assertEquals(1, drain.activeLeaseCount());

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
    @DisplayName("执行器拒绝翻译时以失败终结状态并释放 owner 租约")
    void rejectedExecutorFailsStatusAndReleasesOwnerLeases() {
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

        var drain = ScheduleCapabilityRegistryTestAccess.withdraw(
                fixture.registry(), fixture.publication()).orElseThrow();
        assertTrue(drain.isDrained());
        assertEquals(0, drain.activeLeaseCount());
    }

    @Test
    @DisplayName("执行器接纳前致命失败时释放独立与系列 owner 租约")
    void fatalBeforeExecutorAcceptanceReleasesOwnerLease() {
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
                var drain = ScheduleCapabilityRegistryTestAccess.withdraw(
                        fixture.registry(), fixture.publication()).orElseThrow();
                assertTrue(drain.isDrained());
                assertEquals(0, drain.activeLeaseCount());
            }
        }
    }

    @Test
    @DisplayName("同语言合订期间撤回 owner 后以插件静默失败终结")
    void withdrawalDuringSameLanguageMergeFailsInsteadOfReportingSuccess() throws Exception {
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
            var drain = ScheduleCapabilityRegistryTestAccess.withdraw(
                    fixture.registry(), fixture.publication()).orElseThrow();
            assertEquals(1, drain.activeLeaseCount());

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
