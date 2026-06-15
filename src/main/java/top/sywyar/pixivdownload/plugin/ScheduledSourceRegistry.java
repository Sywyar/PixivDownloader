package top.sywyar.pixivdownload.plugin;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.schedule.ScheduledSourceProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 计划任务来源注册中心。收集各插件的 {@link PixivFeaturePlugin#scheduledSources()}，
 * 按 pluginId 可逆注册（{@link #register} / {@link #unregister}）。读路径走单个不可变快照
 * （{@code sources} 列表 +「规范 type → provider」+「legacy type → provider」两套索引同在一个
 * {@link Snapshot} 对象里）：注册 / 注销在锁内构建新快照、一次性替换引用，读侧无锁且永不观察到
 * 半更新的中间态；注册或注销失败时既有快照原样保留、不被污染。索引供调度器把任务的存量
 * {@code type} 解析到对应 provider。
 *
 * <p>规范 type 与 legacy 名共享同一全局命名空间、要求两两互不相同：同类重复（type↔type、
 * legacy↔legacy）与跨类碰撞（某 provider 的 type 等于另一 provider 的 legacy 名，或反之）都立即
 * 抛出，使应用启动失败而不是带病运行——否则 {@link #resolve} 的「先规范后 legacy」会把存量任务的
 * {@code type} 静默解析到错误 provider。
 *
 * <p>本类维护可逆快照与索引，但调度器仍按自身的派发逻辑执行、尚未改用本注册中心，因此目前无运行期消费者。
 */
@Component
public class ScheduledSourceRegistry {

    /** 一条已注册来源及其声明方插件。 */
    public record RegisteredSource(String pluginId, ScheduledSourceProvider provider) {
    }

    /**
     * 单个不可变快照：注册顺序的来源列表 + 两套解析索引一体发布。读侧只读 {@code snapshot}
     * 引用一次即拿到三者的一致视图，杜绝跨多个引用的半更新。
     */
    private record Snapshot(List<RegisteredSource> sources,
                            Map<String, ScheduledSourceProvider> typeIndex,
                            Map<String, ScheduledSourceProvider> legacyIndex) {

        static final Snapshot EMPTY = new Snapshot(List.of(), Map.of(), Map.of());
    }

    private final Object lock = new Object();

    private volatile Snapshot snapshot = Snapshot.EMPTY;

    public ScheduledSourceRegistry(PluginRegistry pluginRegistry) {
        for (PixivFeaturePlugin plugin : pluginRegistry.plugins()) {
            List<ScheduledSourceProvider> sources = plugin.scheduledSources();
            if (!sources.isEmpty()) {
                register(plugin.id(), sources);
            }
        }
    }

    /** 以内置插件清单构建注册中心，供 Spring 上下文之外的入口（测试 / 启动期检查等）使用。 */
    public static ScheduledSourceRegistry forBuiltInPlugins() {
        return new ScheduledSourceRegistry(new PluginRegistry(BuiltInPlugins.createAll()));
    }

    /**
     * 注册一个插件的全部来源。同一 pluginId 重复注册、来源非法、规范 type 或 legacy 名与已注册项
     * （或同一批次内）冲突都立即抛出；失败时整批不生效、既有快照保持不变。
     */
    public void register(String pluginId, List<ScheduledSourceProvider> sources) {
        if (pluginId == null || pluginId.isBlank()) {
            throw new IllegalStateException("scheduled source contribution without pluginId");
        }
        if (sources == null || sources.isEmpty()) {
            throw new IllegalStateException("empty scheduled source contribution (plugin: " + pluginId + ")");
        }
        synchronized (lock) {
            List<RegisteredSource> current = snapshot.sources();
            if (current.stream().anyMatch(registered -> registered.pluginId().equals(pluginId))) {
                throw new IllegalStateException("scheduled sources already registered for plugin: " + pluginId);
            }
            List<RegisteredSource> next = new ArrayList<>(current);
            for (ScheduledSourceProvider provider : sources) {
                validate(provider, pluginId);
                next.add(new RegisteredSource(pluginId, provider));
            }
            // 先在局部构建新快照（含两套索引、校验全局唯一），全部通过后再一次性替换引用；
            // 任一冲突在替换前抛出，既有快照原样保留、读侧不受影响。
            snapshot = buildSnapshot(next);
        }
    }

    /**
     * 注销一个插件的全部来源。插件可以不声明任何来源，统一卸载流程会对每个插件调用，
     * 因此对未注册过的 pluginId 静默返回（不改快照）。
     */
    public void unregister(String pluginId) {
        synchronized (lock) {
            List<RegisteredSource> current = snapshot.sources();
            List<RegisteredSource> next = new ArrayList<>(current.size());
            for (RegisteredSource registered : current) {
                if (!registered.pluginId().equals(pluginId)) {
                    next.add(registered);
                }
            }
            if (next.size() == current.size()) {
                return;
            }
            snapshot = buildSnapshot(next);
        }
    }

    /** 按注册顺序返回全部来源的不可变快照。 */
    public List<RegisteredSource> sources() {
        return snapshot.sources();
    }

    /** 按规范 type 字符串取 provider；空 / 无匹配返回 {@link Optional#empty()}。 */
    public Optional<ScheduledSourceProvider> byType(String type) {
        if (type == null || type.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(snapshot.typeIndex().get(type));
    }

    /**
     * 解析任务存量 {@code type}：先按规范 type 命中，未命中再回退 legacy 类型字符串
     * （兼容数据库 {@code type} 列里已存的枚举名）。空 / 无匹配返回 {@link Optional#empty()}。
     */
    public Optional<ScheduledSourceProvider> resolve(String typeOrLegacy) {
        if (typeOrLegacy == null || typeOrLegacy.isBlank()) {
            return Optional.empty();
        }
        // 单次读取 volatile 快照引用，再从同一快照取两套索引——保证规范命中与 legacy 回退看到一致视图。
        Snapshot current = snapshot;
        ScheduledSourceProvider provider = current.typeIndex().get(typeOrLegacy);
        if (provider == null) {
            provider = current.legacyIndex().get(typeOrLegacy);
        }
        return Optional.ofNullable(provider);
    }

    /**
     * 从给定注册集合构建不可变快照：规范 type 与 legacy 名共用一套占用表 {@code claimedBy} 做全局
     * 唯一校验——任一字符串只能被一个 provider 以一种身份占用，既禁同类重复（type↔type、
     * legacy↔legacy）也禁跨类碰撞（type↔legacy）。全部通过后才物化两套索引并与来源列表一体返回；
     * 任一冲突中途抛出，调用方既有快照不受影响。
     */
    private static Snapshot buildSnapshot(List<RegisteredSource> registrations) {
        Map<String, ScheduledSourceProvider> byType = new LinkedHashMap<>();
        Map<String, ScheduledSourceProvider> byLegacyType = new LinkedHashMap<>();
        Map<String, RegisteredSource> claimedBy = new LinkedHashMap<>();
        for (RegisteredSource registered : registrations) {
            ScheduledSourceProvider provider = registered.provider();
            String canonical = provider.type();
            RegisteredSource canonicalClash = claimedBy.putIfAbsent(canonical, registered);
            if (canonicalClash != null) {
                throw new IllegalStateException("duplicate scheduled source type: " + canonical
                        + " (plugin: " + registered.pluginId()
                        + "; already claimed by plugin: " + canonicalClash.pluginId() + ")");
            }
            byType.put(canonical, provider);
            for (String name : provider.legacyTypeNames()) {
                RegisteredSource legacyClash = claimedBy.putIfAbsent(name, registered);
                if (legacyClash != null) {
                    throw new IllegalStateException("duplicate scheduled source legacy type: " + name
                            + " (plugin: " + registered.pluginId()
                            + "; already claimed by plugin: " + legacyClash.pluginId() + ")");
                }
                byLegacyType.put(name, provider);
            }
        }
        return new Snapshot(List.copyOf(registrations), Map.copyOf(byType), Map.copyOf(byLegacyType));
    }

    private static void validate(ScheduledSourceProvider provider, String pluginId) {
        if (provider == null) {
            throw new IllegalStateException("null scheduled source provider (plugin: " + pluginId + ")");
        }
        if (provider.type() == null || provider.type().isBlank()) {
            throw new IllegalStateException("scheduled source without type (plugin: " + pluginId + ")");
        }
        if (provider.legacyTypeNames() == null) {
            throw new IllegalStateException("scheduled source with null legacy types: "
                    + provider.type() + " (plugin: " + pluginId + ")");
        }
    }
}
