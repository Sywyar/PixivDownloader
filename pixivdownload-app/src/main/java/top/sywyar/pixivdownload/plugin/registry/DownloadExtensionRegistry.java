package top.sywyar.pixivdownload.plugin.registry;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.web.DownloadAcquisitionMode;
import top.sywyar.pixivdownload.plugin.api.web.DownloadTypeDescriptor;
import top.sywyar.pixivdownload.plugin.api.web.QueueTypeContribution;
import top.sywyar.pixivdownload.plugin.api.web.TabContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebUiSlotContribution;
import top.sywyar.pixivdownload.plugin.web.PluginOwnedWebAssetValidator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 下载工作台扩展的单一 owner 原子快照。每个插件一次发布 queue type、tab 与下载页 UI slot，
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

    public record RegisteredQueueType(
            DownloadExtensionOwner owner,
            long publicationId,
            QueueTypeContribution queueType
    ) {
        public RegisteredQueueType {
            Objects.requireNonNull(owner, "download extension owner");
            Objects.requireNonNull(queueType, "queue type contribution");
        }
    }

    public record RegisteredTab(
            DownloadExtensionOwner owner,
            long publicationId,
            TabContribution tab,
            List<String> supportedQueueTypes
    ) {
        public RegisteredTab {
            Objects.requireNonNull(owner, "download extension owner");
            Objects.requireNonNull(tab, "download tab contribution");
            supportedQueueTypes = List.copyOf(supportedQueueTypes);
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
            List<RegisteredQueueType> queueTypes,
            List<RegisteredTab> tabs,
            List<RegisteredUiSlot> uiSlots
    ) {
        public Snapshot {
            if (epoch == null || epoch.isBlank()) {
                throw new IllegalArgumentException("download extension epoch must not be blank");
            }
            queueTypes = List.copyOf(queueTypes);
            tabs = List.copyOf(tabs);
            uiSlots = List.copyOf(uiSlots);
        }

        static Snapshot empty(String epoch) {
            return new Snapshot(epoch, 0L, List.of(), List.of(), List.of());
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
            List<QueueTypeContribution> queueTypes,
            List<TabContribution> tabs,
            List<WebUiSlotContribution> uiSlots
    ) {
        OwnerBundle {
            queueTypes = List.copyOf(queueTypes);
            tabs = List.copyOf(tabs);
            uiSlots = List.copyOf(uiSlots);
        }

        boolean isEmpty() {
            return queueTypes.isEmpty() && tabs.isEmpty() && uiSlots.isEmpty();
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
                        readList(registered.id(), "queueTypes", plugin::queueTypes),
                        readList(registered.id(), "downloadTabs", plugin::downloadTabs),
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
                readList(pluginId, "queueTypes", plugin::queueTypes),
                readList(pluginId, "downloadTabs", plugin::downloadTabs),
                readList(pluginId, "uiSlots", plugin::uiSlots));
    }

    /**
     * 用调用方已安全快照化的贡献准备下载扩展；用于统一 web 注册事务复用同一份 uiSlots 快照。
     */
    public PreparedPublication preparePublication(
            PluginRegistry.RegisteredPlugin registered,
            List<QueueTypeContribution> queueTypes,
            List<TabContribution> tabs,
            List<WebUiSlotContribution> uiSlots) {
        return preparePublication(
                registered, queueTypes, tabs, uiSlots,
                staticResourceRegistry.resourcesFor(registered));
    }

    /**
     * 用同一个锁外 web prepare 事务已解析的静态资源校验前端模块，无需提前发布 static 快照。
     */
    public PreparedPublication preparePublication(
            PluginRegistry.RegisteredPlugin registered,
            List<QueueTypeContribution> queueTypes,
            List<TabContribution> tabs,
            List<WebUiSlotContribution> uiSlots,
            List<StaticResourceRegistry.RegisteredStaticResource> staticResources) {
        requireActiveIdentity(registered);
        OwnerBundle prepared = prepareOwner(
                registered, List.copyOf(queueTypes), List.copyOf(tabs), List.copyOf(uiSlots),
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
                                     List<QueueTypeContribution> queueTypes,
                                     List<TabContribution> tabs,
                                     List<WebUiSlotContribution> allSlots,
                                     List<StaticResourceRegistry.RegisteredStaticResource> staticResources) {
        String pluginId = registered.id();
        List<WebUiSlotContribution> downloadSlots = allSlots.stream()
                .filter(slot -> slot != null && DOWNLOAD_SLOT_TARGETS.contains(slot.target()))
                .toList();

        boolean needsAssets = queueTypes.stream().anyMatch(item -> item != null && item.moduleUrl() != null)
                || downloadSlots.stream().anyMatch(item -> item != null && item.moduleUrl() != null);
        List<StaticResourceRegistry.RegisteredStaticResource> resources = needsAssets
                ? staticResources
                : List.of();

        for (QueueTypeContribution queueType : queueTypes) {
            validateQueueType(queueType, pluginId);
            if (queueType.moduleUrl() != null) {
                assetValidator.validateOwnedJavaScript(
                        registered, resources, queueType.moduleUrl(),
                        "download queue type '" + queueType.type() + "'");
            }
        }
        for (TabContribution tab : tabs) {
            validateTab(tab, pluginId);
        }
        for (WebUiSlotContribution slot : downloadSlots) {
            validateUiSlot(slot, pluginId);
            if (slot.moduleUrl() != null) {
                assetValidator.validateOwnedJavaScript(
                        registered, resources, slot.moduleUrl(),
                        "download UI slot '" + slot.slotId() + "'");
            }
        }
        return new OwnerBundle(queueTypes, tabs, downloadSlots);
    }

    private Snapshot rebuild(Map<String, PublishedOwner> nextOwners, long revision) {
        List<RegisteredQueueType> queueTypes = new ArrayList<>();
        List<RegisteredUiSlot> uiSlots = new ArrayList<>();
        Set<String> typeIds = new HashSet<>();
        Set<String> slotIds = new HashSet<>();

        for (PublishedOwner published : nextOwners.values()) {
            DownloadExtensionPublication publication = published.publication();
            for (QueueTypeContribution queueType : published.bundle().queueTypes()) {
                if (!typeIds.add(queueType.type())) {
                    throw new IllegalStateException("duplicate download queue type: " + queueType.type());
                }
                queueTypes.add(new RegisteredQueueType(
                        publication.owner(), publication.publicationId(), queueType));
            }
            for (WebUiSlotContribution slot : published.bundle().uiSlots()) {
                if (!slotIds.add(slot.slotId())) {
                    throw new IllegalStateException("duplicate download UI slot: " + slot.slotId());
                }
                uiSlots.add(new RegisteredUiSlot(
                        publication.owner(), publication.publicationId(), slot));
            }
        }

        List<RegisteredTab> tabs = new ArrayList<>();
        Set<String> tabIds = new HashSet<>();
        for (PublishedOwner published : nextOwners.values()) {
            DownloadExtensionPublication publication = published.publication();
            for (TabContribution tab : published.bundle().tabs()) {
                if (!tabIds.add(tab.tabId())) {
                    throw new IllegalStateException("duplicate download tab id: " + tab.tabId());
                }
                DownloadAcquisitionMode mode = acquisitionMode(tab.tabId());
                Set<String> explicitLimit = new LinkedHashSet<>(tab.supportedQueueTypes());
                List<String> supported = queueTypes.stream()
                        .map(RegisteredQueueType::queueType)
                        .filter(queueType -> queueType.descriptor().acquisitionModes().contains(mode))
                        .map(QueueTypeContribution::type)
                        .filter(type -> explicitLimit.isEmpty() || explicitLimit.contains(type))
                        .toList();
                tabs.add(new RegisteredTab(
                        publication.owner(), publication.publicationId(), tab, supported));
            }
        }
        return new Snapshot(epoch, revision, queueTypes, tabs, uiSlots);
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

    static void validateQueueType(QueueTypeContribution queueType, String pluginId) {
        if (queueType == null) {
            throw new IllegalStateException("null queue type contribution (plugin: " + pluginId + ")");
        }
        if (!pluginId.equals(queueType.pluginId())) {
            throw new IllegalStateException("queue type pluginId mismatch: declared "
                    + queueType.pluginId() + " under plugin " + pluginId);
        }
        requireText(queueType.type(), "queue type id", pluginId);
        requireText(queueType.labelNamespace(), "queue type label namespace", pluginId);
        requireText(queueType.labelI18nKey(), "queue type label i18n key", pluginId);
        validateDescriptor(queueType.descriptor(), queueType, pluginId);
    }

    static void validateTab(TabContribution tab, String pluginId) {
        if (tab == null) {
            throw new IllegalStateException("null download tab contribution (plugin: " + pluginId + ")");
        }
        if (!pluginId.equals(tab.pluginId())) {
            throw new IllegalStateException("download tab pluginId mismatch: declared "
                    + tab.pluginId() + " under plugin " + pluginId);
        }
        requireText(tab.tabId(), "download tab id", pluginId);
        acquisitionMode(tab.tabId());
        rejectBlankOrDuplicateStrings(
                tab.supportedQueueTypes(), "supported queue type", tab.tabId(), pluginId);
    }

    private static void validateUiSlot(WebUiSlotContribution slot, String pluginId) {
        if (slot == null) {
            throw new IllegalStateException("null download UI slot contribution (plugin: " + pluginId + ")");
        }
        if (!pluginId.equals(slot.pluginId())) {
            throw new IllegalStateException("download UI slot pluginId mismatch: declared "
                    + slot.pluginId() + " under plugin " + pluginId);
        }
        requireText(slot.slotId(), "download UI slot id", pluginId);
        requireText(slot.target(), "download UI slot target", pluginId);
        if (slot.metadata() == null) {
            throw new IllegalStateException("download UI slot metadata must not be null: " + slot.slotId());
        }
    }

    private static void validateDescriptor(DownloadTypeDescriptor descriptor,
                                           QueueTypeContribution queueType,
                                           String pluginId) {
        if (descriptor == null) {
            throw new IllegalStateException("queue type without descriptor: "
                    + queueType.type() + " (plugin: " + pluginId + ")");
        }
        if (descriptor.contractVersion() != DownloadTypeDescriptor.CURRENT_CONTRACT_VERSION) {
            throw new IllegalStateException("unsupported download type descriptor version: "
                    + descriptor.contractVersion() + " (type: " + queueType.type() + ")");
        }
        if (!pluginId.equals(descriptor.pluginId()) || !queueType.type().equals(descriptor.type())) {
            throw new IllegalStateException("download type descriptor owner/type mismatch: "
                    + queueType.type() + " (plugin: " + pluginId + ")");
        }
        if (!queueType.labelNamespace().equals(descriptor.displayNamespace())
                || !queueType.labelI18nKey().equals(descriptor.displayI18nKey())
                || queueType.order() != descriptor.order()
                || !Objects.equals(queueType.moduleUrl(), descriptor.moduleUrl())) {
            throw new IllegalStateException("download type descriptor projection mismatch: "
                    + queueType.type() + " (plugin: " + pluginId + ")");
        }
        requireText(descriptor.i18nNamespace(), "download type i18n namespace", pluginId);
        rejectDuplicatesAndNulls(
                descriptor.acquisitionModes(), "acquisition mode", queueType.type(), pluginId);
        rejectBlankOrDuplicateStrings(descriptor.filters(), "filter", queueType.type(), pluginId);
        rejectBlankOrDuplicateStrings(descriptor.settings(), "setting", queueType.type(), pluginId);
        rejectBlankOrDuplicateStrings(descriptor.uiSlots(), "ui slot", queueType.type(), pluginId);
        if (descriptor.queue() == null || descriptor.schedule() == null || descriptor.gallery() == null) {
            throw new IllegalStateException("download type descriptor has incomplete capabilities: "
                    + queueType.type() + " (plugin: " + pluginId + ")");
        }
    }

    private static DownloadAcquisitionMode acquisitionMode(String tabId) {
        return switch (tabId) {
            case "single-import" -> DownloadAcquisitionMode.SINGLE_IMPORT;
            case "user" -> DownloadAcquisitionMode.USER_PROFILE;
            case "series" -> DownloadAcquisitionMode.SERIES_COLLECTION;
            case "search" -> DownloadAcquisitionMode.SEARCH;
            case "quick-fetch" -> DownloadAcquisitionMode.QUICK;
            default -> throw new IllegalStateException("download tab has no stable acquisition mode: " + tabId);
        };
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
