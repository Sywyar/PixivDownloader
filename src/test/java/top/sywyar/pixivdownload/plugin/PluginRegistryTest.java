package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PluginRegistryTest {

    private static class TestPlugin implements PixivFeaturePlugin {

        private final String id;
        private final PluginKind kind;
        private final List<String> lifecycleLog;
        private final boolean failOnStop;

        TestPlugin(String id) {
            this(id, PluginKind.FEATURE);
        }

        TestPlugin(String id, PluginKind kind) {
            this(id, kind, new ArrayList<>(), false);
        }

        TestPlugin(String id, List<String> lifecycleLog, boolean failOnStop) {
            this(id, PluginKind.FEATURE, lifecycleLog, failOnStop);
        }

        TestPlugin(String id, PluginKind kind, List<String> lifecycleLog, boolean failOnStop) {
            this.id = id;
            this.kind = kind;
            this.lifecycleLog = lifecycleLog;
            this.failOnStop = failOnStop;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String displayName() {
            return id;
        }

        @Override
        public PluginKind kind() {
            return kind;
        }

        @Override
        public void start() {
            lifecycleLog.add("start:" + id);
        }

        @Override
        public void stop() {
            lifecycleLog.add("stop:" + id);
            if (failOnStop) {
                throw new RuntimeException("boom");
            }
        }
    }

    @Test
    @DisplayName("插件 id 重复时构造失败")
    void duplicateIdFailsConstruction() {
        assertThatThrownBy(() -> new PluginRegistry(List.of(new TestPlugin("stats"), new TestPlugin("stats"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate plugin id");
    }

    @Test
    @DisplayName("不符合小写短横线规范的插件 id 被拒绝")
    void invalidIdRejected() {
        for (String invalid : new String[]{"Stats", "stats_page", "-stats", "stats-", "stats--page", "1stats", ""}) {
            assertThatThrownBy(() -> new PluginRegistry(List.of(new TestPlugin(invalid))))
                    .as("id: %s", invalid)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("invalid plugin id");
        }
    }

    @Test
    @DisplayName("register→unregister→再 register 后快照与首次注册一致")
    void reregisterRestoresSnapshot() {
        TestPlugin stats = new TestPlugin("stats");
        PluginRegistry registry = new PluginRegistry(List.of(new TestPlugin("core"), stats));
        List<PixivFeaturePlugin> initial = registry.plugins();

        registry.unregister("stats");
        assertThat(registry.plugins()).extracting(PixivFeaturePlugin::id).containsExactly("core");
        assertThat(registry.find("stats")).isEmpty();

        registry.register(stats);
        assertThat(registry.plugins()).isEqualTo(initial);
        assertThat(registry.find("stats")).contains(stats);
    }

    @Test
    @DisplayName("注销未注册的插件 id 抛出异常")
    void unregisterUnknownIdThrows() {
        PluginRegistry registry = new PluginRegistry(List.of(new TestPlugin("core")));

        assertThatThrownBy(() -> registry.unregister("stats"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown plugin id");
    }

    @Test
    @DisplayName("快照按注册顺序排列且不可变")
    void snapshotIsOrderedAndImmutable() {
        PluginRegistry registry = new PluginRegistry(
                List.of(new TestPlugin("core"), new TestPlugin("gallery"), new TestPlugin("stats")));

        List<PixivFeaturePlugin> snapshot = registry.plugins();
        assertThat(snapshot).extracting(PixivFeaturePlugin::id).containsExactly("core", "gallery", "stats");
        assertThatThrownBy(() -> snapshot.add(new TestPlugin("novel")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("start 按注册顺序调用各插件，stop 按反序调用且单个失败不中断")
    void lifecycleOrderAndFailureIsolation() {
        List<String> lifecycleLog = new ArrayList<>();
        PluginRegistry registry = new PluginRegistry(List.of(
                new TestPlugin("core", lifecycleLog, false),
                new TestPlugin("gallery", lifecycleLog, true),
                new TestPlugin("stats", lifecycleLog, false)));

        registry.start();
        assertThat(registry.isRunning()).isTrue();
        assertThat(lifecycleLog).containsExactly("start:core", "start:gallery", "start:stats");

        lifecycleLog.clear();
        registry.stop();
        assertThat(registry.isRunning()).isFalse();
        assertThat(lifecycleLog).containsExactly("stop:stats", "stop:gallery", "stop:core");
    }

    @Test
    @DisplayName("禁用的功能插件不进入活动快照，但仍保留在 allPlugins 并出现在 disabledPlugins")
    void disabledFeaturePluginExcludedFromActiveSnapshotButKeptInstalled() {
        PluginToggleProperties toggles = new PluginToggleProperties();
        toggles.put("stats", disabledToggle());
        PluginRegistry registry = new PluginRegistry(
                List.of(new TestPlugin("core", PluginKind.CORE),
                        new TestPlugin("gallery"),
                        new TestPlugin("stats")),
                toggles);

        assertThat(registry.plugins()).extracting(PixivFeaturePlugin::id).containsExactly("core", "gallery");
        assertThat(registry.allPlugins()).extracting(PixivFeaturePlugin::id)
                .containsExactly("core", "gallery", "stats");
        assertThat(registry.disabledPlugins()).extracting(PixivFeaturePlugin::id).containsExactly("stats");
        assertThat(registry.find("stats")).isEmpty();
    }

    @Test
    @DisplayName("核心插件即使配置 enabled=false 也不可禁用")
    void corePluginCannotBeDisabled() {
        PluginToggleProperties toggles = new PluginToggleProperties();
        toggles.put("core", disabledToggle());
        toggles.put("stats", disabledToggle());
        PluginRegistry registry = new PluginRegistry(
                List.of(new TestPlugin("core", PluginKind.CORE), new TestPlugin("stats")),
                toggles);

        assertThat(registry.plugins()).extracting(PixivFeaturePlugin::id).containsExactly("core");
        assertThat(registry.disabledPlugins()).extracting(PixivFeaturePlugin::id).containsExactly("stats");
    }

    @Test
    @DisplayName("禁用插件不进入活动快照，其生命周期方法不被调用")
    void disabledPluginLifecycleNotInvoked() {
        List<String> log = new ArrayList<>();
        PluginToggleProperties toggles = new PluginToggleProperties();
        toggles.put("stats", disabledToggle());
        PluginRegistry registry = new PluginRegistry(
                List.of(new TestPlugin("core", PluginKind.CORE, log, false),
                        new TestPlugin("stats", PluginKind.FEATURE, log, false)),
                toggles);

        registry.start();
        assertThat(log).containsExactly("start:core");

        log.clear();
        registry.stop();
        assertThat(log).containsExactly("stop:core");
    }

    private static PluginToggleProperties.PluginToggle disabledToggle() {
        PluginToggleProperties.PluginToggle toggle = new PluginToggleProperties.PluginToggle();
        toggle.setEnabled(false);
        return toggle;
    }
}
