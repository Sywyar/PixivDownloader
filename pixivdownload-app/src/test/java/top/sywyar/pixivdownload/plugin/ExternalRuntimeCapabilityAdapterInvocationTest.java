package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import top.sywyar.pixivdownload.ai.AiChatClient;
import top.sywyar.pixivdownload.gui.importing.DesktopFileImportToolRegistry;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiText;
import top.sywyar.pixivdownload.plugin.api.gui.importing.DesktopFileImportTool;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.DesktopFileImportToolCapabilityAdapter;
import top.sywyar.pixivdownload.ai.AiClientException;
import top.sywyar.pixivdownload.ai.AiClientSettings;
import top.sywyar.pixivdownload.ai.model.AiChatMessage;
import top.sywyar.pixivdownload.ai.model.AiChatOptions;
import top.sywyar.pixivdownload.ai.model.AiChatResult;
import top.sywyar.pixivdownload.core.ai.AiChatClientRegistry;
import top.sywyar.pixivdownload.core.narration.NarrationEngineRegistry;
import top.sywyar.pixivdownload.core.notification.NotificationSinkRegistry;
import top.sywyar.pixivdownload.core.push.PushChannelRegistry;
import top.sywyar.pixivdownload.notification.NotificationScenario;
import top.sywyar.pixivdownload.notification.NotificationSeverity;
import top.sywyar.pixivdownload.notification.NotificationSink;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginCapabilityContributionRegistrar;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.AiChatClientCapabilityAdapter;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.ExternalRuntimeCapabilityAdapter;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.NarrationVoiceEngineCapabilityAdapter;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.NotificationSinkCapabilityAdapter;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.PushChannelCapabilityAdapter;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityDrain;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityInvocationRegistry;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityPublication;
import top.sywyar.pixivdownload.push.PushChannel;
import top.sywyar.pixivdownload.push.PushChannelId;
import top.sywyar.pixivdownload.push.PushChannelSettings;
import top.sywyar.pixivdownload.push.PushFormat;
import top.sywyar.pixivdownload.push.PushResult;
import top.sywyar.pixivdownload.push.RenderedMessage;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationAudio;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationVoiceEngine;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationVoiceMode;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationVoiceRequest;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("外置能力 adapter 调用租约")
class ExternalRuntimeCapabilityAdapterInvocationTest {

    private static final PushChannelId PUSH_CHANNEL_ID = new PushChannelId("test-channel");

