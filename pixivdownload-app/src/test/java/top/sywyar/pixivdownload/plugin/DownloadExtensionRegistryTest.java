package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.web.DownloadAcquisitionMode;
import top.sywyar.pixivdownload.plugin.api.web.DownloadGalleryCapabilities;
import top.sywyar.pixivdownload.plugin.api.web.DownloadQueueCapabilities;
import top.sywyar.pixivdownload.plugin.api.web.DownloadScheduleCapabilities;
import top.sywyar.pixivdownload.plugin.api.web.DownloadTypeDescriptor;
import top.sywyar.pixivdownload.plugin.api.web.QueueTypeContribution;
import top.sywyar.pixivdownload.plugin.api.web.StaticResourceContribution;
import top.sywyar.pixivdownload.plugin.api.web.TabContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebUiSlotContribution;
import top.sywyar.pixivdownload.plugin.registry.DownloadExtensionPublication;
import top.sywyar.pixivdownload.plugin.registry.DownloadExtensionRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginSource;
import top.sywyar.pixivdownload.plugin.registry.StaticResourceRegistry;
import top.sywyar.pixivdownload.plugin.registry.WebUiSlotRegistry;
import top.sywyar.pixivdownload.plugin.web.PluginOwnedWebAssetValidator;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DownloadExtensionRegistry 下载扩展原子快照")
class DownloadExtensionRegistryTest {

    @Test
    @DisplayName("boot 下载扩展复用 WebUiSlotRegistry 快照且不二次读取有状态 getter")
    void bootReusesUiSlotRegistrySnapshot() {
        StatefulUiSlotPlugin plugin = new StatefulUiSlotPlugin();
        PluginRegistry plugins = new PluginRegistry(List.of(plugin));
        StaticResourceRegistry statics = new StaticResourceRegistry(plugins);
        WebUiSlotRegistry uiSlots = new WebUiSlotRegistry(plugins);
        DownloadExtensionRegistry downloads = new DownloadExtensionRegistry(
                plugins, statics, new PluginOwnedWebAssetValidator(statics), uiSlots);

        assertThat(plugin.uiSlotReads.get()).isEqualTo(1);
        assertThat(uiSlots.slots()).singleElement()
                .extracting(slot -> slot.slot().slotId())
                .isEqualTo("stateful-ui.settings");
        assertThat(downloads.snapshot().uiSlots()).singleElement()
                .extracting(slot -> slot.slot().slotId())
                .isEqualTo("stateful-ui.settings");
    }

    @Test
    @DisplayName("标签页类型由 descriptor 取得模式推导且显式列表只作上限")
    void acquisitionModesAreCanonicalTruth() {
        ExtensionPlugin plugin = new ExtensionPlugin(
                "download-owner",
                List.of(
                        queueType("download-owner", "single-user",
                                List.of(DownloadAcquisitionMode.SINGLE_IMPORT,
                                        DownloadAcquisitionMode.USER_PROFILE)),
                        queueType("download-owner", "quick-search",
                                List.of(DownloadAcquisitionMode.QUICK, DownloadAcquisitionMode.SEARCH))),
                List.of(
                        new TabContribution("download-owner", "single-import", 10, List.of()),
                        new TabContribution("download-owner", "user", 20, List.of("quick-search", "single-user")),
                        new TabContribution("download-owner", "search", 30, List.of()),
                        new TabContribution("download-owner", "quick-fetch", 40, List.of("single-user"))),
                List.of(
                        new WebUiSlotContribution("download-owner", "download-owner.settings",
                                "settings-card", "/test-download/module.js", 10),
                        new WebUiSlotContribution("download-owner", "download-owner.other",
                                "novel-detail-tts", "/test-download/module.js", 20)));
        PluginRegistry plugins = new PluginRegistry(List.of(plugin));
        DownloadExtensionRegistry registry = registry(plugins);

        DownloadExtensionRegistry.Snapshot snapshot = registry.snapshot();
        assertThat(snapshot.revision()).isEqualTo(1L);
        assertThat(snapshot.tabs()).extracting(item -> item.tab().tabId())
                .containsExactly("single-import", "user", "search", "quick-fetch");
        assertThat(snapshot.tabs().get(0).supportedQueueTypes()).containsExactly("single-user");
        assertThat(snapshot.tabs().get(1).supportedQueueTypes()).containsExactly("single-user");
        assertThat(snapshot.tabs().get(2).supportedQueueTypes()).containsExactly("quick-search");
        assertThat(snapshot.tabs().get(3).supportedQueueTypes()).isEmpty();
        assertThat(snapshot.uiSlots()).singleElement()
                .satisfies(slot -> assertThat(slot.slot().target()).isEqualTo("settings-card"));

        var owner = snapshot.queueTypes().get(0).owner();
        assertThat(owner.featurePluginId()).isEqualTo("download-owner");
        assertThat(owner.packageId()).isEqualTo("download-owner");
        assertThat(owner.generation()).isZero();
        assertThat(snapshot.queueTypes().get(0).publicationId()).isPositive();
    }

