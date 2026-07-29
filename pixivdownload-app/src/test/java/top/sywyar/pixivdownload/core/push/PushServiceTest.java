package top.sywyar.pixivdownload.core.push;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityUnavailableException;
import top.sywyar.pixivdownload.push.PushChannel;
import top.sywyar.pixivdownload.push.PushChannelId;
import top.sywyar.pixivdownload.push.PushChannelSettings;
import top.sywyar.pixivdownload.push.PushFormat;
import top.sywyar.pixivdownload.push.PushFormatConverter;
import top.sywyar.pixivdownload.push.PushMessage;
import top.sywyar.pixivdownload.push.PushResult;
import top.sywyar.pixivdownload.push.RenderedMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

@DisplayName("PushService 派发器单元测试")
class PushServiceTest {

    /** 无状态，全测试共用一个实例。 */
    private static final PushFormatConverter CONVERTER = new PushFormatConverter();
    private static final PushChannelId CHANNEL_A = new PushChannelId("channel-a");
    private static final PushChannelId CHANNEL_B = new PushChannelId("channel-b");
    private static final PushChannelId CHANNEL_C = new PushChannelId("channel-c");

    @Test
    @DisplayName("状态发布失败时推送 owner 与可见快照保持原子一致")
    void registryStatePublicationFailureKeepsOwnerAndSnapshotAtomic() {
        AtomicReference<Throwable> nextFailure = new AtomicReference<>();
        PushChannelRegistry registry = new PushChannelRegistry(List.of(), () -> throwPending(nextFailure));
        FakeChannel first = new FakeChannel(CHANNEL_A, true);
        FakeChannel second = new FakeChannel(CHANNEL_B, true);
        registry.registerPrepared("owner-a", 1L, List.of(
                new PushChannelRegistry.PreparedChannel(CHANNEL_A, first, "first.Type")));
        List<PushChannel> beforePublish = registry.channels();

        for (Throwable expected : failures("publish")) {
            nextFailure.set(expected);
            assertThat(catchThrowable(() -> registry.registerPrepared("owner-b", 2L, List.of(
                    new PushChannelRegistry.PreparedChannel(
                            CHANNEL_B, second, "second.Type")))))
                    .isSameAs(expected);
            assertThat(registry.channels()).isSameAs(beforePublish);
            assertThat(registry.byId(new PushChannelId("channel-b"))).isEmpty();
        }

        registry.registerPrepared("owner-b", 2L, List.of(
                new PushChannelRegistry.PreparedChannel(CHANNEL_B, second, "second.Type")));
        List<PushChannel> beforeWithdraw = registry.channels();
        for (Throwable expected : failures("withdraw")) {
            nextFailure.set(expected);
            assertThat(catchThrowable(() -> registry.unregisterPrepared("owner-b", 2L))).isSameAs(expected);
            assertThat(registry.channels()).isSameAs(beforeWithdraw);
            assertThat(registry.byId(new PushChannelId("channel-b"))).containsSame(second);
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
        assertThat(service.push(CHANNEL_A, PushMessage.of("t", "c")).status())
                .isEqualTo(PushResult.Status.SKIPPED);
        assertThat(service.test(List.of(new FakeSettings(CHANNEL_A, true)), PushMessage.of("t", "c")))
                .singleElement()
                .extracting(PushResult::status)
                .isEqualTo(PushResult.Status.SKIPPED);
    }

    @Test
    @DisplayName("核心无需预声明即可按开放通道 id 的值路由")
    void routesUnknownValidChannelIdByValue() {
        FakeChannel channel = new FakeChannel(new PushChannelId("custom-channel"), true);
        PushService service = service(channel);

        PushResult result = service.push(
                new PushChannelId("custom-channel"),
                PushMessage.of("标题", "正文"));

        assertThat(result.isOk()).isTrue();
        assertThat(result.channel()).isEqualTo(new PushChannelId("custom-channel"));
        assertThat(channel.received).hasSize(1);
    }

    @Test
    @DisplayName("不同实例但值相同的通道 id 视为重复并拒绝发布")
    void duplicateChannelIdsConflictByValue() {
        PushChannelRegistry registry = new PushChannelRegistry(List.of());
        FakeChannel first = new FakeChannel(new PushChannelId("custom-channel"), true);
        FakeChannel second = new FakeChannel(new PushChannelId("custom-channel"), true);
        registry.registerPrepared("owner-a", 1L, List.of(
                new PushChannelRegistry.PreparedChannel(
                        first.type(), first, first.getClass().getName())));

        assertThat(catchThrowable(() -> registry.registerPrepared("owner-b", 2L, List.of(
                new PushChannelRegistry.PreparedChannel(
                        second.type(), second, second.getClass().getName())))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate push channel id 'custom-channel'");
        assertThat(registry.byId(new PushChannelId("custom-channel"))).containsSame(first);
    }

    @Test
    @DisplayName("仅向已配置的通道广播，未配置的通道被跳过")
    void broadcastsOnlyToConfiguredChannels() {
        FakeChannel configured = new FakeChannel(CHANNEL_A, true);
        FakeChannel notConfigured = new FakeChannel(CHANNEL_B, false);
        PushService service = service(configured, notConfigured);

        List<PushResult> results = service.push(PushMessage.of("标题", "正文"));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).channel()).isEqualTo(CHANNEL_A);
        assertThat(results.get(0).isOk()).isTrue();
        assertThat(configured.received).hasSize(1);
        assertThat(notConfigured.received).isEmpty();
        // 仅支持纯文本的通道：Markdown 源消息被协商 + 转换为纯文本后才交给通道。
        assertThat(configured.received.get(0).format()).isEqualTo(PushFormat.PLAIN_TEXT);
    }

    @Test
    @DisplayName("单个通道抛异常被隔离，不影响其它通道")
    void oneChannelThrowingDoesNotBreakOthers() {
        FakeChannel exploding = new FakeChannel(CHANNEL_C, true);
        exploding.toThrow = new RuntimeException("boom");
        FakeChannel healthy = new FakeChannel(CHANNEL_A, true);
        PushService service = service(exploding, healthy);

        List<PushResult> results = service.push(PushMessage.of("标题", "正文"));

        assertThat(results).hasSize(2);
        assertThat(results).anyMatch(r -> CHANNEL_C.equals(r.channel())
                && r.status() == PushResult.Status.FAILED);
        assertThat(results).anyMatch(r -> CHANNEL_A.equals(r.channel()) && r.isOk());
        assertThat(healthy.received).hasSize(1);
    }

    @Test
    @DisplayName("通道在配置探测前被撤回时使用注册快照诊断且不让异常逃逸")
    void withdrawnChannelDuringConfigurationProbeFailsSoft() {
        FakeChannel withdrawn = new FakeChannel(CHANNEL_A, true);
        PushService service = service(withdrawn);
        withdrawn.configuredFailure = new ExternalCapabilityUnavailableException("withdrawn");
        withdrawn.failTypeLookup = true;

        List<PushResult> results = service.push(PushMessage.of("标题", "正文"));

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.channel()).isEqualTo(CHANNEL_A);
            assertThat(result.status()).isEqualTo(PushResult.Status.FAILED);
        });
    }

    @Test
    @DisplayName("定向发送：通道不存在 / 未配置时返回 SKIPPED")
    void targetedSendSkipsWhenAbsentOrUnconfigured() {
        FakeChannel unconfigured = new FakeChannel(CHANNEL_A, false);
        PushService service = service(unconfigured);

        assertThat(service.push(CHANNEL_A, PushMessage.of("t", "c")).status())
                .isEqualTo(PushResult.Status.SKIPPED);
        assertThat(service.push(CHANNEL_B, PushMessage.of("t", "c")).status())
                .isEqualTo(PushResult.Status.SKIPPED);
    }

    @Test
    @DisplayName("定向发送空通道类型时收敛为通道不可用")
    void targetedSendWithNullTypeFailsSoft() {
        PushResult result = service().push(null, PushMessage.of("标题", "正文"));

        assertThat(result.channel()).isNull();
        assertThat(result.status()).isEqualTo(PushResult.Status.SKIPPED);
        assertThat(result.detail()).isEqualTo(PushResult.DETAIL_CHANNEL_UNAVAILABLE);
    }

    @Test
    @DisplayName("测试路径仅向传入设置对应的通道发送")
    void testPathRoutesBySettingsType() {
        FakeChannel first = new FakeChannel(CHANNEL_A, true);
        FakeChannel second = new FakeChannel(CHANNEL_B, true);
        PushService service = service(first, second);

        List<PushResult> results = service.test(
                List.of(new FakeSettings(new PushChannelId("channel-a"), true)),
                PushMessage.of("标题", "正文"));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).channel()).isEqualTo(CHANNEL_A);
        assertThat(results.get(0).isOk()).isTrue();
        assertThat(first.testReceived).hasSize(1);
        assertThat(second.testReceived).isEmpty();
    }

    @Test
    @DisplayName("测试路径：设置不完整时返回 SKIPPED，不调用通道")
    void testPathSkipsIncompleteSettings() {
        FakeChannel channel = new FakeChannel(CHANNEL_A, true);
        PushService service = service(channel);

        List<PushResult> results = service.test(
                List.of(new FakeSettings(CHANNEL_A, false)),
                PushMessage.of("t", "c"));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).status()).isEqualTo(PushResult.Status.SKIPPED);
        assertThat(channel.testReceived).isEmpty();
    }

    @Test
    @DisplayName("测试设置回调异常按单项收敛且不影响后续通道")
    void testSettingsCallbackFailureIsContained() {
        FakeChannel channel = new FakeChannel(CHANNEL_A, true);
        PushService service = service(channel);
        PushChannelSettings throwing = new PushChannelSettings() {
            @Override
            public PushChannelId type() {
                throw new IllegalStateException("broken settings");
            }

            @Override
            public boolean isComplete() {
                return true;
            }
        };

        List<PushResult> results = service.test(
                List.of(throwing, new FakeSettings(CHANNEL_A, true)),
                PushMessage.of("标题", "正文"));

        assertThat(results).hasSize(2);
        assertThat(results.get(0).status()).isEqualTo(PushResult.Status.FAILED);
        assertThat(results.get(0).detail()).isEqualTo(PushResult.DETAIL_UNEXPECTED_ERROR);
        assertThat(results.get(1).isOk()).isTrue();
    }

    @Test
    @DisplayName("测试设置含空元素时保持结果与输入一一对应")
    void nullTestSettingsPreserveResultCardinality() {
        FakeChannel channel = new FakeChannel(CHANNEL_A, true);
        PushService service = service(channel);
        List<PushChannelSettings> settings = new ArrayList<>();
        settings.add(null);
        settings.add(new FakeSettings(CHANNEL_A, true));

        List<PushResult> results = service.test(settings, PushMessage.of("标题", "正文"));

        assertThat(results).hasSize(2);
        assertThat(results.get(0).channel()).isNull();
        assertThat(results.get(0).status()).isEqualTo(PushResult.Status.FAILED);
        assertThat(results.get(0).detail()).isEqualTo(PushResult.DETAIL_UNEXPECTED_ERROR);
        assertThat(results.get(1).isOk()).isTrue();
    }

    @Test
    @DisplayName("通道返回空值或非法结果时使用注册快照归一为受控失败")
    void malformedChannelResultsAreNormalized() {
        FakeChannel channel = new FakeChannel(CHANNEL_A, true);
        PushService service = service(channel);

        channel.sendResult = null;
        PushResult broadcast = service.push(PushMessage.of("标题", "正文")).get(0);
        PushResult targeted = service.push(new PushChannelId("channel-a"), PushMessage.of("标题", "正文"));
        channel.testResult = new PushResult(null, null, null);
        PushResult tested = service.test(
                List.of(new FakeSettings(CHANNEL_A, true)),
                PushMessage.of("标题", "正文")).get(0);

        assertThat(List.of(broadcast, targeted, tested)).allSatisfy(result -> {
            assertThat(result.channel()).isEqualTo(CHANNEL_A);
            assertThat(result.status()).isEqualTo(PushResult.Status.FAILED);
            assertThat(result.detail()).isEqualTo(PushResult.DETAIL_UNEXPECTED_ERROR);
        });
    }

    private static PushService service(PushChannel... channels) {
        return new PushService(new PushChannelRegistry(List.of(channels)), CONVERTER);
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

    /** 测试用设置快照：可配置通道标识与是否完整。 */
    private record FakeSettings(PushChannelId type, boolean complete) implements PushChannelSettings {
        @Override
        public boolean isComplete() {
            return complete;
        }
    }

    /** 测试替身：记录收到的已渲染消息，可配置是否"已配置"以及是否抛异常。声明仅支持纯文本。 */
    private static final class FakeChannel implements PushChannel {
        private final PushChannelId type;
        private final boolean configured;
        private RuntimeException toThrow;
        private RuntimeException configuredFailure;
        private boolean failTypeLookup;
        private PushResult sendResult;
        private PushResult testResult;
        private final List<RenderedMessage> received = new ArrayList<>();
        private final List<RenderedMessage> testReceived = new ArrayList<>();

        FakeChannel(PushChannelId type, boolean configured) {
            this.type = type;
            this.configured = configured;
            this.sendResult = PushResult.ok(type);
            this.testResult = PushResult.ok(type);
        }

        @Override
        public PushChannelId type() {
            if (failTypeLookup) {
                throw new AssertionError("service must use captured push channel id");
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
            return sendResult;
        }

        @Override
        public PushResult sendTest(PushChannelSettings settings, RenderedMessage message) {
            if (toThrow != null) {
                throw toThrow;
            }
            testReceived.add(message);
            return testResult;
        }
    }
}
