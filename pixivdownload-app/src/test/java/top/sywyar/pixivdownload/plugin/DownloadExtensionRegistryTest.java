package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.download.type.DownloadAcquisitionMode;
import top.sywyar.pixivdownload.plugin.api.download.type.DownloadTypeDescriptor;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.web.StaticResourceContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebUiSlotContribution;
import top.sywyar.pixivdownload.plugin.registry.DownloadExtensionPublication;
import top.sywyar.pixivdownload.plugin.registry.DownloadExtensionRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginSource;
import top.sywyar.pixivdownload.plugin.registry.StaticResourceRegistry;
import top.sywyar.pixivdownload.plugin.registry.WebUiSlotRegistry;
import top.sywyar.pixivdownload.plugin.web.PluginOwnedWebAssetValidator;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DownloadExtensionRegistry 下载扩展原子快照")
class DownloadExtensionRegistryTest {

    private static final String MODULE_URL = "/test-download/module.js";

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
    @DisplayName("下载类型 descriptor 是取得模式与展示字段的唯一事实源")
    void descriptorIsCanonicalDownloadTypeTruth() {
        ExtensionPlugin plugin = new ExtensionPlugin(
                "download-owner",
                List.of(
                        downloadType("single-user", List.of(
                                DownloadAcquisitionMode.SINGLE_IMPORT,
                                DownloadAcquisitionMode.USER_PROFILE)),
                        downloadType("quick-search", List.of(
                                DownloadAcquisitionMode.QUICK,
                                DownloadAcquisitionMode.SEARCH))),
                List.of(
                        new WebUiSlotContribution("download-owner.settings",
                                "settings-card", MODULE_URL, 10),
                        new WebUiSlotContribution("download-owner.other",
                                "novel-detail-tts", MODULE_URL, 20)));
        PluginRegistry plugins = new PluginRegistry(List.of(plugin));
        DownloadExtensionRegistry registry = registry(plugins);

        DownloadExtensionRegistry.Snapshot snapshot = registry.snapshot();
        assertThat(snapshot.revision()).isEqualTo(1L);
        assertThat(snapshot.downloadTypes())
                .extracting(item -> item.descriptor().type())
                .containsExactly("single-user", "quick-search");
        assertThat(snapshot.downloadTypes().get(0).descriptor().acquisitionModes())
                .containsExactly(DownloadAcquisitionMode.SINGLE_IMPORT,
                        DownloadAcquisitionMode.USER_PROFILE);
        assertThat(snapshot.uiSlots()).singleElement()
                .satisfies(slot -> assertThat(slot.slot().target()).isEqualTo("settings-card"));

        var registeredType = registry.resolveDownloadType("single-user").orElseThrow();
        assertThat(registeredType.owner().featurePluginId()).isEqualTo("download-owner");
        assertThat(registeredType.owner().packageId()).isEqualTo("download-owner");
        assertThat(registeredType.owner().generation()).isZero();
        assertThat(registeredType.publicationId()).isPositive();
        assertThat(registry.resolveDownloadType(" ")).isEmpty();
        assertThatThrownBy(() -> snapshot.downloadTypes().add(registeredType))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("精确 publication 撤回后旧 token 不能删除同代新 publication")
    void stalePublicationCannotWithdrawCurrentOwner() {
        PluginRegistry plugins = new PluginRegistry(List.of());
        DownloadExtensionRegistry registry = registry(plugins);
        String epoch = registry.snapshot().epoch();
        PluginRegistry.RegisteredPlugin registered = registered(
                new ExtensionPlugin("reload-owner",
                        List.of(downloadType("reload-work",
                                List.of(DownloadAcquisitionMode.SINGLE_IMPORT))), List.of()), 7L);
        plugins.register(registered);

        DownloadExtensionPublication first = publishWithOwnedAssets(registry, registered);
        assertThat(registry.withdraw(first)).isTrue();
        DownloadExtensionPublication second = publishWithOwnedAssets(registry, registered);

        assertThat(second.publicationId()).isGreaterThan(first.publicationId());
        assertThat(registry.withdraw(first)).isFalse();
        assertThat(registry.snapshot().epoch()).isEqualTo(epoch);
        assertThat(registry.snapshot().downloadTypes()).singleElement()
                .satisfies(item -> assertThat(item.publicationId()).isEqualTo(second.publicationId()));
    }

    @Test
    @DisplayName("下载扩展准备令牌提交尝试后不可再次发布")
    void preparedPublicationIsSingleUse() {
        PluginRegistry plugins = new PluginRegistry(List.of());
        DownloadExtensionRegistry registry = registry(plugins);
        PluginRegistry.RegisteredPlugin registered = registered(
                new ExtensionPlugin("single-use-owner",
                        List.of(downloadType("single-use-work",
                                List.of(DownloadAcquisitionMode.SINGLE_IMPORT))), List.of()), 1L);
        plugins.register(registered);
        DownloadExtensionRegistry.PreparedPublication prepared =
                prepareWithOwnedAssets(registry, registered);

        DownloadExtensionPublication publication = registry.publish(prepared).orElseThrow();
        assertThat(registry.withdraw(publication)).isTrue();
        assertThatThrownBy(() -> registry.publish(prepared))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already attempted");
        assertThat(registry.snapshot().downloadTypes()).isEmpty();
    }

    @Test
    @DisplayName("进程内热更新保持 epoch 而不同 registry 实例使用不同 epoch")
    void epochDistinguishesColdStartFromHotUpdates() {
        PluginRegistry plugins = new PluginRegistry(List.of());
        DownloadExtensionRegistry firstRegistry = registry(plugins);
        String epoch = firstRegistry.snapshot().epoch();
        PluginRegistry.RegisteredPlugin registered = registered(
                new ExtensionPlugin("epoch-owner",
                        List.of(downloadType("epoch-work",
                                List.of(DownloadAcquisitionMode.SINGLE_IMPORT))), List.of()), 3L);
        plugins.register(registered);

        DownloadExtensionPublication publication = publishWithOwnedAssets(firstRegistry, registered);
        assertThat(firstRegistry.snapshot().epoch()).isEqualTo(epoch).isNotBlank();
        firstRegistry.withdraw(publication);
        assertThat(firstRegistry.snapshot().epoch()).isEqualTo(epoch);

        DownloadExtensionRegistry restartedRegistry = registry(new PluginRegistry(List.of()));
        assertThat(restartedRegistry.snapshot().epoch()).isNotEqualTo(epoch).isNotBlank();
    }

    @Test
    @DisplayName("全局类型冲突失败不改变既有快照与 revision")
    void conflictFailurePreservesSnapshot() {
        PluginRegistry plugins = new PluginRegistry(List.of(new ExtensionPlugin(
                "owner-a",
                List.of(downloadType("shared", List.of(DownloadAcquisitionMode.SEARCH))),
                List.of())));
        DownloadExtensionRegistry registry = registry(plugins);
        DownloadExtensionRegistry.Snapshot before = registry.snapshot();
        PluginRegistry.RegisteredPlugin contender = registered(
                new ExtensionPlugin("owner-b",
                        List.of(downloadType("shared", List.of(DownloadAcquisitionMode.SEARCH))),
                        List.of()), 2L);
        plugins.register(contender);

        assertThatThrownBy(() -> publishWithOwnedAssets(registry, contender))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate download type");
        assertThat(registry.snapshot()).isSameAs(before);
        assertThat(registry.snapshot().revision()).isEqualTo(1L);
    }

    @Test
    @DisplayName("非活动注册身份以及复制的活动身份都会被拒绝")
    void inactiveAndCopiedIdentityRejected() {
        PluginRegistry plugins = new PluginRegistry(List.of());
        DownloadExtensionRegistry registry = registry(plugins);
        PluginRegistry.RegisteredPlugin inactive = registered(
                new ExtensionPlugin("inactive-owner", List.of(), List.of()), 1L);
        assertThatThrownBy(() -> registry.publish(inactive))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("current active plugin identity");

        ExtensionPlugin publishedPlugin = new ExtensionPlugin(
                "published-owner",
                List.of(downloadType("published-work", List.of(DownloadAcquisitionMode.SEARCH))),
                List.of());
        PluginRegistry publishedPlugins = new PluginRegistry(List.of(publishedPlugin));
        DownloadExtensionRegistry publishedRegistry = registry(publishedPlugins);
        PluginRegistry.RegisteredPlugin active = publishedPlugins.registeredPlugins().get(0);
        PluginRegistry.RegisteredPlugin copiedIdentity = new PluginRegistry.RegisteredPlugin(
                active.plugin(), active.source(), active.classLoader(),
                active.packageId(), active.generation());
        assertThatThrownBy(() -> publishedRegistry.currentPublication(copiedIdentity))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("current active plugin identity");

    }

    @Test
    @DisplayName("descriptor 版本和集合形状非法时拒绝整 owner publication")
    void invalidDescriptorRejectsWholeOwnerPublication() {
        DownloadTypeDescriptor unsupported = descriptor(
                2, "unsupported", List.of(DownloadAcquisitionMode.SEARCH), List.of(), List.of());
        assertThatThrownBy(() -> registry(new PluginRegistry(List.of(
                new ExtensionPlugin("unsupported-owner", List.of(unsupported), List.of())))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unsupported download type descriptor version");

        DownloadTypeDescriptor duplicateModes = descriptor(
                DownloadTypeDescriptor.CURRENT_CONTRACT_VERSION,
                "duplicate-modes",
                List.of(DownloadAcquisitionMode.SEARCH, DownloadAcquisitionMode.SEARCH),
                List.of(), List.of());
        assertThatThrownBy(() -> registry(new PluginRegistry(List.of(
                new ExtensionPlugin("duplicate-owner", List.of(duplicateModes), List.of())))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate acquisition mode");

        DownloadTypeDescriptor blankFilter = descriptor(
                DownloadTypeDescriptor.CURRENT_CONTRACT_VERSION,
                "blank-filter", List.of(), List.of(" "), List.of());
        assertThatThrownBy(() -> registry(new PluginRegistry(List.of(
                new ExtensionPlugin("blank-owner", List.of(blankFilter), List.of())))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("blank filter");
    }

    @Test
    @DisplayName("下载类型模块必须归声明 owner 的同源静态资源")
    void moduleMustBeOwnedByDescriptorOwner() {
        DownloadTypeDescriptor foreignModule = new DownloadTypeDescriptor(
                DownloadTypeDescriptor.CURRENT_CONTRACT_VERSION,
                "foreign-module",
                "test",
                "kind.foreign-module",
                10,
                "download",
                "neutral",
                "/foreign/module.js",
                List.of(),
                false,
                List.of(),
                List.of(),
                "test");

        assertThatThrownBy(() -> registry(new PluginRegistry(List.of(
                new ExtensionPlugin("asset-owner", List.of(foreignModule), List.of())))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not covered by an owner static resource contribution");
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
                .hasMessageContaining("downloadTypes")
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
                new ExtensionPlugin(old.id(), List.of(), List.of()), 2L);
        plugins.register(replacement);
        releaseGetter.countDown();
        publisher.join(5000);

        assertThat(publisher.isAlive()).isFalse();
        assertThat(publicationFailure.get())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("current active plugin identity");
        assertThat(registry.snapshot().revision()).isZero();
        assertThat(registry.snapshot().downloadTypes()).isEmpty();
    }

    private static DownloadExtensionRegistry registry(PluginRegistry plugins) {
        StaticResourceRegistry staticResources = new StaticResourceRegistry(plugins);
        return new DownloadExtensionRegistry(
                plugins, staticResources, new PluginOwnedWebAssetValidator(staticResources));
    }

    /** 模拟统一 Web prepare：静态资源尚未 serving，也必须用同一次 owner-local 快照校验下载模块。 */
    private static DownloadExtensionRegistry.PreparedPublication prepareWithOwnedAssets(
            DownloadExtensionRegistry registry,
            PluginRegistry.RegisteredPlugin registered) {
        PixivFeaturePlugin plugin = registered.plugin();
        StaticResourceRegistry resourceResolver =
                new StaticResourceRegistry(new PluginRegistry(List.of()));
        List<StaticResourceRegistry.RegisteredStaticResource> resources = resourceResolver
                .prepare(registered, plugin.staticResources())
                .resources();
        return registry.preparePublication(
                registered, plugin.downloadTypes(), plugin.uiSlots(), resources);
    }

    private static DownloadExtensionPublication publishWithOwnedAssets(
            DownloadExtensionRegistry registry,
            PluginRegistry.RegisteredPlugin registered) {
        return registry.publish(prepareWithOwnedAssets(registry, registered)).orElseThrow();
    }

    private static PluginRegistry.RegisteredPlugin registered(PixivFeaturePlugin plugin, long generation) {
        return new PluginRegistry.RegisteredPlugin(
                plugin, PluginSource.EXTERNAL, plugin.getClass().getClassLoader(), plugin.id(), generation);
    }

    private static DownloadTypeDescriptor downloadType(
            String type, List<DownloadAcquisitionMode> modes) {
        return descriptor(
                DownloadTypeDescriptor.CURRENT_CONTRACT_VERSION, type, modes, List.of(), List.of());
    }

    private static DownloadTypeDescriptor descriptor(
            int contractVersion,
            String type,
            List<DownloadAcquisitionMode> modes,
            List<String> filters,
            List<String> settings) {
        return new DownloadTypeDescriptor(
                contractVersion,
                type,
                "test",
                "kind." + type,
                10,
                "download",
                "neutral",
                MODULE_URL,
                modes,
                false,
                filters,
                settings,
                "test");
    }

    private record ExtensionPlugin(
            String id,
            List<DownloadTypeDescriptor> downloadTypes,
            List<WebUiSlotContribution> slots
    ) implements PixivFeaturePlugin {
        @Override public String displayName() { return "plugin.name"; }
        @Override public String description() { return "plugin.summary"; }
        @Override public PluginKind kind() { return PluginKind.FEATURE; }
        @Override public List<DownloadTypeDescriptor> downloadTypes() { return downloadTypes; }
        @Override public List<WebUiSlotContribution> uiSlots() { return slots; }

        @Override
        public List<StaticResourceContribution> staticResources() {
            return List.of(new StaticResourceContribution(
                    "classpath:/test-download/", "/test-download/"));
        }
    }

    private static final class ErrorDownloadPlugin implements PixivFeaturePlugin {
        @Override public String id() { return "error-download-owner"; }
        @Override public String displayName() { return "plugin.name"; }
        @Override public String description() { return "plugin.summary"; }
        @Override public PluginKind kind() { return PluginKind.FEATURE; }
        @Override public List<DownloadTypeDescriptor> downloadTypes() {
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
        public List<DownloadTypeDescriptor> downloadTypes() {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test release timeout");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("test interrupted");
            }
            return List.of(downloadType("blocking-type", List.of(DownloadAcquisitionMode.SEARCH)));
        }

        @Override
        public List<StaticResourceContribution> staticResources() {
            return List.of(new StaticResourceContribution(
                    "classpath:/test-download/", "/test-download/"));
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
                            "stateful-ui.settings", "settings-card", MODULE_URL, 10))
                    : List.of();
        }

        @Override
        public List<StaticResourceContribution> staticResources() {
            return List.of(new StaticResourceContribution(
                    "classpath:/test-download/", "/test-download/"));
        }
    }
}
