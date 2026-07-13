package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginCapabilityContributionRegistrar;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.PluginCapabilityContributionAdapter;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.PluginContextCapabilityContributionAdapter;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.PushChannelCapabilityAdapter;
import top.sywyar.pixivdownload.push.PushChannel;
import top.sywyar.pixivdownload.core.push.PushChannelRegistry;
import top.sywyar.pixivdownload.push.PushChannelSettings;
import top.sywyar.pixivdownload.push.PushChannelType;
import top.sywyar.pixivdownload.push.PushFormat;
import top.sywyar.pixivdownload.push.PushResult;
import top.sywyar.pixivdownload.push.RenderedMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 外置插件 runtime capability 注册器测试：中心注册器只调度中性 adapter，
 * 具体能力类型 / registry 由 adapter 封装。
 */
@DisplayName("外置插件 runtime capability 注册器")
class PluginCapabilityContributionRegistrarTest {

    @Test
    @DisplayName("adapter 按稳定能力名排序，并只发现子 context 中匹配类型的 Bean")
    void adaptersAreSortedAndBeansAreDiscoveredByType() {
        List<String> events = new ArrayList<>();
        RecordingAdapter<BetaCapability> beta = new RecordingAdapter<>("zeta.beta", BetaCapability.class, events);
        RecordingAdapter<AlphaCapability> alpha = new RecordingAdapter<>("alpha.core", AlphaCapability.class, events);
        PluginCapabilityContributionRegistrar registrar =
                new PluginCapabilityContributionRegistrar(List.<PluginCapabilityContributionAdapter<?>>of(beta, alpha));

        try (AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext()) {
            child.registerBean("alpha", AlphaCapability.class, AlphaBean::new);
            child.registerBean("beta", BetaCapability.class, BetaBean::new);
            child.refresh();

            registrar.register("ext-demo", child);
        }

        assertThat(registrar.capabilityNames()).containsExactly("alpha.core", "zeta.beta");
        assertThat(events).containsExactly(
                "alpha.core:register:ext-demo:1",
                "zeta.beta:register:ext-demo:1");
        assertThat(alpha.beans("ext-demo")).hasOnlyElementsOfType(AlphaBean.class);
        assertThat(beta.beans("ext-demo")).hasOnlyElementsOfType(BetaBean.class);
    }

    @Test
    @DisplayName("能力缺席时传入空列表：adapter 可自然移除该插件先前能力")
    void missingCapabilityIsRegisteredAsEmptyList() {
        List<String> events = new ArrayList<>();
        RecordingAdapter<AlphaCapability> alpha = new RecordingAdapter<>("alpha.core", AlphaCapability.class, events);
        PluginCapabilityContributionRegistrar registrar =
                new PluginCapabilityContributionRegistrar(List.<PluginCapabilityContributionAdapter<?>>of(alpha));

        try (AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext()) {
            child.refresh();

            registrar.register("ext-disabled", child);
        }

        assertThat(events).containsExactly("alpha.core:register:ext-disabled:0");
        assertThat(alpha.beans("ext-disabled")).isEmpty();
    }

    @Test
    @DisplayName("子 context 缺席时注销全部 adapter：禁用 / 停用插件能力自然缺席")
    void nullContextUnregistersAllAdapters() {
        List<String> events = new ArrayList<>();
        RecordingAdapter<AlphaCapability> alpha = new RecordingAdapter<>("alpha.core", AlphaCapability.class, events);
        RecordingAdapter<BetaCapability> beta = new RecordingAdapter<>("zeta.beta", BetaCapability.class, events);
        PluginCapabilityContributionRegistrar registrar =
                new PluginCapabilityContributionRegistrar(List.<PluginCapabilityContributionAdapter<?>>of(beta, alpha));

        registrar.register("ext-demo", null);

        assertThat(events).containsExactly(
                "alpha.core:unregister:ext-demo",
                "zeta.beta:unregister:ext-demo");
    }

