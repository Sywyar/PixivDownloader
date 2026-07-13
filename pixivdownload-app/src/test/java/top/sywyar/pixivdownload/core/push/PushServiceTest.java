package top.sywyar.pixivdownload.core.push;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceResolvable;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityUnavailableException;
import top.sywyar.pixivdownload.push.PushChannel;
import top.sywyar.pixivdownload.push.PushChannelSettings;
import top.sywyar.pixivdownload.push.PushChannelType;
import top.sywyar.pixivdownload.push.PushFormat;
import top.sywyar.pixivdownload.push.PushMessage;
import top.sywyar.pixivdownload.push.PushResult;
import top.sywyar.pixivdownload.push.RenderedMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

@DisplayName("PushService 派发器单元测试")
class PushServiceTest {

    /** 不依赖 Spring 容器：用最简 MessageSource 回落 code，仅供日志取文案。 */
    private static final AppMessages MESSAGES = new AppMessages(new MessageSource() {
        @Override
        public String getMessage(String code, Object[] args, String defaultMessage, Locale locale) {
            return defaultMessage != null ? defaultMessage : code;
        }

        @Override
        public String getMessage(String code, Object[] args, Locale locale) {
            return code;
        }

        @Override
        public String getMessage(MessageSourceResolvable resolvable, Locale locale) {
            return "";
        }
    });

    /** 无状态，全测试共用一个实例。 */
    private static final PushFormatConverter CONVERTER = new PushFormatConverter();

    @Test
    @DisplayName("状态发布失败时推送 owner 与可见快照保持原子一致")
    void registryStatePublicationFailureKeepsOwnerAndSnapshotAtomic() {
        AtomicReference<Throwable> nextFailure = new AtomicReference<>();
        PushChannelRegistry registry = new PushChannelRegistry(List.of(), () -> throwPending(nextFailure));
        FakeChannel first = new FakeChannel(PushChannelType.BARK, true);
        FakeChannel second = new FakeChannel(PushChannelType.TELEGRAM, true);
        registry.registerPrepared("owner-a", 1L, List.of(
                new PushChannelRegistry.PreparedChannel(PushChannelType.BARK, first, "first.Type")));
        List<PushChannel> beforePublish = registry.channels();

        for (Throwable expected : failures("publish")) {
            nextFailure.set(expected);
            assertThat(catchThrowable(() -> registry.registerPrepared("owner-b", 2L, List.of(
                    new PushChannelRegistry.PreparedChannel(
                            PushChannelType.TELEGRAM, second, "second.Type")))))
                    .isSameAs(expected);
            assertThat(registry.channels()).isSameAs(beforePublish);
            assertThat(registry.byType(PushChannelType.TELEGRAM)).isEmpty();
        }

        registry.registerPrepared("owner-b", 2L, List.of(
                new PushChannelRegistry.PreparedChannel(PushChannelType.TELEGRAM, second, "second.Type")));
        List<PushChannel> beforeWithdraw = registry.channels();
        for (Throwable expected : failures("withdraw")) {
            nextFailure.set(expected);
            assertThat(catchThrowable(() -> registry.unregisterPrepared("owner-b", 2L))).isSameAs(expected);
            assertThat(registry.channels()).isSameAs(beforeWithdraw);
            assertThat(registry.byType(PushChannelType.TELEGRAM)).containsSame(second);
        }
        registry.unregisterPrepared("owner-b", 2L);
        assertThat(registry.channels()).containsExactly(first);
    }

    @Test
    @DisplayName("无活动 push 插件通道时广播为空、定向与测试路径明确 SKIPPED")
    void unavailableWhenNoPluginChannelsRegistered() {
        PushService service = service();

        List<PushResult> results = service.push(PushMessage.of("标题", "正文"));

        assertThat(results).isEmpty();
        assertThat(service.push(PushChannelType.BARK, PushMessage.of("t", "c")).status())
                .isEqualTo(PushResult.Status.SKIPPED);
        assertThat(service.test(List.of(new FakeSettings(PushChannelType.BARK, true)), PushMessage.of("t", "c")))
                .singleElement()
                .extracting(PushResult::status)
                .isEqualTo(PushResult.Status.SKIPPED);
    }

