package top.sywyar.pixivdownload.core.download.queue;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 跨类型下载队列宿主操作注册中心（核心 owned）。收集各方贡献的 {@link QueueOperations} Bean
 * （插画操作由下载工作台贡献、小说操作由小说插件贡献），按 {@link QueueOperations#queueType() queueType} 索引。
 * 读路径走单个不可变快照（{@code byType} 映射），注册 / 注销在锁内构建新快照后一次性原子替换引用，读侧无锁且
 * 永不观察到半更新；注册失败时既有快照原样保留。
 *
 * <p>镜像 {@link top.sywyar.pixivdownload.core.schedule.work.ScheduledWorkRunnerRegistry}：{@code queueType} 全局唯一
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
     * 单个不可变快照：{@code queueType → 操作适配器}（保持注册顺序，{@code all()} 据此遍历）。读侧只读
     * {@code snapshot} 引用一次即拿到一致视图。{@code byType} 由构造期一次性建立的不可变保序映射承载。
     */
    private record Snapshot(Map<String, QueueOperations> byType) {

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
        if (operations == null || operations.isEmpty()) {
            return;
        }
        synchronized (lock) {
            Map<String, QueueOperations> next = new LinkedHashMap<>(snapshot.byType());
            for (QueueOperations ops : operations) {
                validate(ops);
                QueueOperations clash = next.putIfAbsent(ops.queueType(), ops);
                if (clash != null) {
                    throw new IllegalStateException("duplicate queue operations type: " + ops.queueType()
                            + " (" + ops.getClass().getName()
                            + "; already registered: " + clash.getClass().getName() + ")");
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
            Map<String, QueueOperations> next = new LinkedHashMap<>(snapshot.byType());
            next.remove(queueType);
            snapshot = new Snapshot(Collections.unmodifiableMap(next));
        }
    }

    /** 按作品类型路由键解析操作适配器；空 / 无匹配返回 {@link Optional#empty()}（即该类型当前不可用）。 */
    public Optional<QueueOperations> resolve(String queueType) {
        if (queueType == null || queueType.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(snapshot.byType().get(queueType));
    }

    /** 按注册顺序返回全部操作适配器的不可变快照，供跨类型批量操作（如清空全部队列）遍历。 */
    public List<QueueOperations> all() {
        return List.copyOf(snapshot.byType().values());
    }

    private static void validate(QueueOperations ops) {
        if (ops == null) {
            throw new IllegalStateException("null queue operations");
        }
        if (ops.queueType() == null || ops.queueType().isBlank()) {
            throw new IllegalStateException("queue operations without queueType: " + ops.getClass().getName());
        }
    }
}