    @Test
    @DisplayName("精确 publication 撤回后旧 token 不能删除同代新 publication")
    void stalePublicationCannotWithdrawCurrentOwner() {
        PluginRegistry plugins = new PluginRegistry(List.of());
        DownloadExtensionRegistry registry = registry(plugins);
        String epoch = registry.snapshot().epoch();
        PluginRegistry.RegisteredPlugin registered = registered(
                new ExtensionPlugin("reload-owner",
                        List.of(queueType("reload-owner", "reload-work",
                                List.of(DownloadAcquisitionMode.SINGLE_IMPORT))),
                        List.of(), List.of()), 7L);
        plugins.register(registered);

        DownloadExtensionPublication first = registry.publish(registered).orElseThrow();
        assertThat(registry.withdraw(first)).isTrue();
        DownloadExtensionPublication second = registry.publish(registered).orElseThrow();

        assertThat(second.publicationId()).isGreaterThan(first.publicationId());
        assertThat(registry.withdraw(first)).isFalse();
        assertThat(registry.snapshot().epoch()).isEqualTo(epoch);
        assertThat(registry.snapshot().queueTypes()).singleElement()
                .satisfies(item -> assertThat(item.publicationId()).isEqualTo(second.publicationId()));
    }

    @Test
    @DisplayName("下载扩展准备令牌提交尝试后不可再次发布")
    void preparedPublicationIsSingleUse() {
        PluginRegistry plugins = new PluginRegistry(List.of());
        DownloadExtensionRegistry registry = registry(plugins);
        PluginRegistry.RegisteredPlugin registered = registered(
                new ExtensionPlugin("single-use-owner",
                        List.of(queueType("single-use-owner", "single-use-work",
                                List.of(DownloadAcquisitionMode.SINGLE_IMPORT))),
                        List.of(), List.of()), 1L);
        plugins.register(registered);
        DownloadExtensionRegistry.PreparedPublication prepared =
                registry.preparePublication(registered);

        DownloadExtensionPublication publication = registry.publish(prepared).orElseThrow();
        assertThat(registry.withdraw(publication)).isTrue();
        assertThatThrownBy(() -> registry.publish(prepared))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already attempted");
        assertThat(registry.snapshot().queueTypes()).isEmpty();
    }

    @Test
    @DisplayName("进程内热更新保持 epoch 而不同 registry 实例使用不同 epoch")
    void epochDistinguishesColdStartFromHotUpdates() {
        PluginRegistry plugins = new PluginRegistry(List.of());
        DownloadExtensionRegistry firstRegistry = registry(plugins);
        String epoch = firstRegistry.snapshot().epoch();
        PluginRegistry.RegisteredPlugin registered = registered(
                new ExtensionPlugin(
                        "epoch-owner",
                        List.of(queueType("epoch-owner", "epoch-work",
                                List.of(DownloadAcquisitionMode.SINGLE_IMPORT))),
                        List.of(), List.of()),
                3L);
        plugins.register(registered);

        DownloadExtensionPublication publication = firstRegistry.publish(registered).orElseThrow();
        assertThat(firstRegistry.snapshot().epoch()).isEqualTo(epoch).isNotBlank();
        firstRegistry.withdraw(publication);
        assertThat(firstRegistry.snapshot().epoch()).isEqualTo(epoch);

        DownloadExtensionRegistry restartedRegistry = registry(new PluginRegistry(List.of()));
        assertThat(restartedRegistry.snapshot().epoch()).isNotEqualTo(epoch).isNotBlank();
    }