    @Test
    @DisplayName("仅向已配置的通道广播，未配置的通道被跳过")
    void broadcastsOnlyToConfiguredChannels() {
        FakeChannel configured = new FakeChannel(PushChannelType.BARK, true);
        FakeChannel notConfigured = new FakeChannel(PushChannelType.TELEGRAM, false);
        PushService service = service(configured, notConfigured);

        List<PushResult> results = service.push(PushMessage.of("标题", "正文"));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).channel()).isEqualTo(PushChannelType.BARK);
        assertThat(results.get(0).isOk()).isTrue();
        assertThat(configured.received).hasSize(1);
        assertThat(notConfigured.received).isEmpty();
        // 仅支持纯文本的通道：Markdown 源消息被协商 + 转换为纯文本后才交给通道。
        assertThat(configured.received.get(0).format()).isEqualTo(PushFormat.PLAIN_TEXT);
    }

    @Test
    @DisplayName("单个通道抛异常被隔离，不影响其它通道")
    void oneChannelThrowingDoesNotBreakOthers() {
        FakeChannel exploding = new FakeChannel(PushChannelType.DINGTALK, true);
        exploding.toThrow = new RuntimeException("boom");
        FakeChannel healthy = new FakeChannel(PushChannelType.BARK, true);
        PushService service = service(exploding, healthy);

        List<PushResult> results = service.push(PushMessage.of("标题", "正文"));

        assertThat(results).hasSize(2);
        assertThat(results).anyMatch(r -> r.channel() == PushChannelType.DINGTALK
                && r.status() == PushResult.Status.FAILED);
        assertThat(results).anyMatch(r -> r.channel() == PushChannelType.BARK && r.isOk());
        assertThat(healthy.received).hasSize(1);
    }

    @Test
    @DisplayName("通道在配置探测前被撤回时使用注册快照诊断且不让异常逃逸")
    void withdrawnChannelDuringConfigurationProbeFailsSoft() {
        FakeChannel withdrawn = new FakeChannel(PushChannelType.BARK, true);
        PushService service = service(withdrawn);
        withdrawn.configuredFailure = new ExternalCapabilityUnavailableException("withdrawn");
        withdrawn.failTypeLookup = true;

        List<PushResult> results = service.push(PushMessage.of("标题", "正文"));

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.channel()).isEqualTo(PushChannelType.BARK);
            assertThat(result.status()).isEqualTo(PushResult.Status.FAILED);
        });
    }

    @Test
    @DisplayName("定向发送：通道不存在 / 未配置时返回 SKIPPED")
    void targetedSendSkipsWhenAbsentOrUnconfigured() {
        FakeChannel unconfigured = new FakeChannel(PushChannelType.BARK, false);
        PushService service = service(unconfigured);

        assertThat(service.push(PushChannelType.BARK, PushMessage.of("t", "c")).status())
                .isEqualTo(PushResult.Status.SKIPPED);
        assertThat(service.push(PushChannelType.TELEGRAM, PushMessage.of("t", "c")).status())
                .isEqualTo(PushResult.Status.SKIPPED);
    }

    @Test
    @DisplayName("测试路径仅向传入设置对应的通道发送")
    void testPathRoutesBySettingsType() {
        FakeChannel bark = new FakeChannel(PushChannelType.BARK, true);
        FakeChannel telegram = new FakeChannel(PushChannelType.TELEGRAM, true);
        PushService service = service(bark, telegram);

        List<PushResult> results = service.test(
                List.of(new FakeSettings(PushChannelType.BARK, true)),
                PushMessage.of("标题", "正文"));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).channel()).isEqualTo(PushChannelType.BARK);
        assertThat(results.get(0).isOk()).isTrue();
        assertThat(bark.testReceived).hasSize(1);
        assertThat(telegram.testReceived).isEmpty();
    }

    @Test
    @DisplayName("测试路径：设置不完整时返回 SKIPPED，不调用通道")
    void testPathSkipsIncompleteSettings() {
        FakeChannel bark = new FakeChannel(PushChannelType.BARK, true);
        PushService service = service(bark);

        List<PushResult> results = service.test(
                List.of(new FakeSettings(PushChannelType.BARK, false)),
                PushMessage.of("t", "c"));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).status()).isEqualTo(PushResult.Status.SKIPPED);
        assertThat(bark.testReceived).isEmpty();
    }

    private static PushService service(PushChannel... channels) {
        return new PushService(new PushChannelRegistry(List.of(channels)), CONVERTER, MESSAGES);
    }

    private static List<Throwable> failures(String action) {
        return List.of(
                new IllegalStateException("ordinary-" + action),
                new OutOfMemoryError("fatal-" + action),
                new ThreadDeath());
    }

    private static void throwPending(AtomicReference<Throwable> pending) {
        Throwable failure = pending.getAndSet(null);
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    /** 测试用设置快照：可配置 type 与是否完整。 */
    private record FakeSettings(PushChannelType type, boolean complete) implements PushChannelSettings {
        @Override
        public boolean isComplete() {
            return complete;
        }
    }

    /** 测试替身：记录收到的已渲染消息，可配置是否"已配置"以及是否抛异常。声明仅支持纯文本。 */
    private static final class FakeChannel implements PushChannel {
        private final PushChannelType type;
        private final boolean configured;
        private RuntimeException toThrow;
        private RuntimeException configuredFailure;
        private boolean failTypeLookup;
        private final List<RenderedMessage> received = new ArrayList<>();
        private final List<RenderedMessage> testReceived = new ArrayList<>();

        FakeChannel(PushChannelType type, boolean configured) {
            this.type = type;
            this.configured = configured;
        }

        @Override
        public PushChannelType type() {
            if (failTypeLookup) {
                throw new AssertionError("service must use captured push channel type");
            }
            return type;
        }

        @Override
        public boolean isConfigured() {
            if (configuredFailure != null) {
                throw configuredFailure;
            }
            return configured;
        }

        @Override
        public List<PushFormat> supportedFormats() {
            return List.of(PushFormat.PLAIN_TEXT);
        }

        @Override
        public PushResult send(RenderedMessage message) {
            if (toThrow != null) {
                throw toThrow;
            }
            received.add(message);
            return PushResult.ok(type);
        }

        @Override
        public PushResult sendTest(PushChannelSettings settings, RenderedMessage message) {
            if (toThrow != null) {
                throw toThrow;
            }
            testReceived.add(message);
            return PushResult.ok(type);
        }
    }
}
