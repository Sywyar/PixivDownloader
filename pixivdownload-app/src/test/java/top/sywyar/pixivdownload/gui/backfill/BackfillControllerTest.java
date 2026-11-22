package top.sywyar.pixivdownload.gui.backfill;

import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.sywyar.pixivdownload.config.ProxyConfig;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.db.DatabaseTarget;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.tools.ArtworksBackFill;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("管理员回填 HTTP 协议")
class BackfillControllerTest {
    private MockMvc mvc(DesktopBackfillRunner runner) {
        var target = mock(DatabaseTarget.class);
        when(target.available()).thenReturn(true);
        when(target.displayName()).thenReturn("configured-database");
        var proxy = new ProxyConfig();
        proxy.setEnabled(false);
        return MockMvcBuilders.standaloneSetup(new BackfillController(runner, target, proxy,
                new DownloadConfig(), TestI18nBeans.appMessages())).build();
    }

    @Test @DisplayName("真实后台执行共用准入、查询及精确取消，后续任务期间仍能读取原终态")
    void executionLifecycle() throws Exception {
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var options = new AtomicReference<ArtworksBackFill.Options>();
        var runner = new DesktopBackfillRunner((value, progress) -> {
            options.set(value);
            entered.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException cancelled) {
                interrupted.countDown();
                release.await();
                throw cancelled;
            }
            throw new AssertionError();
        }, () -> {
            var log = mock(top.sywyar.pixivdownload.plugin.api.gui.DesktopUiToolHost.ToolLogSession.class);
            when(log.sessionPath()).thenReturn(java.nio.file.Path.of("fixture.html"));
            return log;
        });
        runner.start();
        var mvc = mvc(runner);
        try {
            mvc.perform(get("/api/tools/backfill/tasks/current")).andExpect(status().isNoContent());
            var result = mvc.perform(post("/api/tools/backfill/tasks").contentType("application/json")
                            .content("{\"dryRun\":true,\"artworkIds\":[42,42],\"limit\":3,\"delayMs\":0,\"alwaysNetwork\":true}"))
                    .andExpect(status().isAccepted()).andReturn();
            String location = result.getResponse().getHeader("Location");
            assertNotNull(location);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(options.get().dryRun());
            assertTrue(options.get().alwaysNetwork());
            assertFalse(options.get().useProxy());
            assertEquals(List.of(42L), options.get().artworkIds());
            assertEquals(3, options.get().limit());
            assertEquals("configured-database", options.get().dbPath());
            mvc.perform(get(location)).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("RUNNING"));
            mvc.perform(post("/api/tools/backfill/tasks").contentType("application/json").content("{}"))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BACKFILL_BUSY"));
            mvc.perform(post("/api/tools/backfill/tasks/" + UUID.randomUUID() + "/cancel"))
                    .andExpect(status().isNotFound());
            assertEquals(1, interrupted.getCount());
            mvc.perform(post(location + "/cancel")).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("STOPPING"));
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
            release.countDown();
            runner.stop();
            mvc.perform(get(location)).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("CANCELLED"));
            mvc.perform(post(location + "/cancel")).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("CANCELLED"));
            runner.start();
            var second = mvc.perform(post("/api/tools/backfill/tasks").contentType("application/json").content("{}"))
                    .andExpect(status().isAccepted()).andReturn().getResponse().getHeader("Location");
            assertNotEquals(location, second);
            mvc.perform(get(location)).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("CANCELLED"));
            mvc.perform(post(location + "/cancel")).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("CANCELLED"));
            mvc.perform(get(second)).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("RUNNING"));
        } finally { release.countDown(); runner.stop(); }
    }

    @Test @DisplayName("畸形输入及任意路径代理字段在执行前拒绝，正文预算覆盖未知长度")
    void invalidInputDoesNotStartWork() throws Exception {
        var runner = mock(DesktopBackfillRunner.class);
        var mvc = mvc(runner);
        for (String body : List.of("", "null", "{} {}", "{\"limit\":1,\"limit\":2}", "{\"limit\":1.5}",
                "{\"limit\":\"3\"}", "{\"dryRun\":\"true\"}", "{\"limit\":-1}", "{\"delayMs\":-1}",
                "{\"artworkIds\":null}", "{\"artworkIds\":[]}", "{\"artworkIds\":[null]}", "{\"artworkIds\":[0]}", "{\"dbPath\":\"private.db\"}", "{\"proxyHost\":\"remote\"}", "{\"rootFolder\":\"private\"}")) {
            mvc.perform(post("/api/tools/backfill/tasks").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BACKFILL_INVALID_REQUEST"));
        }
        mvc.perform(post("/api/tools/backfill/tasks").contentType("application/json")
                        .content("{\"artworkIds\":[1,0]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BACKFILL_INVALID_REQUEST"))
                .andExpect(jsonPath("$.in").value("body")).andExpect(jsonPath("$.field").value("/artworkIds/1"))
                .andExpect(jsonPath("$.reason").value("out_of_range"));
        mvc.perform(post("/api/tools/backfill/tasks").contentType("application/json")
                        .content(" ".repeat(BackfillController.MAX_BODY_BYTES + 1)))
                .andExpect(status().isPayloadTooLarge());
        mvc.perform(context -> {
            var request = new org.springframework.mock.web.MockHttpServletRequest(context) {
                @Override public long getContentLengthLong() { return -1; }
            };
            request.setMethod("POST");
            request.setRequestURI("/api/tools/backfill/tasks");
            request.setContentType("application/json");
            request.setContent(new byte[BackfillController.MAX_BODY_BYTES + 1]);
            return request;
        }).andExpect(status().isPayloadTooLarge());
        verifyNoInteractions(runner);
    }

    @Test @DisplayName("候选统计复用执行器，后端不可用不启动任务")
    void candidatesAndUnavailable() throws Exception {
        var runner = mock(DesktopBackfillRunner.class);
        when(runner.isRunning()).thenReturn(true);
        when(runner.countCandidates(any())).thenReturn(7);
        var mvc = mvc(runner);
        mvc.perform(post("/api/tools/backfill/candidates").contentType("application/json").content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.count").value(7));
        when(runner.isRunning()).thenReturn(false);
        mvc.perform(post("/api/tools/backfill/tasks").contentType("application/json").content("{}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("BACKFILL_UNAVAILABLE"));
        verify(runner, never()).start(any(), any());
    }
}