    @Test
    @DisplayName("adapter 注册失败时回滚此前已注册能力，不留下半接入状态")
    void failureRollsBackPreviousAdapters() {
        List<String> events = new ArrayList<>();
        RecordingAdapter<AlphaCapability> alpha = new RecordingAdapter<>("alpha.core", AlphaCapability.class, events);
        RecordingAdapter<BetaCapability> beta = new RecordingAdapter<>("zeta.beta", BetaCapability.class, events);
        beta.failOnRegister = true;
        PluginCapabilityContributionRegistrar registrar =
                new PluginCapabilityContributionRegistrar(List.<PluginCapabilityContributionAdapter<?>>of(beta, alpha));

        try (AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext()) {
            child.registerBean("alpha", AlphaCapability.class, AlphaBean::new);
            child.registerBean("beta", BetaCapability.class, BetaBean::new);
            child.refresh();

            assertThatThrownBy(() -> registrar.register("ext-demo", child))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("zeta.beta failed");
        }

        assertThat(events).containsExactly(
                "alpha.core:register:ext-demo:1",
                "zeta.beta:register:ext-demo:1",
                "zeta.beta:unregister:ext-demo",
                "alpha.core:unregister:ext-demo");
        assertThat(alpha.registered).doesNotContainKey("ext-demo");
        assertThat(beta.registered).doesNotContainKey("ext-demo");
    }

    @Test
    @DisplayName("下游 registry 的能力去重冲突透传，并回滚已注册 adapter")
    void downstreamDuplicateCapabilityConflictPropagatesAndRollsBack() {
        List<String> events = new ArrayList<>();
        RecordingAdapter<AlphaCapability> alpha = new RecordingAdapter<>("alpha.core", AlphaCapability.class, events);
        PushChannelRegistry pushRegistry = new PushChannelRegistry(List.of());
        PushChannelCapabilityAdapter push = new PushChannelCapabilityAdapter(pushRegistry);
        PluginCapabilityContributionRegistrar registrar =
                new PluginCapabilityContributionRegistrar(List.<PluginCapabilityContributionAdapter<?>>of(push, alpha));

        try (AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext()) {
            child.registerBean("alpha", AlphaCapability.class, AlphaBean::new);
            child.registerBean("push-a", PushChannel.class, () -> new FakePushChannel(PushChannelType.BARK));
            child.registerBean("push-b", PushChannel.class, () -> new FakePushChannel(PushChannelType.BARK));
            child.refresh();

            assertThatThrownBy(() -> registrar.register("ext-demo", child))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("duplicate push channel type");
        }

        assertThat(events).containsExactly(
                "alpha.core:register:ext-demo:1",
                "alpha.core:unregister:ext-demo");
        assertThat(alpha.registered).doesNotContainKey("ext-demo");
        assertThat(pushRegistry.channels()).isEmpty();

        try (AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext()) {
            child.registerBean("push-ok", PushChannel.class, () -> new FakePushChannel(PushChannelType.BARK));
            child.refresh();

            registrar.register("ext-demo", child);
        }

        assertThat(pushRegistry.channels()).hasSize(1);
    }

    @Test
    @DisplayName("context adapter 部分发布后失败时 registrar 仍调用注销清除残留")
    void partialContextAdapterFailureIsRolledBack() {
        List<String> events = new ArrayList<>();
        RecordingContextAdapter gallery = new RecordingContextAdapter(events);
        PluginCapabilityContributionRegistrar registrar = new PluginCapabilityContributionRegistrar(
                List.of(), List.of(gallery));

        try (AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext()) {
            child.refresh();
            gallery.failAfterRegister = true;

            assertThatThrownBy(() -> registrar.register("ext-demo", child))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("gallery failed");
        }

        assertThat(events).containsExactly(
                "gallery:register:ext-demo",
                "gallery:unregister:ext-demo");
        assertThat(gallery.registered).isEmpty();
    }

