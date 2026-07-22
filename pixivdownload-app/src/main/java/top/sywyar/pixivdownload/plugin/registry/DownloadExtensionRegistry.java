package top.sywyar.pixivdownload.plugin.registry;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.plugin.api.download.type.DownloadTypeDescriptor;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.web.WebUiSlotContribution;
import top.sywyar.pixivdownload.plugin.web.PluginOwnedWebAssetValidator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 下载工作台扩展的单一 owner 原子快照。每个插件一次发布下载类型与下载页 UI slot，
 * 读侧只读取一个 volatile snapshot，因而不会观察到跨注册中心的半代数据。
 */
@Component
public final class DownloadExtensionRegistry {

    /** 下载页实际声明的稳定挂载点；其它页面的通用 UI slot 不进入本快照。 */
    private static final Set<String> DOWNLOAD_SLOT_TARGETS = Set.of(
            "cookie-tools",
            "quick-actions-bookmarks",
            "quick-actions-mine",
            "kind-option-quick",
            "import-hint",
            "kind-option-user",
            "kind-option-search",
            "search-filter",
            "settings-card");

    public record RegisteredDownloadType(
            DownloadExtensionOwner owner,
            long publicationId,
            DownloadTypeDescriptor descriptor
    ) {
        public RegisteredDownloadType {
            Objects.requireNonNull(owner, "download extension owner");
            Objects.requireNonNull(descriptor, "download type descriptor");
        }
    }

    public record RegisteredUiSlot(
            DownloadExtensionOwner owner,
            long publicationId,
            WebUiSlotContribution slot
    ) {
        public RegisteredUiSlot {
            Objects.requireNonNull(owner, "download extension owner");
            Objects.requireNonNull(slot, "download UI slot contribution");
        }
    }

    public record Snapshot(
            String epoch,
            long revision,
            List<RegisteredDownloadType> downloadTypes,
            List<RegisteredUiSlot> uiSlots
    ) {
        public Snapshot {
            if (epoch == null || epoch.isBlank()) {
                throw new IllegalArgumentException("download extension epoch must not be blank");
            }
            downloadTypes = List.copyOf(downloadTypes);
            uiSlots = List.copyOf(uiSlots);
        }

        static Snapshot empty(String epoch) {
            return new Snapshot(epoch, 0L, List.of(), List.of());
        }
    }

    /**
     * 已在锁外读取并完成 owner-local 校验的发布令牌。令牌绑定创建它的 registry 与精确 RegisteredPlugin 身份；
     * 最终发布仍会在 PluginRegistry 锁内复核身份，再以固定锁序提交本 registry 快照。
     */
    public static final class PreparedPublication {
        private final Object authority;
        private final PluginRegistry.RegisteredPlugin registered;
        private final DownloadExtensionOwner owner;
        private final OwnerBundle bundle;
        private boolean attempted;

        private PreparedPublication(Object authority,
                                    PluginRegistry.RegisteredPlugin registered,
                                    DownloadExtensionOwner owner,
                                    OwnerBundle bundle) {
            this.authority = authority;
            this.registered = registered;
            this.owner = owner;
            this.bundle = bundle;
        }

        private synchronized void beginAttempt(Object expectedAuthority) {
            if (authority != expectedAuthority) {
                throw new IllegalStateException(
                        "prepared download extension publication belongs to another registry");
            }
            if (attempted) {
                throw new IllegalStateException(
                        "prepared download extension publication already attempted for plugin: "
                                + registered.id());
            }
            attempted = true;
        }
    }

    private record OwnerBundle(
            List<DownloadTypeDescriptor> downloadTypes,
            List<WebUiSlotContribution> uiSlots
    ) {
        OwnerBundle {
            downloadTypes = List.copyOf(downloadTypes);
            uiSlots = List.copyOf(uiSlots);
        }

        boolean isEmpty() {
            return downloadTypes.isEmpty() && uiSlots.isEmpty();
        }
    }

    private record PublishedOwner(
            DownloadExtensionPublication publication,
            OwnerBundle bundle
    ) {
    }

    private final Object lock = new Object();
    private final Object preparationAuthority = new Object();
    private final String epoch = UUID.randomUUID().toString();
    private final PluginRegistry pluginRegistry;
    private final StaticResourceRegistry staticResourceRegistry;
    private final PluginOwnedWebAssetValidator assetValidator;
    private final Map<String, PublishedOwner> owners = new LinkedHashMap<>();
    private volatile Snapshot snapshot = Snapshot.empty(epoch);
    private long nextPublicationId;

