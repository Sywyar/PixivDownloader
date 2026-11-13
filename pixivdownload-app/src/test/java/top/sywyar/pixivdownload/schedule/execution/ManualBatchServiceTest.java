package top.sywyar.pixivdownload.schedule.execution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import top.sywyar.pixivdownload.config.OutboundProxySettings;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskStore;
import top.sywyar.pixivdownload.plugin.api.schedule.capability.ScheduleCapabilityOwner;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledFailure;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardDecision;
import top.sywyar.pixivdownload.plugin.api.schedule.source.*;
import top.sywyar.pixivdownload.plugin.api.schedule.work.*;
import top.sywyar.pixivdownload.schedule.*;
import top.sywyar.pixivdownload.schedule.batch.ManualBatchService;
import top.sywyar.pixivdownload.schedule.persistence.ScheduleWorkPersistenceCodec;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("独立手动批次的真实引擎与生命周期")
class ManualBatchServiceTest extends ScheduleExecutionEngineTestSupport {
    private record Fixture(ManualBatchService service, String token) {}

    private Fixture service(ScheduledSourceExecutor source, ScheduledWorkExecutor work) {
        var registry = new FakeScheduleCapabilityAccess();
        publish(registry, bindingBundle(new ScheduleCapabilityOwner("fixture", "fixture-package", 1), source, work,
                new top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialPolicy() {
                    @Override public String policyId() { return POLICY; }
                    @Override public top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialProbeResult probe(
                            top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialContext context) {
                        return top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialProbeResult.valid("account-1");
                    }
                }, guard(context -> ScheduledGuardDecision.proceed())));
        var json = JsonMapper.builder().build();
        var service = new ManualBatchService(registry, mock(ScheduledTaskStore.class), new ScheduleRunState(),
                mock(TransactionTemplate.class), new ScheduleConfig(), new ScheduleWorkPersistenceCodec(json),
                new ScheduleNetworkRouteResolver(mock(OutboundProxySettings.class)), new SyncTaskExecutor(),
                new ScheduleWorkConcurrencyLimiter(), json, () -> true);
        service.start();
        return new Fixture(service, activationToken(registry));
    }

    private ManualBatchService.Request request(String token) {
        return new ManualBatchService.Request("Fixture", SOURCE, token, "{}", null);
    }