    @Test
    @DisplayName("注销单个 adapter 失败时仍清理其余能力并汇总异常")
    void unregisterFailureDoesNotBlockRemainingAdapters() {
        List<String> events = new ArrayList<>();
        RecordingAdapter<AlphaCapability> alpha = new RecordingAdapter<>(
                "alpha.core", AlphaCapability.class, events);
        RecordingAdapter<BetaCapability> beta = new RecordingAdapter<>(
                "zeta.beta", BetaCapability.class, events);
        RecordingContextAdapter gallery = new RecordingContextAdapter(events);
        alpha.failOnUnregister = true;
        PluginCapabilityContributionRegistrar registrar = new PluginCapabilityContributionRegistrar(
                List.<PluginCapabilityContributionAdapter<?>>of(beta, alpha), List.of(gallery));

        assertThatThrownBy(() -> registrar.unregister("ext-demo"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed to unregister one or more runtime capabilities");

        assertThat(events).containsExactly(
                "alpha.core:unregister:ext-demo",
                "zeta.beta:unregister:ext-demo",
                "gallery:unregister:ext-demo");
    }

    @Test
    @DisplayName("回滚单个 adapter 失败时仍逆序清理其它已注册能力")
    void rollbackFailureDoesNotBlockEarlierAdapters() {
        List<String> events = new ArrayList<>();
        RecordingAdapter<AlphaCapability> alpha = new RecordingAdapter<>(
                "alpha.core", AlphaCapability.class, events);
        RecordingAdapter<BetaCapability> beta = new RecordingAdapter<>(
                "middle.beta", BetaCapability.class, events);
        RecordingAdapter<BetaCapability> failing = new RecordingAdapter<>(
                "zeta.failure", BetaCapability.class, events);
        beta.failOnUnregister = true;
        failing.failOnRegister = true;
        PluginCapabilityContributionRegistrar registrar = new PluginCapabilityContributionRegistrar(
                List.<PluginCapabilityContributionAdapter<?>>of(failing, beta, alpha));

        try (AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext()) {
            child.registerBean("alpha", AlphaCapability.class, AlphaBean::new);
            child.registerBean("beta", BetaCapability.class, BetaBean::new);
            child.refresh();

            assertThatThrownBy(() -> registrar.register("ext-demo", child))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("zeta.failure failed")
                    .satisfies(failure -> assertThat(failure.getSuppressed()).hasSize(1));
        }

        assertThat(events).containsExactly(
                "alpha.core:register:ext-demo:1",
                "middle.beta:register:ext-demo:1",
                "zeta.failure:register:ext-demo:1",
                "zeta.failure:unregister:ext-demo",
                "middle.beta:unregister:ext-demo",
                "alpha.core:unregister:ext-demo");
        assertThat(alpha.registered).doesNotContainKey("ext-demo");
    }

    private interface AlphaCapability {
    }

    private interface BetaCapability {
    }

    private static final class AlphaBean implements AlphaCapability {
    }

    private static final class BetaBean implements BetaCapability {
    }

    private static final class RecordingAdapter<T> implements PluginCapabilityContributionAdapter<T> {
        private final String name;
        private final Class<T> type;
        private final List<String> events;
        private final Map<String, List<T>> registered = new LinkedHashMap<>();
        private boolean failOnRegister;
        private boolean failOnUnregister;

        private RecordingAdapter(String name, Class<T> type, List<String> events) {
            this.name = name;
            this.type = type;
            this.events = events;
        }

        @Override
        public Class<T> beanType() {
            return type;
        }

        @Override
        public String capabilityName() {
            return name;
        }

        @Override
        public void register(String pluginId, List<T> beans) {
            events.add(name + ":register:" + pluginId + ":" + beans.size());
            if (failOnRegister) {
                throw new IllegalStateException(name + " failed");
            }
            registered.put(pluginId, beans);
        }

        @Override
        public void unregister(String pluginId) {
            events.add(name + ":unregister:" + pluginId);
            if (failOnUnregister) {
                throw new IllegalStateException(name + " unregister failed");
            }
            registered.remove(pluginId);
        }

        private List<T> beans(String pluginId) {
            return registered.getOrDefault(pluginId, List.of());
        }
    }

    private static final class RecordingContextAdapter
            implements PluginContextCapabilityContributionAdapter {
        private final List<String> events;
        private final List<String> registered = new ArrayList<>();
        private boolean failOnRegister;
        private boolean failAfterRegister;

        private RecordingContextAdapter(List<String> events) {
            this.events = events;
        }

        @Override
        public String capabilityName() {
            return "gallery";
        }

        @Override
        public void register(String pluginId, org.springframework.context.ConfigurableApplicationContext context) {
            events.add("gallery:register:" + pluginId);
            if (failOnRegister) {
                throw new IllegalStateException("gallery failed");
            }
            if (!registered.contains(pluginId)) {
                registered.add(pluginId);
            }
            if (failAfterRegister) {
                throw new IllegalStateException("gallery failed after partial publication");
            }
        }

        @Override
        public void unregister(String pluginId) {
            events.add("gallery:unregister:" + pluginId);
            registered.remove(pluginId);
        }
    }

    private record FakePushChannel(PushChannelType type) implements PushChannel {
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
            return PushResult.ok(type);
        }

        @Override
        public PushResult sendTest(PushChannelSettings settings, RenderedMessage message) {
            return PushResult.ok(type);
        }
    }
}
