package top.sywyar.pixivdownload.core.download.queue;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import top.sywyar.pixivdownload.plugin.registry.QueueTypeRegistry;

/**
 * 跨类型下载队列宿主操作注册中心（核心 owned）。收集各方贡献的 {@link QueueOperations} Bean
 * （插画操作由下载工作台贡献、小说操作由小说插件贡献），按 {@link QueueOperations#queueType() queueType} 索引。
 * 读路径走单个不可变快照（{@code byType} 映射），注册 / 注销在锁内构建新快照后一次性原子替换引用，读侧无锁且
 * 永不观察到半更新；注册失败时既有快照原样保留。
 *
 * <p>与其它 owner-scoped 能力注册中心一致，{@code queueType} 全局唯一
 * （与 {@code QueueTypeRegistry} 对类型 id 的全局唯一约束同口径），重复 {@code queueType} 在注册期 fail-fast。某作品类型
 * 操作缺席（贡献它的插件被禁 / 卸载、或本就未提供该 Bean）时，{@link #resolve} 返回空、{@link #all()} 不含它——
 * 下载队列控制器据此把跨类型操作只作用于在场的作品类型，绝不因缺操作而启动失败
 * （{@code List<QueueOperations>} 注入在无任何操作 Bean 时为空列表，不阻断启动）。
 *
 * <p>{@link #register} / {@link #unregister} 提供可逆注册语义（注册 → 注销 → 再注册后快照一致）；
 * 当前生产路径经构造器注入的操作 Bean 列表一次性建表。
 */
@Component
public class QueueOperationRegistry {

    /**
     * 注册时捕获的 queueType 与对应操作实例。生命周期只把此快照用作一次性准备 / 取消调用，绝不长期持有；
     * queueType 来自宿主已提交快照的键，不在 teardown 时重新调用插件 Bean getter。
     */
    public record OwnedQueueOperations(String queueType, QueueOperations operations) {
        public OwnedQueueOperations {
            if (queueType == null || queueType.isBlank()) {
                throw new IllegalStateException("owned queue operations without queueType");
            }
            Objects.requireNonNull(operations, "owned queue operations");
        }
    }

    /**
     * 单个不可变快照：{@code queueType → 操作适配器}（保持注册顺序，{@code all()} 据此遍历）。读侧只读
     * {@code snapshot} 引用一次即拿到一致视图。{@code byType} 由构造期一次性建立的不可变保序映射承载。
     */
    private record OwnedOperations(String ownerPluginId, QueueOperations operations) {
    }

    /** 插件 getter 已在 registry lock 外恰好读取一次的待提交值。 */
    private record CapturedOperations(String queueType, QueueOperations operations) {
    }

    private record Snapshot(Map<String, OwnedOperations> byType) {

        static final Snapshot EMPTY = new Snapshot(Map.of());
    }

    private final Object lock = new Object();

    private volatile Snapshot snapshot = Snapshot.EMPTY;

    /**
     * 由 Spring 注入全部 {@link QueueOperations} Bean 建表。无任何操作 Bean 时注入空列表
     * （集合注入不要求至少一个候选），快照为空、不阻断启动。
     */
    public QueueOperationRegistry(List<QueueOperations> operations) {
        register(operations);
    }

    /**
     * 注册一批操作适配器。{@code queueType} 与已注册项冲突、适配器非法都立即抛出；失败时既有快照保持不变。
     */
    public void register(List<QueueOperations> operations) {
        registerOwned(null, capture(operations));
    }

    /**
     * 原子替换某外置插件从子 context 贡献的全部队列操作。owner 只用于生命周期精准注销，不参与 queueType 路由。
     * 任一新操作与其它 owner 冲突时，既有快照保持不变。
     */
    public void register(String ownerPluginId, List<QueueOperations> operations) {
        if (ownerPluginId == null || ownerPluginId.isBlank()) {
            throw new IllegalStateException("queue operations without owner plugin id");
        }
        registerOwned(ownerPluginId, capture(operations));
    }