    @Test
    void httpUsesRealEngineStrictInputAndCookieCsrf() throws Exception {
        var source = spy(sourceExecutor(1, context -> {
            context.workSink().submit(work("http-work"));
            return ScheduledDiscoveryResult.withoutCheckpoint();
        }));
        var fixture = service(source, workExecutor(context -> ScheduledWorkResult.completed()));
        var messages = top.sywyar.pixivdownload.i18n.TestI18nBeans.appMessages();
        var locale = mock(top.sywyar.pixivdownload.i18n.AppLocaleResolver.class);
        when(locale.resolveLocale(any())).thenReturn(java.util.Locale.US);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new top.sywyar.pixivdownload.schedule.batch.ManualBatchController(fixture.service(),
                        new top.sywyar.pixivdownload.gui.ToolApiRequests(messages, mock(top.sywyar.pixivdownload.i18n.WebI18nService.class)), messages))
                .addFilters(new top.sywyar.pixivdownload.setup.CsrfProtectionFilter(locale, messages,
                        mock(top.sywyar.pixivdownload.plugin.registry.route.RouteAccessRegistry.class))).build();
        String base = "/api/download/batches";
        byte[] body = JsonMapper.builder().build().writeValueAsBytes(request(fixture.token()));
        try {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(base).contentType("application/json")
                    .cookie(new jakarta.servlet.http.Cookie("pixiv_session", "admin"))
                    .header("Origin", "https://foreign.invalid").content(body))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(base).contentType("application/json")
                    .content("{\"unknown\":true}"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
            verify(source, never()).discover(any());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(base).contentType("application/json")
                    .header("X-Acquisition-Credential", "fixture-secret").content(body))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isAccepted());
            var done = awaitIdle(fixture.service());
            assertThat(done.state()).isEqualTo(ManualBatchService.State.COMPLETED);
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(base + "/" + done.id() + "/items").param("limit", "1"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[0].key.id").value("http-work"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("fixture-secret"))));
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(base).contentType("application/json").content(body))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(base + "/" + done.id()))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNoContent());
        } finally { fixture.service().stop(); }
    }

    @Test
    void shutdownWaitsForWorkAndCredentialReplacementRequiresIdleRun() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var fixture = service(sourceExecutor(1, context -> {
            context.workSink().submit(work("blocked"));
            return ScheduledDiscoveryResult.withoutCheckpoint();
        }), workExecutor(context -> {
            entered.countDown();
            try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("release timeout"); }
            catch (InterruptedException failure) { throw ScheduledExecutionException.cancelled(); }
            return ScheduledWorkResult.completed();
        }));
        var service = fixture.service();
        var stopped = new CountDownLatch(1);
        try {
            var accepted = service.create(request(fixture.token()), "fixture-secret");
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> service.credentials(accepted.id(), accepted.run(), "replacement"))
                    .isInstanceOf(IllegalStateException.class);
            Thread.ofPlatform().start(() -> { service.stop(); stopped.countDown(); });
            assertThat(stopped.await(50, TimeUnit.MILLISECONDS)).isFalse();
            release.countDown();
            assertThat(stopped.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(service.get(accepted.id()).state()).isEqualTo(ManualBatchService.State.CANCELLED);
        } finally { release.countDown(); service.stop(); }
    }
    private ManualBatchService.View awaitIdle(ManualBatchService service) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            var view = service.current();
            if (!List.of(ManualBatchService.State.RUNNING, ManualBatchService.State.PAUSING, ManualBatchService.State.CANCELLING).contains(view.state())) return view;
            Thread.sleep(5);
        }
        throw new AssertionError("batch did not drain");
    }

    @Test
    @DisplayName("暂停等待实际作品退出，恢复不重放已完成作品，删除只作用于精确批次")
    void pausesAfterDrainAndResumesWithoutRepeatingCompletedWork() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executions = new AtomicInteger();
        var fixture = service(sourceExecutor(1, context -> {
            for (String id : List.of("first", "second", "third")) context.workSink().submit(work(id));
            return ScheduledDiscoveryResult.withoutCheckpoint();
        }), workExecutor(context -> {
            if (executions.incrementAndGet() == 2) {
                entered.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("release timeout"); }
                catch (InterruptedException failure) { throw ScheduledExecutionException.cancelled(); }
            }
            return ScheduledWorkResult.completed();
        }));
        var service = fixture.service();
        try {
            var accepted = service.create(request(fixture.token()), "fixture-secret");
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(service.stop(accepted.id(), true).state()).isEqualTo(ManualBatchService.State.PAUSING);
            assertThatThrownBy(() -> service.delete(accepted.id())).isInstanceOf(IllegalStateException.class);
            release.countDown();
            assertThat(awaitIdle(service).state()).isEqualTo(ManualBatchService.State.PAUSED);
            assertThatThrownBy(() -> service.credentials(accepted.id(), accepted.run() - 1, "replacement"))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(service.credentials(accepted.id(), accepted.run(), "replacement").state()).isEqualTo(ManualBatchService.State.PAUSED);
            service.credentials(accepted.id(), accepted.run(), null);
            service.credentials(accepted.id(), accepted.run(), "replacement");
            service.resume(accepted.id(), false);
            var done = awaitIdle(service);
            assertThat(done.state()).isEqualTo(ManualBatchService.State.COMPLETED);
            assertThat(done.completed()).isEqualTo(3);
            assertThat(executions).hasValue(3);
            assertThat(service.items(done.id(), 1, 1).items()).singleElement()
                    .satisfies(item -> assertThat(item.key().id()).isEqualTo("second"));
            assertThatThrownBy(() -> service.delete(java.util.UUID.randomUUID())).isInstanceOf(java.util.NoSuchElementException.class);
            service.delete(done.id());
            assertThat(service.current()).isNull();
        } finally { release.countDown(); service.stop(); }
    }

    @Test
    @DisplayName("重试只处理失败项，不重新扫描来源或重复成功作品")
    void retriesOnlyFailedWork() throws Exception {
        var discoveries = new AtomicInteger();
        var executions = new AtomicInteger();
        var fixture = service(sourceExecutor(1, context -> {
            discoveries.incrementAndGet();
            context.workSink().submit(work("good"));
            context.workSink().submit(work("bad"));
            return ScheduledDiscoveryResult.withoutCheckpoint();
        }), workExecutor(context -> {
            if (executions.incrementAndGet() == 2) throw new ScheduledExecutionException(ScheduledFailure.Category.RETRYABLE_NETWORK, "fixture.retry");
            return ScheduledWorkResult.completed();
        }));
        var service = fixture.service();
        try {
            var accepted = service.create(request(fixture.token()), "fixture-secret");
            assertThat(awaitIdle(service).state()).isEqualTo(ManualBatchService.State.FAILED);
            service.resume(accepted.id(), true);
            var done = awaitIdle(service);
            assertThat(done.state()).isEqualTo(ManualBatchService.State.COMPLETED);
            assertThat(done.completed()).isEqualTo(2);
            assertThat(executions).hasValue(3);
            assertThat(discoveries).hasValue(1);
        } finally { service.stop(); }
    }

    @Test
    @DisplayName("过期来源令牌保留挂起批次且不调用来源，宿主停止后拒绝新提交")
    void stalePublicationFailsClosed() throws Exception {
        var source = spy(sourceExecutor(1, context -> ScheduledDiscoveryResult.withoutCheckpoint()));
        var fixture = service(source, workExecutor(context -> ScheduledWorkResult.completed()));
        var service = fixture.service();
        try {
            service.create(request("retired"), "fixture-secret");
            var view = awaitIdle(service);
            assertThat(view.state()).isEqualTo(ManualBatchService.State.SUSPENDED);
            assertThat(view.code()).isEqualTo("schedule.error.source-publication-changed");
            verify(source, never()).prepare(any());
            verify(source, never()).plan(any());
            verify(source, never()).discover(any());
        } finally { service.stop(); }
        assertThatThrownBy(() -> service.create(request(fixture.token()), null)).isInstanceOf(IllegalStateException.class);
    }
}