    @Autowired
    public DownloadExtensionRegistry(PluginRegistry pluginRegistry,
                                     StaticResourceRegistry staticResourceRegistry,
                                     PluginOwnedWebAssetValidator assetValidator,
                                     WebUiSlotRegistry webUiSlotRegistry) {
        this.pluginRegistry = Objects.requireNonNull(pluginRegistry, "plugin registry");
        this.staticResourceRegistry = Objects.requireNonNull(staticResourceRegistry, "static resource registry");
        this.assetValidator = Objects.requireNonNull(assetValidator, "plugin owned web asset validator");
        for (PluginRegistry.RegisteredPlugin registered : pluginRegistry.registeredPlugins()) {
            if (webUiSlotRegistry == null) {
                publish(registered);
            } else {
                PixivFeaturePlugin plugin = registered.plugin();
                publish(preparePublication(
                        registered,
                        readList(registered.id(), "downloadTypes", plugin::downloadTypes),
                        webUiSlotRegistry.slotsFor(registered.id())));
            }
        }
    }

    /** 独立 registry 单测兼容构造器；生产 Spring 装配使用复用 WebUiSlotRegistry 快照的完整构造器。 */
    public DownloadExtensionRegistry(PluginRegistry pluginRegistry,
                                     StaticResourceRegistry staticResourceRegistry,
                                     PluginOwnedWebAssetValidator assetValidator) {
        this(pluginRegistry, staticResourceRegistry, assetValidator, null);
    }

    /** 返回当前不可变快照。 */
    public Snapshot snapshot() {
        return snapshot;
    }

    /** 从同一个当前快照按稳定 type 解析下载类型；未知或空白 type 返回空。 */
    public Optional<RegisteredDownloadType> resolveDownloadType(String type) {
        if (type == null || type.isBlank()) {
            return Optional.empty();
        }
        Snapshot current = snapshot;
        return current.downloadTypes().stream()
                .filter(registered -> type.equals(registered.descriptor().type()))
                .findFirst();
    }

    /**
     * 全量发布一个当前活动插件的下载扩展。所有 getter 与 owner-local 校验均在 registry 锁外完成；
     * 全局冲突失败时既有快照与 revision 保持不变。
     */
    public Optional<DownloadExtensionPublication> publish(PluginRegistry.RegisteredPlugin registered) {
        return publish(preparePublication(registered));
    }

    /** 在任何 serving registry 变更前，一次性读取插件 getter 并准备下载扩展。 */
    public PreparedPublication preparePublication(PluginRegistry.RegisteredPlugin registered) {
        requireActiveIdentity(registered);
        PixivFeaturePlugin plugin = registered.plugin();
        String pluginId = registered.id();
        return preparePublication(
                registered,
                readList(pluginId, "downloadTypes", plugin::downloadTypes),
                readList(pluginId, "uiSlots", plugin::uiSlots));
    }

    /**
     * 用调用方已安全快照化的贡献准备下载扩展；用于统一 web 注册事务复用同一份 uiSlots 快照。
     */
    public PreparedPublication preparePublication(
            PluginRegistry.RegisteredPlugin registered,
            List<DownloadTypeDescriptor> downloadTypes,
            List<WebUiSlotContribution> uiSlots) {
        return preparePublication(
                registered, downloadTypes, uiSlots,
                staticResourceRegistry.resourcesFor(registered));
    }

    /**
     * 用同一个锁外 web prepare 事务已解析的静态资源校验前端模块，无需提前发布 static 快照。
     */
    public PreparedPublication preparePublication(
            PluginRegistry.RegisteredPlugin registered,
            List<DownloadTypeDescriptor> downloadTypes,
            List<WebUiSlotContribution> uiSlots,
            List<StaticResourceRegistry.RegisteredStaticResource> staticResources) {
        requireActiveIdentity(registered);
        OwnerBundle prepared = prepareOwner(
                registered, List.copyOf(downloadTypes), List.copyOf(uiSlots),
                List.copyOf(staticResources));
        return new PreparedPublication(
                preparationAuthority, registered, DownloadExtensionOwner.from(registered), prepared);
    }

    /** 最终发布一个已准备令牌；身份复核与提交相对 PluginRegistry remove/replace 线性化。 */
    public Optional<DownloadExtensionPublication> publish(PreparedPublication prepared) {
        Objects.requireNonNull(prepared, "prepared download extension publication");
        prepared.beginAttempt(preparationAuthority);
        return pluginRegistry.commitIfActiveIdentity(
                prepared.registered, commit -> publishConsumed(prepared, commit));
    }

    /**
     * 在 PluginRegistry 已持有精确身份提交权时发布，不反向重入 PluginRegistry 锁。
     */
    public Optional<DownloadExtensionPublication> publish(
            PreparedPublication prepared,
            PluginRegistry.ActiveIdentityCommit commit) {
        Objects.requireNonNull(prepared, "prepared download extension publication");
        prepared.beginAttempt(preparationAuthority);
        return publishConsumed(prepared, commit);
    }