    @Test
    @DisplayName("未知导入提供者通过公共入口发布，撤回排空预览且旧句柄拒绝命令")
    void fileImportPublicationDrains() throws Exception {
        var invocation = new ExternalCapabilityInvocationRegistry();
        var registry = new DesktopFileImportToolRegistry();
        var adapter = new DesktopFileImportToolCapabilityAdapter(registry, invocation);
        var gate = new BlockingGate();
        var target = new DesktopFileImportTool.Source() {
            public DesktopFileImportTool.Description description() {
                return new DesktopFileImportTool.Description(DesktopUiText.raw("Fixture"), DesktopUiText.raw("Help"));
            }
            public DesktopFileImportTool.Result<DesktopFileImportTool.Preview> inspect(DesktopFileImportTool.Request request) {
                gate.block();
                return DesktopFileImportTool.Result.success(new DesktopFileImportTool.Preview("token", 0, 0, 0, 0, 0, List.of()));
            }
            public DesktopFileImportTool.Result<DesktopFileImportTool.Status> start(String token, String secret) {
                return DesktopFileImportTool.Result.failure(DesktopUiText.key("fixture.unavailable"));
            }
            public DesktopFileImportTool.Status status() {
                return new DesktopFileImportTool.Status(DesktopFileImportTool.Phase.IDLE, 0, 0, 0, 0, 0, List.of());
            }
            public void cancel() {}
            public java.util.Optional<DesktopFileImportTool.Completion> pendingCompletion() {
                return java.util.Optional.of(new DesktopFileImportTool.Completion("finished", 2));
            }
            public void acknowledgeCompletion(String id) { assertThat(id).isEqualTo("finished"); }
        };
        try (var child = child(DesktopFileImportTool.Source.class, target)) {
            var registrar = registrar(invocation, adapter);
            var publication = publish(registrar, child, "unknown-importer", 3);
            var identity = registry.tools().get(0).identity();
            var proxy = registry.source(identity);
            assertThat(proxy.pendingCompletion().orElseThrow().imported()).isEqualTo(2);
            proxy.acknowledgeCompletion("finished");
            assertThat(proxy.start("token", "private").error().key()).isEqualTo("fixture.unavailable");
            var previousDrain = drainBlocking(registrar, publication, gate, () -> {
                var result = proxy.inspect(new DesktopFileImportTool.Request("/records.json", "/source"));
                assertThat(result.value().records()).isZero();
            });
            assertThat(registry.tools()).isEmpty();
            org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, proxy::status);
            org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, proxy::pendingCompletion);
            org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> proxy.acknowledgeCompletion("finished"));
            registrar.acknowledgeRetired(previousDrain);
            child.close();
            assertThat(registrar.releaseRetirementProof(previousDrain)).isTrue();
            try (var replacement = child(DesktopFileImportTool.Source.class, target)) {
                var next = publish(registrar, replacement, "unknown-importer", 4);
                try {
                assertThat(registry.tools().get(0).identity()).isNotEqualTo(identity);
                org.junit.jupiter.api.Assertions.assertThrows(DesktopFileImportTool.OperationException.class, () -> registry.source(identity));
                org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> proxy.start("token", ""));
                } finally {
                var drain = registrar.withdraw(next).orElseThrow();
                assertThat(drain.await(Duration.ofSeconds(1))).isTrue();
                registrar.retireDrained(drain);
                registrar.acknowledgeRetired(drain);
                replacement.close();
                assertThat(registrar.releaseRetirementProof(drain)).isTrue();
                }
            }
        }
    }

    @Test
    @DisplayName("阻塞 AI 调用在 publication 撤回后被真实 drain")
    void blockingAiInvocationDrains() throws Exception {
        ExternalCapabilityInvocationRegistry invocation = new ExternalCapabilityInvocationRegistry();
        AiChatClientRegistry clientRegistry = new AiChatClientRegistry();
        AiChatClientCapabilityAdapter adapter = new AiChatClientCapabilityAdapter(clientRegistry, invocation);
        BlockingGate gate = new BlockingGate();
        AiChatClient target = new BlockingAiClient(gate);
        try (AnnotationConfigApplicationContext child = child(AiChatClient.class, target)) {
            PluginCapabilityContributionRegistrar registrar = registrar(invocation, adapter);
            ExternalCapabilityPublication publication = publish(registrar, child, "ai", 5L);
            AiChatClient proxy = clientRegistry.active().orElseThrow();

            drainBlocking(registrar, publication, gate,
                    () -> proxy.chat("test", List.of(), AiChatOptions.defaults()));
            assertThat(clientRegistry.active()).isEmpty();
        }
    }

    @Test
    @DisplayName("阻塞 narration 合成在 publication 撤回后被真实 drain")
    void blockingNarrationInvocationDrains() throws Exception {
        ExternalCapabilityInvocationRegistry invocation = new ExternalCapabilityInvocationRegistry();
        NarrationEngineRegistry engineRegistry = new NarrationEngineRegistry(List.of());
        NarrationVoiceEngineCapabilityAdapter adapter =
                new NarrationVoiceEngineCapabilityAdapter(engineRegistry, invocation);
        BlockingGate gate = new BlockingGate();
        NarrationVoiceEngine target = new BlockingNarrationEngine(gate);
        try (AnnotationConfigApplicationContext child = child(NarrationVoiceEngine.class, target)) {
            PluginCapabilityContributionRegistrar registrar = registrar(invocation, adapter);
            ExternalCapabilityPublication publication = publish(registrar, child, "tts", 6L);
            NarrationVoiceEngine proxy = engineRegistry.byId("fixture").orElseThrow();

            drainBlocking(registrar, publication, gate, () -> proxy.synthesize(
                    NarrationVoiceMode.VOICE_DESIGN,
                    NarrationVoiceRequest.of("hello", "calm")));
            assertThat(engineRegistry.count()).isZero();
        }
    }

    @Test
    @DisplayName("阻塞 push 发送在 publication 撤回后被真实 drain")
    void blockingPushInvocationDrains() throws Exception {
        ExternalCapabilityInvocationRegistry invocation = new ExternalCapabilityInvocationRegistry();
        PushChannelRegistry channelRegistry = new PushChannelRegistry(List.of());
        PushChannelCapabilityAdapter adapter = new PushChannelCapabilityAdapter(channelRegistry, invocation);
        BlockingGate gate = new BlockingGate();
        PushChannel target = new BlockingPushChannel(gate);
        try (AnnotationConfigApplicationContext child = child(PushChannel.class, target)) {
            PluginCapabilityContributionRegistrar registrar = registrar(invocation, adapter);
            ExternalCapabilityPublication publication = publish(registrar, child, "push", 7L);
            PushChannel proxy = channelRegistry.byId(new PushChannelId("test-channel")).orElseThrow();

            drainBlocking(registrar, publication, gate, () -> proxy.send(message()));
            assertThat(channelRegistry.channels()).isEmpty();
        }
    }

    @Test
    @DisplayName("阻塞 notification 下发在 publication 撤回后被真实 drain")
    void blockingNotificationInvocationDrains() throws Exception {
        ExternalCapabilityInvocationRegistry invocation = new ExternalCapabilityInvocationRegistry();
        NotificationSinkRegistry sinkRegistry = new NotificationSinkRegistry(List.of());
        NotificationSinkCapabilityAdapter adapter = new NotificationSinkCapabilityAdapter(sinkRegistry, invocation);
        BlockingGate gate = new BlockingGate();
        NotificationSink target = new BlockingNotificationSink(gate);
        try (AnnotationConfigApplicationContext child = child(NotificationSink.class, target)) {
            PluginCapabilityContributionRegistrar registrar = registrar(invocation, adapter);
            ExternalCapabilityPublication publication = publish(registrar, child, "notification", 8L);
            NotificationSink proxy = sinkRegistry.sinks().get(0);

            drainBlocking(registrar, publication, gate,
                    () -> proxy.deliver(NotificationScenario.RUN_FAILED, Locale.ENGLISH, Map.of()));
            assertThat(sinkRegistry.sinks()).isEmpty();
        }
    }

    @Test
    @DisplayName("PushNotificationSink 到同 publication PushChannel 的嵌套调用跨撤回复用 exact owner")
    void pushNotificationNestedCallSurvivesAdmissionWithdrawal() throws Exception {
        ExternalCapabilityInvocationRegistry invocation = new ExternalCapabilityInvocationRegistry();
        PushChannelRegistry channelRegistry = new PushChannelRegistry(List.of());
        NotificationSinkRegistry sinkRegistry = new NotificationSinkRegistry(List.of());
        PushChannelCapabilityAdapter pushAdapter = new PushChannelCapabilityAdapter(channelRegistry, invocation);
        NotificationSinkCapabilityAdapter sinkAdapter =
                new NotificationSinkCapabilityAdapter(sinkRegistry, invocation);
        AtomicInteger sends = new AtomicInteger();
        PushChannel rawChannel = new CountingPushChannel(sends);
        NestedPushNotificationSink rawSink = new NestedPushNotificationSink();
        try (AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext()) {
            child.registerBean("pushChannel", PushChannel.class, () -> rawChannel);
            child.registerBean("notificationSink", NotificationSink.class, () -> rawSink);
            child.refresh();
            PluginCapabilityContributionRegistrar registrar = registrar(
                    invocation, pushAdapter, sinkAdapter);
            ExternalCapabilityPublication publication = publish(registrar, child, "push", 11L);
            rawSink.channel = channelRegistry.byId(PUSH_CHANNEL_ID).orElseThrow();
            NotificationSink sinkProxy = sinkRegistry.sinks().get(0);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread caller = new Thread(() -> {
                try {
                    sinkProxy.deliver(NotificationScenario.RUN_FAILED, Locale.ENGLISH, Map.of());
                } catch (Throwable thrown) {
                    failure.set(thrown);
                }
            }, "push-notification-nested-call");
            caller.start();
            ExternalCapabilityDrain drain;
            try {
                assertThat(rawSink.entered.await(5, TimeUnit.SECONDS)).isTrue();
                drain = registrar.withdraw(publication).orElseThrow();
            } finally {
                rawSink.proceed.countDown();
                caller.join(5000L);
            }

            assertThat(caller.isAlive()).isFalse();
            assertThat(failure.get()).isNull();
            assertThat(sends.get()).isEqualTo(1);
            assertThat(drain.await(Duration.ofSeconds(1))).isTrue();
            registrar.retireDrained(drain);
        }
    }

    private static PluginCapabilityContributionRegistrar registrar(
            ExternalCapabilityInvocationRegistry invocation,
            ExternalRuntimeCapabilityAdapter... adapters) {
        return new PluginCapabilityContributionRegistrar(
                List.of(), List.of(), List.of(adapters), invocation);
    }

    private static ExternalCapabilityPublication publish(
            PluginCapabilityContributionRegistrar registrar,
            AnnotationConfigApplicationContext child,
            String owner,
            long generation) {
        PluginCapabilityContributionRegistrar.PreparedOwner prepared = registrar.allocateOwner(
                owner, owner, generation);
        registrar.prepareInto(prepared, child);
        return registrar.publish(prepared);
    }

    private static <T> AnnotationConfigApplicationContext child(Class<T> type, T target) {
        AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext();
        child.registerBean(type.getName(), type, () -> target);
        child.refresh();
        return child;
    }

    private static ExternalCapabilityDrain drainBlocking(
            PluginCapabilityContributionRegistrar registrar,
            ExternalCapabilityPublication publication,
            BlockingGate gate,
            CheckedInvocation invocation) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                invocation.run();
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        }, "external-capability-blocking-fixture");
        caller.start();
        ExternalCapabilityDrain drain;
        try {
            assertThat(gate.entered.await(5, TimeUnit.SECONDS)).isTrue();
            drain = registrar.withdraw(publication).orElseThrow();
            assertThat(drain.isDrained()).isFalse();
        } finally {
            gate.release.countDown();
            caller.join(5000L);
        }
        assertThat(caller.isAlive()).isFalse();
        assertThat(failure.get()).isNull();
        assertThat(drain.await(Duration.ofSeconds(1))).isTrue();
        registrar.retireDrained(drain);
        return drain;
    }

    private static RenderedMessage message() {
        return new RenderedMessage(
                "title", "body", PushFormat.PLAIN_TEXT, NotificationSeverity.INFO);
    }

    @FunctionalInterface
    private interface CheckedInvocation {
        void run() throws Exception;
    }

    private static final class BlockingGate {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        private void block() {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("fixture release timeout");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("fixture interrupted");
            }
        }
    }

    private record BlockingAiClient(BlockingGate gate) implements AiChatClient {
        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public AiChatResult chat(String callType, List<AiChatMessage> messages, AiChatOptions options)
                throws AiClientException {
            gate.block();
            return new AiChatResult("ok", "stop", 1, 1, 2);
        }

        @Override
        public AiChatResult chatTest(
                String callType,
                AiClientSettings settings,
                List<AiChatMessage> messages,
                AiChatOptions options) throws AiClientException {
            return chat(callType, messages, options);
        }
    }

    private record BlockingNarrationEngine(BlockingGate gate) implements NarrationVoiceEngine {
        @Override
        public String id() {
            return "fixture";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public NarrationAudio synthesize(NarrationVoiceMode mode, NarrationVoiceRequest req) {
            gate.block();
            return new NarrationAudio(new byte[]{1, 2}, "audio/wav");
        }
    }

    private record BlockingPushChannel(BlockingGate gate) implements PushChannel {
        @Override
        public PushChannelId type() {
            return PUSH_CHANNEL_ID;
        }

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public List<PushFormat> supportedFormats() {
            return List.of(PushFormat.PLAIN_TEXT);
        }

        @Override
        public PushResult send(RenderedMessage message) {
            gate.block();
            return PushResult.ok(type());
        }

        @Override
        public PushResult sendTest(PushChannelSettings settings, RenderedMessage message) {
            return send(message);
        }
    }

    private record BlockingNotificationSink(BlockingGate gate) implements NotificationSink {
        @Override
        public String medium() {
            return "fixture";
        }

        @Override
        public void deliver(NotificationScenario scenario, Locale locale, Map<String, String> placeholders) {
            gate.block();
        }

        @Override
        public void verifyRenderable(NotificationScenario scenario) {
        }
    }

    private record CountingPushChannel(AtomicInteger sends) implements PushChannel {
        @Override
        public PushChannelId type() {
            return PUSH_CHANNEL_ID;
        }

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public List<PushFormat> supportedFormats() {
            return List.of(PushFormat.PLAIN_TEXT);
        }

        @Override
        public PushResult send(RenderedMessage message) {
            sends.incrementAndGet();
            return PushResult.ok(type());
        }

        @Override
        public PushResult sendTest(PushChannelSettings settings, RenderedMessage message) {
            return send(message);
        }
    }

    private static final class NestedPushNotificationSink implements NotificationSink {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch proceed = new CountDownLatch(1);
        private PushChannel channel;

        @Override
        public String medium() {
            return "push";
        }

        @Override
        public void deliver(NotificationScenario scenario, Locale locale, Map<String, String> placeholders) {
            entered.countDown();
            try {
                if (!proceed.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("nested fixture timeout");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("nested fixture interrupted");
            }
            channel.send(message());
        }

        @Override
        public void verifyRenderable(NotificationScenario scenario) {
        }
    }
}
