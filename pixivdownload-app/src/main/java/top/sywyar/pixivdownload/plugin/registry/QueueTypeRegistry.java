package top.sywyar.pixivdownload.plugin.registry;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.web.DownloadTypeDescriptor;
import top.sywyar.pixivdownload.plugin.api.web.QueueTypeContribution;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 下载队列作品类型兼容门面。生产读路径投影 {@link DownloadExtensionRegistry} 的单一 owner 原子快照；
 * 保留接收 {@link PluginRegistry} 的构造器与可逆写方法，供尚未迁移的独立测试 / 兼容调用使用。
 * <p>
 * 旧实现收集各插件的 {@link QueueTypeContribution}，
 * 按 pluginId 可逆注册（{@link #register} / {@link #unregister}），
 * 读路径走不可变快照：注册变更时整体替换快照引用，读侧无锁
 * （{@code /api/download/extensions} 在每次请求上读取）。
 * <p>
 * 镜像 {@link NavigationRegistry}：快照按注册顺序返回，排序与展示由消费端完成。
 * 类型 id（{@code type}）全局唯一——同一类型只能由一个插件声明，重复立即抛出使应用启动失败，
 * 避免两个插件对同一 {@code kind} 注册彼此不一致的下载行为。
 */
@Component
public class QueueTypeRegistry {

    /** 一条已注册作品类型及其声明方插件。 */
    public record RegisteredQueueType(String pluginId, QueueTypeContribution queueType) {
    }

    private final Object lock = new Object();

    private final DownloadExtensionRegistry extensionRegistry;

    private volatile List<RegisteredQueueType> snapshot = List.of();

    @Autowired
    public QueueTypeRegistry(DownloadExtensionRegistry extensionRegistry) {
        this.extensionRegistry = extensionRegistry;
    }

    public QueueTypeRegistry(PluginRegistry pluginRegistry) {
        this.extensionRegistry = null;
        for (PluginRegistry.RegisteredPlugin registered : pluginRegistry.registeredPlugins()) {
            PixivFeaturePlugin plugin = registered.plugin();
            List<QueueTypeContribution> queueTypes = plugin.queueTypes();
            if (!queueTypes.isEmpty()) {
                register(registered.id(), queueTypes);
            }
        }
    }

    /**
     * 注册一个插件的全部作品类型。同一 pluginId 重复注册、类型非法、类型 id 与已注册项冲突都立即抛出。
     */
    public void register(String pluginId, List<QueueTypeContribution> queueTypes) {
        requireLegacyMutationMode();
        if (pluginId == null || pluginId.isBlank()) {
            throw new IllegalStateException("queue type contribution without pluginId");
        }
        if (queueTypes == null || queueTypes.isEmpty()) {
            throw new IllegalStateException("empty queue type contribution (plugin: " + pluginId + ")");
        }
        synchronized (lock) {
            if (snapshot.stream().anyMatch(registered -> registered.pluginId().equals(pluginId))) {
                throw new IllegalStateException("queue types already registered for plugin: " + pluginId);
            }
            Set<String> types = snapshot.stream()
                    .map(registered -> registered.queueType().type())
                    .collect(Collectors.toCollection(HashSet::new));
            List<RegisteredQueueType> next = new ArrayList<>(snapshot);
            for (QueueTypeContribution item : queueTypes) {
                validate(item, pluginId);
                if (!types.add(item.type())) {
                    throw new IllegalStateException("duplicate queue type: "
                            + item.type() + " (plugin: " + pluginId + ")");
                }
                next.add(new RegisteredQueueType(pluginId, item));
            }
            snapshot = List.copyOf(next);
        }
    }

    /**
     * 注销一个插件的全部作品类型。插件可以不声明任何类型，统一卸载流程会对每个插件调用，
     * 因此对未注册过的 pluginId 静默返回。
     */
    public void unregister(String pluginId) {
        requireLegacyMutationMode();
        synchronized (lock) {
            snapshot = snapshot.stream()
                    .filter(registered -> !registered.pluginId().equals(pluginId))
                    .collect(Collectors.collectingAndThen(Collectors.toList(), List::copyOf));
        }
    }

    /** 按注册顺序返回全部作品类型的不可变快照。 */
    public List<RegisteredQueueType> queueTypes() {
        if (extensionRegistry != null) {
            return extensionRegistry.snapshot().queueTypes().stream()
                    .map(registered -> new RegisteredQueueType(
                            registered.owner().featurePluginId(), registered.queueType()))
                    .toList();
        }
        return snapshot;
    }

    private void requireLegacyMutationMode() {
        if (extensionRegistry != null) {
            throw new UnsupportedOperationException(
                    "download extension mutations must use DownloadExtensionRegistry publications");
        }
    }

    private static void validate(QueueTypeContribution queueType, String pluginId) {
        if (queueType == null) {
            throw new IllegalStateException("null queue type contribution (plugin: " + pluginId + ")");
        }
        if (!pluginId.equals(queueType.pluginId())) {
            throw new IllegalStateException("queue type pluginId mismatch: declared "
                    + queueType.pluginId() + " under plugin " + pluginId);
        }
        if (queueType.type() == null || queueType.type().isBlank()) {
            throw new IllegalStateException("queue type without type id (plugin: " + pluginId + ")");
        }
        if (queueType.labelI18nKey() == null || queueType.labelI18nKey().isBlank()) {
            throw new IllegalStateException("queue type without label i18n key: "
                    + queueType.type() + " (plugin: " + pluginId + ")");
        }
        // labelNamespace 必填：labelI18nKey 是纯 key，必须有确定 namespace 才能在前端解析（tns(namespace, key)）。
        // 留空会让前端 tns 退化为裸 key、在页面首个 namespace 内误解析，故注册期 fail-fast。
        if (queueType.labelNamespace() == null || queueType.labelNamespace().isBlank()) {
            throw new IllegalStateException("queue type without label namespace: "
                    + queueType.type() + " (plugin: " + pluginId + ")");
        }
        if (queueType.moduleUrl() != null && !isSameOriginAbsolutePath(queueType.moduleUrl())) {
            throw new IllegalStateException("queue type moduleUrl must be a same-origin absolute path starting with '/' "
                    + "(no scheme / protocol-relative): " + queueType.moduleUrl()
                    + " (type: " + queueType.type() + ", plugin: " + pluginId + ")");
        }
        validateDescriptor(queueType.descriptor(), queueType, pluginId);
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
                    + descriptor.contractVersion() + " (type: " + queueType.type()
                    + ", plugin: " + pluginId + ")");
        }
        if (!pluginId.equals(descriptor.pluginId())) {
            throw new IllegalStateException("download type descriptor pluginId mismatch: declared "
                    + descriptor.pluginId() + " under plugin " + pluginId);
        }
        if (!queueType.type().equals(descriptor.type())) {
            throw new IllegalStateException("download type descriptor type mismatch: declared "
                    + descriptor.type() + " for queue type " + queueType.type()
                    + " (plugin: " + pluginId + ")");
        }
        if (!queueType.labelNamespace().equals(descriptor.displayNamespace())
                || !queueType.labelI18nKey().equals(descriptor.displayI18nKey())) {
            throw new IllegalStateException("download type descriptor display mismatch: "
                    + queueType.type() + " (plugin: " + pluginId + ")");
        }
        if (queueType.order() != descriptor.order()) {
            throw new IllegalStateException("download type descriptor order mismatch: "
                    + queueType.type() + " (plugin: " + pluginId + ")");
        }
        if (!equalsNullable(queueType.moduleUrl(), descriptor.moduleUrl())) {
            throw new IllegalStateException("download type descriptor moduleUrl mismatch: "
                    + queueType.type() + " (plugin: " + pluginId + ")");
        }
        if (descriptor.moduleUrl() != null && !isSameOriginAbsolutePath(descriptor.moduleUrl())) {
            throw new IllegalStateException("download type descriptor moduleUrl must be a same-origin absolute path: "
                    + descriptor.moduleUrl() + " (type: " + queueType.type()
                    + ", plugin: " + pluginId + ")");
        }
        if (descriptor.i18nNamespace() == null || descriptor.i18nNamespace().isBlank()) {
            throw new IllegalStateException("download type descriptor without i18n namespace: "
                    + queueType.type() + " (plugin: " + pluginId + ")");
        }
        rejectDuplicatesAndNulls(descriptor.acquisitionModes(), "acquisition mode", queueType.type(), pluginId);
        rejectBlankOrDuplicateStrings(descriptor.filters(), "filter", queueType.type(), pluginId);
        rejectBlankOrDuplicateStrings(descriptor.settings(), "setting", queueType.type(), pluginId);
        rejectBlankOrDuplicateStrings(descriptor.uiSlots(), "ui slot", queueType.type(), pluginId);
        if (descriptor.queue() == null) {
            throw new IllegalStateException("download type descriptor without queue capabilities: "
                    + queueType.type() + " (plugin: " + pluginId + ")");
        }
        if (descriptor.schedule() == null) {
            throw new IllegalStateException("download type descriptor without schedule capabilities: "
                    + queueType.type() + " (plugin: " + pluginId + ")");
        }
        if (descriptor.gallery() == null) {
            throw new IllegalStateException("download type descriptor without gallery capabilities: "
                    + queueType.type() + " (plugin: " + pluginId + ")");
        }
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
        Set<String> seen = new HashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new IllegalStateException("download type descriptor has blank " + label + ": "
                        + type + " (plugin: " + pluginId + ")");
            }
            if (!seen.add(value)) {
                throw new IllegalStateException("download type descriptor has duplicate " + label + ": "
                        + value + " (type: " + type + ", plugin: " + pluginId + ")");
            }
        }
    }

    private static boolean equalsNullable(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private static boolean isSameOriginAbsolutePath(String value) {
        if (value.isEmpty() || value.charAt(0) != '/') {
            return false;
        }
        if (value.length() >= 2) {
            char second = value.charAt(1);
            return second != '/' && second != '\\';
        }
        return true;
    }
}