    private Optional<DownloadExtensionPublication> publishConsumed(
            PreparedPublication prepared,
            PluginRegistry.ActiveIdentityCommit commit) {
        pluginRegistry.requireActiveIdentityCommit(commit, prepared.registered);
        return prepared.bundle.isEmpty()
                ? Optional.empty()
                : commitPrepared(prepared.owner, prepared.bundle);
    }

    private Optional<DownloadExtensionPublication> commitPrepared(
            DownloadExtensionOwner owner, OwnerBundle prepared) {
        synchronized (lock) {
            if (owners.containsKey(owner.featurePluginId())) {
                throw new IllegalStateException("download extensions already published for plugin: "
                        + owner.featurePluginId());
            }
            long publicationId = Math.addExact(nextPublicationId, 1L);
            DownloadExtensionPublication publication =
                    new DownloadExtensionPublication(owner, publicationId);
            Map<String, PublishedOwner> next = new LinkedHashMap<>(owners);
            next.put(owner.featurePluginId(), new PublishedOwner(publication, prepared));
            Snapshot rebuilt = rebuild(next, Math.addExact(snapshot.revision(), 1L));
            owners.clear();
            owners.putAll(next);
            snapshot = rebuilt;
            nextPublicationId = publicationId;
            return Optional.of(publication);
        }
    }

    /**
     * 精确撤回一次 publication。旧 generation 或同 generation 的旧 token 都不能撤回当前快照。
     */
    public boolean withdraw(DownloadExtensionPublication publication) {
        if (publication == null) {
            return false;
        }
        synchronized (lock) {
            PublishedOwner current = owners.get(publication.owner().featurePluginId());
            if (current == null || current.publication() != publication) {
                return false;
            }
            Map<String, PublishedOwner> next = new LinkedHashMap<>(owners);
            next.remove(publication.owner().featurePluginId());
            Snapshot rebuilt = rebuild(next, Math.addExact(snapshot.revision(), 1L));
            owners.clear();
            owners.putAll(next);
            snapshot = rebuilt;
            return true;
        }
    }

    /** 启动期已发布贡献对应的精确 token；只接受同 owner/package/generation 的活动注册身份。 */
    public Optional<DownloadExtensionPublication> currentPublication(
            PluginRegistry.RegisteredPlugin registered) {
        requireActiveIdentity(registered);
        DownloadExtensionOwner expected = DownloadExtensionOwner.from(registered);
        synchronized (lock) {
            PublishedOwner current = owners.get(expected.featurePluginId());
            if (current == null || !current.publication().owner().equals(expected)) {
                return Optional.empty();
            }
            return Optional.of(current.publication());
        }
    }

    private OwnerBundle prepareOwner(PluginRegistry.RegisteredPlugin registered,
                                     List<DownloadTypeDescriptor> downloadTypes,
                                     List<WebUiSlotContribution> allSlots,
                                     List<StaticResourceRegistry.RegisteredStaticResource> staticResources) {
        String pluginId = registered.id();
        List<WebUiSlotContribution> downloadSlots = allSlots.stream()
                .filter(slot -> slot != null && DOWNLOAD_SLOT_TARGETS.contains(slot.target()))
                .toList();

        boolean needsAssets = !downloadTypes.isEmpty()
                || downloadSlots.stream().anyMatch(item -> item != null && item.moduleUrl() != null);
        List<StaticResourceRegistry.RegisteredStaticResource> resources = needsAssets
                ? staticResources
                : List.of();

        for (DownloadTypeDescriptor descriptor : downloadTypes) {
            validateDescriptor(descriptor, pluginId);
            assetValidator.validateOwnedJavaScript(
                    registered, resources, descriptor.moduleUrl(),
                    "download type '" + descriptor.type() + "'");
        }
        for (WebUiSlotContribution slot : downloadSlots) {
            validateUiSlot(slot, pluginId);
            if (slot.moduleUrl() != null) {
                assetValidator.validateOwnedJavaScript(
                        registered, resources, slot.moduleUrl(),
                        "download UI slot '" + slot.slotId() + "'");
            }
        }
        return new OwnerBundle(downloadTypes, downloadSlots);
    }

