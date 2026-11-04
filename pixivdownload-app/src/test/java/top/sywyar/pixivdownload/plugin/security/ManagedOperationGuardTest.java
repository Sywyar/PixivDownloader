package top.sywyar.pixivdownload.plugin.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.api.web.page.*;
import top.sywyar.pixivdownload.plugin.registry.web.PageContributionRegistry;
import top.sywyar.pixivdownload.plugin.web.page.WebPublicationStore;
import top.sywyar.pixivdownload.plugin.api.security.*;
import top.sywyar.pixivdownload.plugin.api.service.*;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.OperationVerificationCapabilityAdapter;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.*;

import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.*;
import static top.sywyar.pixivdownload.plugin.api.security.OperationGuard.Status.*;

@DisplayName("未知业务 owner 的受管验证、证明绑定与真实动作排空")
class ManagedOperationGuardTest {
    final ExternalCapabilityInvocationRegistry registry = new ExternalCapabilityInvocationRegistry();
    @TempDir java.nio.file.Path root;
    final PageContributionRegistry catalog = new PageContributionRegistry(List.of(), registry);
    WebPublicationStore store;
    OperationVerificationGate gate;
    final AtomicReference<String> revision = new AtomicReference<>("r1");

    @BeforeEach void initialize() {
        store = new WebPublicationStore(root.resolve("publications"), root.resolve("config/enrolled"));
        gate = new OperationVerificationGate(registry,
                Map.of("form:submit", List.of("neutral"), "form:other", List.of("neutral")), Clock.systemUTC(),
                store, catalog, new OperationAdmissions());
    }

    private ExternalCapabilityPreparation prepare(String owner) {
        var preparation = registry.allocatePreparation(owner, owner, 1);
        registry.installPreparation(preparation);
        return preparation;
    }

    private PluginServices consumer(boolean declared) {
        var preparation = prepare("form");
        var services = registry.servicesFor(preparation,
                declared ? List.of(OperationVerification.IMPORT) : List.of());
        String schema = "{\"type\":\"object\",\"properties\":{\"changed\":{\"type\":\"boolean\"},"
                + "\"private-context\":{\"type\":\"boolean\"}},\"additionalProperties\":false}";
        var targets = List.of("submit", "other").stream().map(name -> new PageInteraction.Target(name,
                name, "form:" + name, "form", schema, List.of(OperationVerification.INTERACTION), 1)).toList();
        var page = new EditablePage("form:page", "page", "form", "/form.html", true,
                List.of(new PageRegion("form", "form", "", true, false)), "", "", List.of(), targets);
        catalog.publish(new PageContributionRegistry.Entry(preparation.owner(), "form-token", List.of(), List.of(page), List.of(), null));
        registry.publish(preparation);
        return services;
    }

    private ExternalCapabilityPublication provider(Function<OperationVerificationRequest, OperationVerificationResult> verify) {
        var preparation = prepare("verifier");
        registry.prepareService(preparation, OperationVerificationCapabilityAdapter.key("neutral"), new OperationVerification() {
            public String policyId() { return "neutral"; }
            public String generation() { return revision.get(); }
            public Policy policy() { return new Policy("p1", "", INTERACTION, java.util.List.of()); }
            public OperationVerificationResult verify(OperationVerificationRequest request) { return verify.apply(request); }
        });
        return registry.publish(preparation);
    }

    private static OperationVerificationResult approved() {
        return new OperationVerificationResult(OperationVerificationResult.Status.APPROVED, "");
    }

    @Test @DisplayName("通用导入允许已批准策略而不开放全局发现，具体策略导入不能代替受管权限")
    void importDoesNotExposeProviderDiscovery() throws Exception {
        var services = consumer(true);
        provider(request -> approved());
        assertThat(services.findAll(OperationVerification.IMPORT)).isEmpty();
        assertThatThrownBy(() -> services.findAll(OperationVerificationCapabilityAdapter.key("neutral")))
                .isInstanceOf(ServiceAccessException.class);
        assertThat(gate.create(services).execute("form:submit", "user", "{}", "", Map.of(), () -> "saved").status()).isEqualTo(APPROVED);
    }

