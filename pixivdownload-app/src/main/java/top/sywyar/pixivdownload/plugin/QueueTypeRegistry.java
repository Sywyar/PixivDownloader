package top.sywyar.pixivdownload.plugin;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.web.QueueTypeContribution;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 下载队列作品类型注册中心。收集各插件的 {@link QueueTypeContribution}，
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

    private volatile List<RegisteredQueueType> snapshot = List.of();

    public QueueTypeRegistry(PluginRegistry pluginRegistry) {
        for (PixivFeaturePlugin plugin : pluginRegistry.plugins()) {
            List<QueueTypeContribution> queueTypes = plugin.queueTypes();
            if (!queueTypes.isEmpty()) {
                register(plugin.id(), queueTypes);
            }
        }
    }

    /**
     * 注册一个插件的全部作品类型。同一 pluginId 重复注册、类型非法、类型 id 与已注册项冲突都立即抛出。
     */
    public void register(String pluginId, List<QueueTypeContribution> queueTypes) {
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
        synchronized (lock) {
            snapshot = snapshot.stream()
                    .filter(registered -> !registered.pluginId().equals(pluginId))
                    .collect(Collectors.collectingAndThen(Collectors.toList(), List::copyOf));
        }
    }

    /** 按注册顺序返回全部作品类型的不可变快照。 */
    public List<RegisteredQueueType> queueTypes() {
        return snapshot;
    }

    private static void validate(QueueTypeContribution queueType, String pluginId) {
        if (queueType == null) {
            throw new IllegalStateException("null queue type contribution (plugin: " + pluginId + ")");
        }
        if (queueType.type() == null || queueType.type().isBlank()) {
            throw new IllegalStateException("queue type without type id (plugin: " + pluginId + ")");
        }
        if (queueType.labelI18nKey() == null || queueType.labelI18nKey().isBlank()) {
            throw new IllegalStateException("queue type without label i18n key: "
                    + queueType.type() + " (plugin: " + pluginId + ")");
        }
    }
}