    @Test
    @DisplayName("全局冲突失败不改变既有快照与 revision")
    void conflictFailurePreservesSnapshot() {
        PluginRegistry plugins = new PluginRegistry(List.of(new ExtensionPlugin(
                "owner-a",
                List.of(queueType("owner-a", "shared", List.of(DownloadAcquisitionMode.SEARCH))),
                List.of(), List.of())));
        DownloadExtensionRegistry registry = registry(plugins);
        DownloadExtensionRegistry.Snapshot before = registry.snapshot();
        PluginRegistry.RegisteredPlugin contender = registered(
                new ExtensionPlugin(
                        "owner-b",
                        List.of(queueType("owner-b", "shared", List.of(DownloadAcquisitionMode.SEARCH))),
                        List.of(), List.of()),
                2L);
        plugins.register(contender);

        assertThatThrownBy(() -> registry.publish(contender))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate download queue type");
        assertThat(registry.snapshot()).isSameAs(before);
        assertThat(registry.snapshot().revision()).isEqualTo(1L);
    }

    @Test
    @DisplayName("非活动注册身份以及 tab owner 不一致都会被拒绝")
    void inactiveIdentityAndTabOwnerMismatchRejected() {
        PluginRegistry plugins = new PluginRegistry(List.of());
        DownloadExtensionRegistry registry = registry(plugins);
        PluginRegistry.RegisteredPlugin inactive = registered(
                new ExtensionPlugin("inactive-owner", List.of(), List.of(), List.of()), 1L);
        assertThatThrownBy(() -> registry.publish(inactive))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("current active plugin identity");

        ExtensionPlugin publishedPlugin = new ExtensionPlugin(
                "published-owner",
                List.of(queueType("published-owner", "published-work",
                        List.of(DownloadAcquisitionMode.SEARCH))),
                List.of(), List.of());
        PluginRegistry publishedPlugins = new PluginRegistry(List.of(publishedPlugin));
        DownloadExtensionRegistry publishedRegistry = registry(publishedPlugins);
        PluginRegistry.RegisteredPlugin active = publishedPlugins.registeredPlugins().get(0);
        PluginRegistry.RegisteredPlugin copiedIdentity = new PluginRegistry.RegisteredPlugin(
                active.plugin(), active.source(), active.classLoader(),
                active.packageId(), active.generation());
        assertThatThrownBy(() -> publishedRegistry.currentPublication(copiedIdentity))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("current active plugin identity");

        ExtensionPlugin mismatch = new ExtensionPlugin(
                "real-owner",
                List.of(),
                List.of(new TabContribution("other-owner", "search", 10, List.of())),
                List.of());
        assertThatThrownBy(() -> registry(new PluginRegistry(List.of(mismatch))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pluginId mismatch");
    }

    @Test
    @DisplayName("下载贡献 getter 的 AssertionError 被转为无 cause 宿主异常且快照不变")
    void pluginGetterErrorIsContainedWithoutPublishing() {
        PluginRegistry plugins = new PluginRegistry(List.of());
        DownloadExtensionRegistry registry = registry(plugins);
        PluginRegistry.RegisteredPlugin registered = registered(new ErrorDownloadPlugin(), 1L);
        plugins.register(registered);
        DownloadExtensionRegistry.Snapshot before = registry.snapshot();

        assertThatThrownBy(() -> registry.publish(registered))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("queueTypes")
                .hasMessageContaining("failureType=java.lang.AssertionError")
                .hasNoCause();
        assertThat(registry.snapshot()).isSameAs(before);
    }

    @Test
    @DisplayName("prepare 窗口内同 id remove 与 replacement 完成后旧身份不能提交")
    void replacementDuringPrepareRejectsOldPublication() throws Exception {
        PluginRegistry plugins = new PluginRegistry(List.of());
        DownloadExtensionRegistry registry = registry(plugins);
        CountDownLatch getterEntered = new CountDownLatch(1);
        CountDownLatch releaseGetter = new CountDownLatch(1);
        BlockingDownloadPlugin oldPlugin = new BlockingDownloadPlugin(getterEntered, releaseGetter);
        PluginRegistry.RegisteredPlugin old = registered(oldPlugin, 1L);
        plugins.register(old);
        AtomicReference<Throwable> publicationFailure = new AtomicReference<>();
        Thread publisher = new Thread(() -> {
            try {
                registry.publish(old);
            } catch (Throwable failure) {
                publicationFailure.set(failure);
            }
        }, "old-download-publication");
        publisher.start();
        assertThat(getterEntered.await(5, TimeUnit.SECONDS)).isTrue();

        plugins.unregister(old.id());
        PluginRegistry.RegisteredPlugin replacement = registered(
                new ExtensionPlugin(old.id(), List.of(), List.of(), List.of()), 2L);
        plugins.register(replacement);
        releaseGetter.countDown();
        publisher.join(5000);

        assertThat(publisher.isAlive()).isFalse();
        assertThat(publicationFailure.get())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("current active plugin identity");
        assertThat(registry.snapshot().revision()).isZero();
        assertThat(registry.snapshot().queueTypes()).isEmpty();
    }

    private static DownloadExtensionRegistry registry(PluginRegistry plugins) {
        StaticResourceRegistry staticResources = new StaticResourceRegistry(plugins);
        return new DownloadExtensionRegistry(
                plugins, staticResources, new PluginOwnedWebAssetValidator(staticResources));
    }

    private static PluginRegistry.RegisteredPlugin registered(PixivFeaturePlugin plugin, long generation) {
        return new PluginRegistry.RegisteredPlugin(
                plugin, PluginSource.EXTERNAL, plugin.getClass().getClassLoader(), plugin.id(), generation);
    }

    private static QueueTypeContribution queueType(String pluginId,
                                                   String type,
                                                   List<DownloadAcquisitionMode> modes) {
        String moduleUrl = null;
        return new QueueTypeContribution(
                pluginId, type, "test", "kind." + type, 10, moduleUrl,
                new DownloadTypeDescriptor(
                        DownloadTypeDescriptor.CURRENT_CONTRACT_VERSION,
                        pluginId,
                        type,
                        "test",
                        "kind." + type,
                        10,
                        "download",
                        "neutral",
                        moduleUrl,
                        modes,
                        DownloadQueueCapabilities.full(),
                        DownloadScheduleCapabilities.notSaveable(),
                        List.of(),
                        List.of(),
                        List.of(),
                        "test",
                        DownloadGalleryCapabilities.none()));
    }

    private record ExtensionPlugin(
            String id,
            List<QueueTypeContribution> queueTypes,
            List<TabContribution> tabs,
            List<WebUiSlotContribution> slots
    ) implements PixivFeaturePlugin {
        @Override public String displayName() { return "plugin.name"; }
        @Override public String description() { return "plugin.summary"; }
        @Override public PluginKind kind() { return PluginKind.FEATURE; }
        @Override public List<QueueTypeContribution> queueTypes() { return queueTypes; }
        @Override public List<TabContribution> downloadTabs() { return tabs; }
        @Override public List<WebUiSlotContribution> uiSlots() { return slots; }

        @Override
        public List<StaticResourceContribution> staticResources() {
            return List.of(new StaticResourceContribution(
                    id, "classpath:/test-download/", "/test-download/"));
        }
    }

    private static final class ErrorDownloadPlugin implements PixivFeaturePlugin {
        @Override public String id() { return "error-download-owner"; }
        @Override public String displayName() { return "plugin.name"; }
        @Override public String description() { return "plugin.summary"; }
        @Override public PluginKind kind() { return PluginKind.FEATURE; }
        @Override public List<QueueTypeContribution> queueTypes() {
            throw new AssertionError("plugin-controlled error text");
        }
    }

    private static final class BlockingDownloadPlugin implements PixivFeaturePlugin {
        private final CountDownLatch entered;
        private final CountDownLatch release;

        private BlockingDownloadPlugin(CountDownLatch entered, CountDownLatch release) {
            this.entered = entered;
            this.release = release;
        }

        @Override public String id() { return "blocking-download-owner"; }
        @Override public String displayName() { return "plugin.name"; }
        @Override public String description() { return "plugin.summary"; }
        @Override public PluginKind kind() { return PluginKind.FEATURE; }

        @Override
        public List<QueueTypeContribution> queueTypes() {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test release timeout");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("test interrupted");
            }
            return List.of(queueType(id(), "blocking-type", List.of(DownloadAcquisitionMode.SEARCH)));
        }
    }

    private static final class StatefulUiSlotPlugin implements PixivFeaturePlugin {
        private final AtomicInteger uiSlotReads = new AtomicInteger();

        @Override public String id() { return "stateful-ui"; }
        @Override public String displayName() { return "plugin.name"; }
        @Override public String description() { return "plugin.summary"; }
        @Override public PluginKind kind() { return PluginKind.FEATURE; }

        @Override
        public List<WebUiSlotContribution> uiSlots() {
            return uiSlotReads.incrementAndGet() == 1
                    ? List.of(new WebUiSlotContribution(
                            id(), "stateful-ui.settings", "settings-card",
                            "/test-download/module.js", 10))
                    : List.of();
        }

        @Override
        public List<StaticResourceContribution> staticResources() {
            return List.of(new StaticResourceContribution(
                    id(), "classpath:/test-download/", "/test-download/"));
        }
    }
}