    private void registerOwned(String ownerPluginId, List<CapturedOperations> operations) {
        synchronized (lock) {
            Map<String, OwnedOperations> next = new LinkedHashMap<>(snapshot.byType());
            if (ownerPluginId != null) {
                next.entrySet().removeIf(entry -> ownerPluginId.equals(entry.getValue().ownerPluginId()));
            }
            if (operations == null || operations.isEmpty()) {
                snapshot = new Snapshot(Collections.unmodifiableMap(next));
                return;
            }
            for (CapturedOperations captured : operations) {
                QueueOperations ops = captured.operations();
                OwnedOperations candidate = new OwnedOperations(ownerPluginId, ops);
                OwnedOperations clash = next.putIfAbsent(captured.queueType(), candidate);
                if (clash != null) {
                    throw new IllegalStateException("duplicate queue operations type: " + captured.queueType()
                            + " (" + ops.getClass().getName()
                            + "; already registered: " + clash.operations().getClass().getName() + ")");
                }
            }
            // next 是构造期一次性建立、此后只读的保序映射；包成 unmodifiable 后整体替换引用（保留注册顺序）。
            snapshot = new Snapshot(Collections.unmodifiableMap(next));
        }
    }

    /** 注销某 {@code queueType} 的操作适配器；未注册过则静默返回（不改快照）。 */
    public void unregister(String queueType) {
        if (queueType == null || queueType.isBlank()) {
            return;
        }
        synchronized (lock) {
            if (!snapshot.byType().containsKey(queueType)) {
                return;
            }
            Map<String, OwnedOperations> next = new LinkedHashMap<>(snapshot.byType());
            next.remove(queueType);
            snapshot = new Snapshot(Collections.unmodifiableMap(next));
        }
    }

    /** 精准注销某插件贡献的全部队列操作；不会移除其它插件或父 context 的同类能力。 */
    public void unregisterOwner(String ownerPluginId) {
        if (ownerPluginId == null || ownerPluginId.isBlank()) {
            return;
        }
        synchronized (lock) {
            Map<String, OwnedOperations> next = new LinkedHashMap<>(snapshot.byType());
            boolean changed = next.entrySet().removeIf(entry -> ownerPluginId.equals(entry.getValue().ownerPluginId()));
            if (changed) {
                snapshot = new Snapshot(Collections.unmodifiableMap(next));
            }
        }
    }

    /** 按作品类型路由键解析操作适配器；空 / 无匹配返回 {@link Optional#empty()}（即该类型当前不可用）。 */
    public Optional<QueueOperations> resolve(String queueType) {
        if (queueType == null || queueType.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(snapshot.byType().get(queueType)).map(OwnedOperations::operations);
    }

    /** 按注册顺序返回全部操作适配器的不可变快照，供跨类型批量操作（如清空全部队列）遍历。 */
    public List<QueueOperations> all() {
        return snapshot.byType().values().stream().map(OwnedOperations::operations).toList();
    }

    /**
     * 返回当前精确归属于某外置插件 identity 的操作快照。生命周期据此清退本代队列，禁止在 teardown 时重读
     * {@code PixivFeaturePlugin.queueTypes()}（getter 可变或已不可安全调用）。父 context 无 owner 的操作不在结果中。
     */
    public List<OwnedQueueOperations> operationsForOwner(String ownerPluginId) {
        if (ownerPluginId == null || ownerPluginId.isBlank()) {
            return List.of();
        }
        Snapshot current = snapshot;
        return current.byType().entrySet().stream()
                .filter(entry -> ownerPluginId.equals(entry.getValue().ownerPluginId()))
                .map(entry -> new OwnedQueueOperations(entry.getKey(), entry.getValue().operations()))
                .toList();
    }

    private static List<CapturedOperations> capture(List<QueueOperations> operations) {
        if (operations == null || operations.isEmpty()) {
            return List.of();
        }
        Map<String, CapturedOperations> captured = new LinkedHashMap<>();
        for (QueueOperations ops : operations) {
            if (ops == null) {
                throw new IllegalStateException("null queue operations");
            }
            String queueType = ops.queueType();
            if (queueType == null || queueType.isBlank()) {
                throw new IllegalStateException("queue operations without queueType: "
                        + ops.getClass().getName());
            }
            CapturedOperations candidate = new CapturedOperations(queueType, ops);
            CapturedOperations clash = captured.putIfAbsent(queueType, candidate);
            if (clash != null) {
                throw new IllegalStateException("duplicate queue operations type: " + queueType
                        + " (" + ops.getClass().getName()
                        + "; already registered: " + clash.operations().getClass().getName() + ")");
            }
        }
        return List.copyOf(captured.values());
    }
}
