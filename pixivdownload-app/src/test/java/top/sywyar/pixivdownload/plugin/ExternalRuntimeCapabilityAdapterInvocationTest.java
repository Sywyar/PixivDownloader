package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import top.sywyar.pixivdownload.ai.AiChatClient;
import top.sywyar.pixivdownload.ai.AiClientException;
import top.sywyar.pixivdownload.ai.AiClientSettings;
import top.sywyar.pixivdownload.ai.model.AiChatMessage;
import top.sywyar.pixivdownload.ai.model.AiChatOptions;
import top.sywyar.pixivdownload.ai.model.AiChatResult;
import top.sywyar.pixivdownload.core.ai.AiChatClientRegistry;
import top.sywyar.pixivdownload.core.gallery.GalleryProjectionProvider;
import top.sywyar.pixivdownload.core.gallery.facet.GalleryFacetPage;
import top.sywyar.pixivdownload.core.gallery.model.GalleryKind;
import top.sywyar.pixivdownload.core.gallery.model.projection.GalleryDataAccess;
import top.sywyar.pixivdownload.core.gallery.model.projection.GalleryProjectionDescriptor;
import top.sywyar.pixivdownload.core.gallery.model.projection.GalleryProjectionPage;
import top.sywyar.pixivdownload.core.gallery.query.GalleryProjectionQuery;
import top.sywyar.pixivdownload.core.gallery.runtime.GalleryCapabilityRegistry;
import top.sywyar.pixivdownload.core.narration.NarrationEngineRegistry;
import top.sywyar.pixivdownload.core.notification.NotificationSinkRegistry;
import top.sywyar.pixivdownload.core.push.PushChannelRegistry;
import top.sywyar.pixivdownload.notification.NotificationScenario;
import top.sywyar.pixivdownload.notification.NotificationSink;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginCapabilityContributionRegistrar;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.AiChatClientCapabilityAdapter;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.ExternalRuntimeCapabilityAdapter;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.GalleryCapabilityContributionAdapter;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.NarrationVoiceEngineCapabilityAdapter;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.NotificationSinkCapabilityAdapter;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.PushChannelCapabilityAdapter;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityDrain;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityInvocationRegistry;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityPublication;
import top.sywyar.pixivdownload.push.PushChannel;
import top.sywyar.pixivdownload.push.PushChannelSettings;
import top.sywyar.pixivdownload.push.PushChannelType;
import top.sywyar.pixivdownload.push.PushFormat;
import top.sywyar.pixivdownload.push.PushLevel;
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
            PushChannel proxy = channelRegistry.byType(PushChannelType.BARK).orElseThrow();

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
    @DisplayName("阻塞 Gallery provider 查询在 publication 撤回后被真实 drain")
    void blockingGalleryInvocationDrains() throws Exception {
        ExternalCapabilityInvocationRegistry invocation = new ExternalCapabilityInvocationRegistry();
        GalleryCapabilityRegistry galleryRegistry = new GalleryCapabilityRegistry(List.of(), List.of());
        GalleryCapabilityContributionAdapter adapter =
                new GalleryCapabilityContributionAdapter(galleryRegistry, invocation);
        BlockingGate gate = new BlockingGate();
        GalleryProjectionProvider target = new BlockingGalleryProvider(gate);
        try (AnnotationConfigApplicationContext child = child(GalleryProjectionProvider.class, target)) {
            PluginCapabilityContributionRegistrar registrar = registrar(invocation, adapter);
            ExternalCapabilityPublication publication = publish(registrar, child, "gallery", 9L);
            GalleryProjectionProvider proxy = galleryRegistry
                    .resolveProjections(GalleryKind.IMAGE, "pixiv").get(0).provider();

            GalleryProjectionQuery query = new GalleryProjectionQuery(
                    GalleryKind.IMAGE, "pixiv", List.of(), null, null, null, 10);
            drainBlocking(registrar, publication, gate, () -> proxy.page(query));
            assertThat(galleryRegistry.resolveProjections(GalleryKind.IMAGE, "pixiv")).isEmpty();
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
            rawSink.channel = channelRegistry.byType(PushChannelType.BARK).orElseThrow();
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
            assertThat(rawSink.entered.await(5, TimeUnit.SECONDS)).isTrue();

            ExternalCapabilityDrain drain = registrar.withdraw(publication).orElseThrow();
            rawSink.proceed.countDown();
            caller.join(5000L);

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

    private static void drainBlocking(
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
        assertThat(gate.entered.await(5, TimeUnit.SECONDS)).isTrue();
        ExternalCapabilityDrain drain = registrar.withdraw(publication).orElseThrow();
        assertThat(drain.isDrained()).isFalse();
        gate.release.countDown();
        caller.join(5000L);
        assertThat(caller.isAlive()).isFalse();
        assertThat(failure.get()).isNull();
        assertThat(drain.await(Duration.ofSeconds(1))).isTrue();
        registrar.retireDrained(drain);
    }

    private static RenderedMessage message() {
        return new RenderedMessage("title", "body", PushFormat.PLAIN_TEXT, PushLevel.INFO);
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
        public PushChannelType type() {
            return PushChannelType.BARK;
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

    private record BlockingGalleryProvider(BlockingGate gate) implements GalleryProjectionProvider {
        @Override
        public String providerId() {
            return "fixture-gallery";
        }

        @Override
        public List<GalleryProjectionDescriptor> projections() {
            return List.of(new GalleryProjectionDescriptor(
                    "pixiv", GalleryKind.IMAGE, "gallery", "gallery.image", 1,
                    GalleryDataAccess.SHARED, Map.of()));
        }

        @Override
        public GalleryProjectionPage page(GalleryProjectionQuery query) {
            gate.block();
            return GalleryProjectionPage.empty();
        }

        @Override
        public long count(GalleryProjectionQuery query) {
            return 0L;
        }

        @Override
        public GalleryFacetPage facets(GalleryProjectionQuery query) {
            return GalleryFacetPage.empty();
        }
    }

    private record CountingPushChannel(AtomicInteger sends) implements PushChannel {
        @Override
        public PushChannelType type() {
            return PushChannelType.BARK;
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