    @Test @DisplayName("非英文界面下的业务日志仍为英文，并关联结果而不记录主体或上下文")
    void logsEnglishOutcomeWithoutPrivateInputs() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(OperationVerificationGate.class);
        var capture = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        var locale = java.util.Locale.getDefault();
        capture.start(); logger.addAppender(capture);
        String previousCorrelation = org.slf4j.MDC.get("operationVerificationId");
        org.slf4j.MDC.put("operationVerificationId", "outer-operation");
        try {
            java.util.Locale.setDefault(java.util.Locale.JAPAN);
            var guard = gate.create(consumer(true));
            var publication = provider(request -> {
                String correlation = org.slf4j.MDC.get("operationVerificationId");
                assertThat(correlation).isNotBlank().isNotEqualTo("outer-operation");
                assertThat(correlation).isNotEqualTo(request.attemptId());
                return approved();
            });
            assertThat(guard.execute("form:submit", "private-subject", "{\"private-context\":true}", "", Map.of(), () -> "private-result").status())
                    .isEqualTo(APPROVED);
            assertThat(org.slf4j.MDC.get("operationVerificationId")).isEqualTo("outer-operation");
            registry.withdraw(publication);
            assertThat(guard.execute("form:submit", "private-subject", "{}", "", Map.of(), () -> "unexpected").status()).isEqualTo(UNAVAILABLE);
            var messages = capture.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList();
            assertThat(messages).anyMatch(message -> message.startsWith("Operation verification finished") && message.contains("result=APPROVED"));
            assertThat(messages).anyMatch(message -> message.contains("reason=UNAVAILABLE"));
            assertThat(messages).anyMatch(message -> message.contains("result=UNAVAILABLE"));
            assertThat(String.join("\n", messages)).doesNotContain("private-subject", "private-context", "private-result", "operation.log.");
            var start = messages.stream().filter(message -> message.startsWith("Operation verification started")).findFirst().orElseThrow();
            String id = start.substring(start.indexOf("id=") + 3, start.indexOf(" operation="));
            assertThat(messages.stream().filter(message -> message.contains(id)).count()).isEqualTo(3);
            assertThat(org.slf4j.MDC.get("operationVerificationId")).isEqualTo("outer-operation");
        } finally {
            java.util.Locale.setDefault(locale); logger.detachAppender(capture); capture.stop();
            if (previousCorrelation == null) org.slf4j.MDC.remove("operationVerificationId");
            else org.slf4j.MDC.put("operationVerificationId", previousCorrelation);
        }
    }

    @Test @DisplayName("超过验证预算的通过结果不能执行动作，provider 实际返回前不释放租约")
    void timeoutCannotReleaseRunningProviderOrApproveAction() throws Exception {
        var elapsed = new java.util.concurrent.atomic.AtomicLong();
        var bounded = new OperationVerificationGate(registry, Map.of("form:submit", List.of("neutral")),
                Clock.systemUTC(), store, catalog, new OperationAdmissions(), elapsed::get);
        var guard = bounded.create(consumer(true));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var publication = provider(request -> {
            elapsed.set(TimeUnit.SECONDS.toNanos(11)); entered.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout"); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
            return approved();
        });
        var executor = Executors.newSingleThreadExecutor();
        try {
            var running = executor.submit(() -> guard.execute("form:submit", "user", "{}", "", Map.of(), calls::incrementAndGet));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var drain = registry.withdraw(publication).orElseThrow();
            assertThat(drain.isDrained()).isFalse();
            release.countDown();
            assertThat(running.get(5, TimeUnit.SECONDS).status()).isEqualTo(UNAVAILABLE);
            assertThat(calls).hasValue(0);
            assertThat(drain.isDrained()).isTrue();
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    private static OperationVerificationResult challenge(OperationVerificationRequest request) {
        return request.response() && request.proof().equals("answer") ? approved()
                : new OperationVerificationResult(OperationVerificationResult.Status.CHALLENGE, "question");
    }

    @Test @DisplayName("伪造 owner、未导入的策略和未知操作都不执行业务")
    void enforcesOwnerAndImports() throws Exception {
        var guard = gate.create(consumer(false));
        provider(request -> approved());
        var calls = new AtomicInteger();
        assertThat(guard.execute("core:login", "user", "{}", "", Map.of(), calls::incrementAndGet).status()).isEqualTo(INVALID);
        assertThat(guard.execute("form:missing", "user", "{}", "", Map.of(), calls::incrementAndGet).status()).isEqualTo(UNAVAILABLE);
        assertThat(guard.execute("form:submit", "user", "{}", "", Map.of(), calls::incrementAndGet).status()).isEqualTo(UNAVAILABLE);
        assertThat(calls).hasValue(0);
        assertThatThrownBy(() -> gate.create(org.mockito.Mockito.mock(PluginServices.class)))
                .isInstanceOf(ServiceAccessException.class);
    }

    @Test @DisplayName("验证通过后撤回 provider 仍等待真实业务退出，同时拒绝新命令")
    void drainsBusinessAction() throws Exception {
        var guard = gate.create(consumer(true));
        var publication = provider(request -> approved());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var running = executor.submit(() -> guard.execute("form:submit", "user", "{}", "", Map.of(), () -> {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("timeout"); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException(failure); }
                return "saved";
            }));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var drain = registry.withdraw(publication).orElseThrow();
            assertThat(drain.isDrained()).isFalse();
            assertThat(guard.execute("form:submit", "user", "{}", "", Map.of(), () -> "unexpected").status())
                    .isEqualTo(UNAVAILABLE);
            release.countDown();
            assertThat(running.get(5, TimeUnit.SECONDS).value()).isEqualTo("saved");
            assertThat(drain.isDrained()).isTrue();
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test @DisplayName("挑战绑定操作、上下文和配置代际，修改后拒绝且消费旧挑战")
    void bindsOperationContextAndRevision() throws Exception {
        var guard = gate.create(consumer(true));
        provider(ManagedOperationGuardTest::challenge);
        var calls = new AtomicInteger();
        var first = guard.execute("form:submit", "user", "{}", "", Map.of(), calls::incrementAndGet);
        assertThat(first.status()).isEqualTo(CHALLENGE);
        assertThat(guard.execute("form:other", "user", "{}", first.token(), Map.of("neutral", "answer"),
                calls::incrementAndGet).status()).isEqualTo(STALE);
        first = guard.execute("form:submit", "user", "{}", "", Map.of(), calls::incrementAndGet);
        assertThat(guard.execute("form:submit", "user", "{\"changed\":true}", first.token(), Map.of("neutral", "answer"),
                calls::incrementAndGet).status()).isEqualTo(STALE);
        first = guard.execute("form:submit", "user", "{}", "", Map.of(), calls::incrementAndGet);
        revision.set("r2");
        assertThat(guard.execute("form:submit", "user", "{}", first.token(), Map.of("neutral", "answer"),
                calls::incrementAndGet).status()).isEqualTo(STALE);
        assertThat(calls).hasValue(0);
    }

    @Test @DisplayName("同一证明并发提交最多执行一次，业务 IO 失败不重放")
    void consumesOnceAndDoesNotRetry() throws Exception {
        var guard = gate.create(consumer(true));
        provider(ManagedOperationGuardTest::challenge);
        var calls = new AtomicInteger();
        var first = guard.execute("form:submit", "user", "{}", "", Map.of(), calls::incrementAndGet);
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            Callable<OperationGuard.Status> submit = () -> {
                start.await();
                return guard.execute("form:submit", "user", "{}", first.token(), Map.of("neutral", "answer"),
                        calls::incrementAndGet).status();
            };
            var a = executor.submit(submit);
            var b = executor.submit(submit);
            start.countDown();
            assertThat(List.of(a.get(5, TimeUnit.SECONDS), b.get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(APPROVED, STALE);
            assertThat(calls).hasValue(1);
        } finally { start.countDown(); executor.shutdownNow(); }
        var next = guard.execute("form:submit", "user", "{}", "", Map.of(), calls::incrementAndGet);
        assertThatThrownBy(() -> guard.execute("form:submit", "user", "{}", next.token(),
                Map.of("neutral", "answer"), () -> { calls.incrementAndGet(); throw new IOException("write failed"); }))
                .isInstanceOf(IOException.class);
        assertThat(guard.execute("form:submit", "user", "{}", next.token(), Map.of("neutral", "answer"),
                calls::incrementAndGet).status()).isEqualTo(STALE);
        assertThat(calls).hasValue(2);
    }
}