    private Snapshot rebuild(Map<String, PublishedOwner> nextOwners, long revision) {
        List<RegisteredDownloadType> downloadTypes = new ArrayList<>();
        List<RegisteredUiSlot> uiSlots = new ArrayList<>();
        Set<String> typeIds = new HashSet<>();
        Set<String> slotIds = new HashSet<>();

        for (PublishedOwner published : nextOwners.values()) {
            DownloadExtensionPublication publication = published.publication();
            for (DownloadTypeDescriptor descriptor : published.bundle().downloadTypes()) {
                if (!typeIds.add(descriptor.type())) {
                    throw new IllegalStateException("duplicate download type: " + descriptor.type());
                }
                downloadTypes.add(new RegisteredDownloadType(
                        publication.owner(), publication.publicationId(), descriptor));
            }
            for (WebUiSlotContribution slot : published.bundle().uiSlots()) {
                if (!slotIds.add(slot.slotId())) {
                    throw new IllegalStateException("duplicate download UI slot: " + slot.slotId());
                }
                uiSlots.add(new RegisteredUiSlot(
                        publication.owner(), publication.publicationId(), slot));
            }
        }

        return new Snapshot(epoch, revision, downloadTypes, uiSlots);
    }

    private void requireActiveIdentity(PluginRegistry.RegisteredPlugin registered) {
        Objects.requireNonNull(registered, "registered plugin");
        if (!pluginRegistry.isActiveIdentity(registered)) {
            throw new IllegalStateException(
                    "download extension publication is not the current active plugin identity");
        }
    }

    private static <T> List<T> readList(String pluginId, String field, Supplier<List<T>> reader) {
        try {
            List<T> value = reader.get();
            if (value == null) {
                throw new IllegalStateException("plugin returned null");
            }
            return List.copyOf(value);
        } catch (Throwable failure) {
            rethrowFatal(failure);
            throw new IllegalStateException("failed to read download extension contribution '" + field
                    + "' for plugin '" + pluginId + "' (failureType="
                    + failure.getClass().getName() + ")");
        }
    }

    private static void rethrowFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError virtualMachineError) {
            throw virtualMachineError;
        }
        if (failure instanceof ThreadDeath threadDeath) {
            throw threadDeath;
        }
    }

    private static void validateUiSlot(WebUiSlotContribution slot, String pluginId) {
        if (slot == null) {
            throw new IllegalStateException("null download UI slot contribution (plugin: " + pluginId + ")");
        }
        requireText(slot.slotId(), "download UI slot id", pluginId);
        requireText(slot.target(), "download UI slot target", pluginId);
    }

    static void validateDescriptor(DownloadTypeDescriptor descriptor, String pluginId) {
        if (descriptor == null) {
            throw new IllegalStateException("null download type descriptor (plugin: " + pluginId + ")");
        }
        if (descriptor.contractVersion() != DownloadTypeDescriptor.CURRENT_CONTRACT_VERSION) {
            throw new IllegalStateException("unsupported download type descriptor version: "
                    + descriptor.contractVersion() + " (type: " + descriptor.type() + ")");
        }
        requireText(descriptor.type(), "download type id", pluginId);
        requireText(descriptor.displayNamespace(), "download type display namespace", pluginId);
        requireText(descriptor.displayI18nKey(), "download type display i18n key", pluginId);
        requireText(descriptor.iconKey(), "download type icon key", pluginId);
        requireText(descriptor.colorToken(), "download type color token", pluginId);
        requireText(descriptor.moduleUrl(), "download type module URL", pluginId);
        requireText(descriptor.i18nNamespace(), "download type i18n namespace", pluginId);
        rejectDuplicatesAndNulls(
                descriptor.acquisitionModes(), "acquisition mode", descriptor.type(), pluginId);
        rejectBlankOrDuplicateStrings(descriptor.filters(), "filter", descriptor.type(), pluginId);
        rejectBlankOrDuplicateStrings(descriptor.settings(), "setting", descriptor.type(), pluginId);
    }

    private static <T> void rejectDuplicatesAndNulls(Collection<T> values,
                                                     String label,
                                                     String type,
                                                     String pluginId) {
        Set<T> seen = new HashSet<>();
        for (T value : values) {
            if (value == null) {
                throw new IllegalStateException("download type descriptor has null " + label + ": "
                        + type + " (plugin: " + pluginId + ")");
            }
            if (!seen.add(value)) {
                throw new IllegalStateException("download type descriptor has duplicate " + label + ": "
                        + value + " (type: " + type + ", plugin: " + pluginId + ")");
            }
        }
    }

    private static void rejectBlankOrDuplicateStrings(Collection<String> values,
                                                      String label,
                                                      String type,
                                                      String pluginId) {
        if (values == null) {
            throw new IllegalStateException(label + " list must not be null: " + type);
        }
        Set<String> seen = new HashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new IllegalStateException("blank " + label + ": "
                        + type + " (plugin: " + pluginId + ")");
            }
            if (!seen.add(value)) {
                throw new IllegalStateException("duplicate " + label + ": "
                        + value + " (type: " + type + ", plugin: " + pluginId + ")");
            }
        }
    }

    private static void requireText(String value, String label, String pluginId) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(label + " must not be blank (plugin: " + pluginId + ")");
        }
    }
}
