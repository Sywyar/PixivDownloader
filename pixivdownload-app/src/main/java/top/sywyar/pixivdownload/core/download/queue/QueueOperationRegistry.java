package top.sywyar.pixivdownload.core.download.queue;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import top.sywyar.pixivdownload.plugin.api.download.queue.QueueOperations;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityInvocationException;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityUnavailableException;
import top.sywyar.pixivdownload.plugin.registry.QueueTypeRegistry;

/**
 * 跨类型队列宿主操作注册中心（核心 owned）。收集各方贡献的 {@link QueueOperations} Bean
 * （下载队列及既有后台扫描队列），按 {@link QueueOperations#queueType() queueType} 索引。
 * 读路径走单个不可变快照（{@code byType} 映射），注册 / 注销在锁内构建新快照后一次性原子替换引用，读侧无锁且
 * 永不观察到半更新；注册失败时既有快照原样保留。
 *
 * <p>与其它 owner-scoped 能力注册中心一致，{@code queueType} 全局唯一。下载队列的键通常与
 * {@link QueueTypeRegistry} 对类型 id 的全局唯一约束同口径，但本注册中心不要求每项都存在下载 descriptor；重复
 * {@code queueType} 在注册期 fail-fast。某队列操作缺席（贡献它的插件被禁 / 卸载、或本就未提供该 Bean）时，
 * {@link #resolve} 返回空、{@link #all()} 不含它——下载队列控制器据此只作用于在场的操作，绝不因缺操作而启动失败
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
    public record OwnedQueueOperations(
            String queueType,
            QueueOperationOwner owner,
            QueueOperations operations) {
        public OwnedQueueOperations {
            if (queueType == null || queueType.isBlank()) {
                throw new IllegalStateException("owned queue operations without queueType");
            }
            Objects.requireNonNull(operations, "owned queue operations");
        }

        public OwnedQueueOperations(String queueType, QueueOperations operations) {
            this(queueType, null, operations);
        }
    }

    /** HTTP 控制面可调用的窄命令与精确 capability owner；不会暴露 lifecycle 方法或 raw target。 */
    public record OwnedQueueCommands(
            String queueType,
            QueueOperationOwner owner,
            QueueOperationCommands commands) {
        public OwnedQueueCommands {
            if (queueType == null || queueType.isBlank()) {
                throw new IllegalStateException("owned queue commands without queueType");
            }
            Objects.requireNonNull(owner, "owned queue command owner");
            Objects.requireNonNull(commands, "owned queue commands");
        }
    }

    /** adapter 已在锁外捕获 queueType，并准备好 raw lifecycle target 与窄 invocation proxy。 */
    public record PreparedQueueOperations(
            String queueType,
            QueueOperations rawOperations,
            QueueOperationCommands commands,
            String implementationType) {
        public PreparedQueueOperations {
            if (queueType == null || queueType.isBlank()) {
                throw new IllegalStateException("prepared queue operations without queueType");
            }
            Objects.requireNonNull(rawOperations, "prepared raw queue operations");
            Objects.requireNonNull(commands, "prepared queue operation commands");
            implementationType = implementationType == null || implementationType.isBlank()
                    ? "unknown" : implementationType;
        }
    }

    /**
     * 单个不可变快照：{@code queueType → 操作适配器}（保持注册顺序，{@code all()} 据此遍历）。读侧只读
     * {@code snapshot} 引用一次即拿到一致视图。{@code byType} 由构造期一次性建立的不可变保序映射承载。
     */
    private record OwnedOperations(
            String legacyOwnerPluginId,
            QueueOperationOwner owner,
            QueueOperations rawOperations,
            QueueOperationCommands commands,
            String implementationType) {
    }

    /** 插件 getter 已在 registry lock 外恰好读取一次的待提交值。 */
    private record CapturedOperations(
            String queueType,
            QueueOperations rawOperations,
            QueueOperationCommands commands,
            String implementationType) {
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
        registerOwned(null, null, capture(operations));
    }

    /**
     * 原子替换某外置插件从子 context 贡献的全部队列操作。owner 只用于生命周期精准注销，不参与 queueType 路由。
     * 任一新操作与其它 owner 冲突时，既有快照保持不变。
     */
    public void register(String ownerPluginId, List<QueueOperations> operations) {
        if (ownerPluginId == null || ownerPluginId.isBlank()) {
            throw new IllegalStateException("queue operations without owner plugin id");
        }
        registerOwned(ownerPluginId, null, capture(operations));
    }

    /** 发布一批精确外置 capability operation；不调用 proxy 或插件 getter。 */
    public void registerPrepared(
            QueueOperationOwner owner,
            List<PreparedQueueOperations> operations) {
        Objects.requireNonNull(owner, "queue operation owner");
        registerOwned(null, owner, capturePrepared(operations));
    }

    private void registerOwned(
            String legacyOwnerPluginId,
            QueueOperationOwner owner,
            List<CapturedOperations> operations) {
        synchronized (lock) {
            Map<String, OwnedOperations> next = new LinkedHashMap<>(snapshot.byType());
            if (legacyOwnerPluginId != null) {
                next.entrySet().removeIf(entry -> legacyOwnerPluginId.equals(
                        entry.getValue().legacyOwnerPluginId()));
            }
            if (owner != null) {
                next.entrySet().removeIf(entry -> owner.equals(entry.getValue().owner()));
            }
            if (operations == null || operations.isEmpty()) {
                snapshot = new Snapshot(Collections.unmodifiableMap(next));
                return;
            }
            for (CapturedOperations captured : operations) {
                QueueOperations raw = captured.rawOperations();
                OwnedOperations candidate = new OwnedOperations(
                        legacyOwnerPluginId,
                        owner,
                        raw,
                        captured.commands(),
                        captured.implementationType());
                OwnedOperations clash = next.putIfAbsent(captured.queueType(), candidate);
                if (clash != null) {
                    throw new IllegalStateException("duplicate queue operations type: " + captured.queueType()
                            + " (" + captured.implementationType()
                            + "; already registered: " + clash.implementationType() + ")");
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
            boolean changed = next.entrySet().removeIf(entry -> ownerPluginId.equals(
                    entry.getValue().legacyOwnerPluginId()));
            if (changed) {
                snapshot = new Snapshot(Collections.unmodifiableMap(next));
            }
        }
    }

    /** 精确撤回一个 capability publication；过期 owner 不会删除同插件的新代。 */
    public void unregisterPrepared(QueueOperationOwner owner) {
        if (owner == null) {
            return;
        }
        synchronized (lock) {
            Map<String, OwnedOperations> next = new LinkedHashMap<>(snapshot.byType());
            boolean changed = next.entrySet().removeIf(entry -> owner.equals(entry.getValue().owner()));
            if (changed) {
                snapshot = new Snapshot(Collections.unmodifiableMap(next));
            }
        }
    }

    /** 按稳定队列路由键解析窄同步命令；不会暴露 raw lifecycle target。 */
    public Optional<QueueOperationCommands> resolve(String queueType) {
        if (queueType == null || queueType.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(snapshot.byType().get(queueType)).map(OwnedOperations::commands);
    }

    /**
     * 只解析与当前下载 descriptor 的 plugin/package/generation 完全一致的外置操作。
     * descriptor publicationId 在调用控制器中独立验证，不能与 capabilityPublicationId 跨域比较。
     */
    public Optional<OwnedQueueCommands> resolveOwned(
            String queueType,
            String pluginId,
            String packageId,
            long pluginGeneration) {
        if (queueType == null || queueType.isBlank()
                || pluginId == null || pluginId.isBlank()
                || packageId == null || packageId.isBlank()
                || pluginGeneration < 0L) {
            return Optional.empty();
        }
        OwnedOperations registered = snapshot.byType().get(queueType);
        if (registered == null || registered.owner() == null
                || !registered.owner().matches(pluginId, packageId, pluginGeneration)) {
            return Optional.empty();
        }
        return Optional.of(new OwnedQueueCommands(queueType, registered.owner(), registered.commands()));
    }

    /**
     * 只解析父 context 的内置操作。legacy external 与 generation-aware external 均不能冒充内置项；
     * 调用方还必须先把请求 owner 四元组与当前 generation=0 descriptor 精确比对。
     */
    public Optional<QueueOperationCommands> resolveHost(String queueType) {
        if (queueType == null || queueType.isBlank()) {
            return Optional.empty();
        }
        OwnedOperations registered = snapshot.byType().get(queueType);
        if (registered == null || registered.owner() != null
                || registered.legacyOwnerPluginId() != null) {
            return Optional.empty();
        }
        return Optional.of(registered.commands());
    }

    /**
     * 在调用前再次确认命令仍是该 queueType 的当前 publication；外置 proxy 已撤回或调用失败时统一投影为
     * host-owned unavailable，控制器据此返回稳定非 2xx 机器码。
     */
    public void cancel(
            String queueType,
            QueueOperationCommands expected,
            String workKey,
            String ownerUuid,
            boolean admin) {
        if (expected == null) {
            throw new QueueOperationUnavailableException("queue operation is unavailable");
        }
        OwnedOperations registered = snapshot.byType().get(queueType);
        if (registered == null || registered.commands() != expected) {
            throw new QueueOperationUnavailableException("queue operation publication changed");
        }
        try {
            expected.cancel(workKey, ownerUuid, admin);
        } catch (ExternalCapabilityUnavailableException | ExternalCapabilityInvocationException unavailable) {
            throw new QueueOperationUnavailableException("queue operation is unavailable");
        }
    }

    /** 按注册顺序返回全部窄命令的不可变快照，供 HTTP 清空入口遍历。 */
    public List<QueueOperationCommands> all() {
        return snapshot.byType().values().stream().map(OwnedOperations::commands).toList();
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
                .filter(entry -> ownerPluginId.equals(entry.getValue().legacyOwnerPluginId())
                        || entry.getValue().owner() != null
                        && ownerPluginId.equals(entry.getValue().owner().pluginId()))
                .map(entry -> new OwnedQueueOperations(
                        entry.getKey(), entry.getValue().owner(), entry.getValue().rawOperations()))
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
            CapturedOperations candidate = new CapturedOperations(
                    queueType, ops, directCommands(ops), ops.getClass().getName());
            CapturedOperations clash = captured.putIfAbsent(queueType, candidate);
            if (clash != null) {
                throw new IllegalStateException("duplicate queue operations type: " + queueType
                        + " (" + ops.getClass().getName()
                        + "; already registered: " + clash.implementationType() + ")");
            }
        }
        return List.copyOf(captured.values());
    }

    private static List<CapturedOperations> capturePrepared(List<PreparedQueueOperations> operations) {
        if (operations == null || operations.isEmpty()) {
            return List.of();
        }
        Map<String, CapturedOperations> captured = new LinkedHashMap<>();
        for (PreparedQueueOperations prepared : operations) {
            Objects.requireNonNull(prepared, "prepared queue operations");
            CapturedOperations candidate = new CapturedOperations(
                    prepared.queueType(),
                    prepared.rawOperations(),
                    prepared.commands(),
                    prepared.implementationType());
            CapturedOperations clash = captured.putIfAbsent(prepared.queueType(), candidate);
            if (clash != null) {
                throw new IllegalStateException("duplicate queue operations type: " + prepared.queueType()
                        + " (" + prepared.implementationType()
                        + "; already registered: " + clash.implementationType() + ")");
            }
        }
        return List.copyOf(captured.values());
    }

    private static QueueOperationCommands directCommands(QueueOperations operations) {
        return new QueueOperationCommands() {
            @Override
            public void cancel(String workKey, String ownerUuid, boolean admin) {
                operations.cancel(workKey, ownerUuid, admin);
            }

            @Override
            public int clearAll() {
                return operations.clearAll();
            }

            @Override
            public int clearForOwner(String ownerUuid) {
                return operations.clearForOwner(ownerUuid);
            }
        };
    }
}
