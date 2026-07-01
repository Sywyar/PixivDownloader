package top.sywyar.pixivdownload.core.schedule.work;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import top.sywyar.pixivdownload.plugin.registry.ScheduledSourceRegistry;

/**
 * 计划任务「作品类型执行器」注册中心（核心 owned）。收集各方贡献的 {@link ScheduledWorkRunner} Bean
 * （插画执行器由下载工作台贡献、小说执行器由小说插件贡献），按 {@link ScheduledWorkRunner#kind() kind} 索引。
 * 读路径走单个不可变快照（{@code byKind} 映射），注册 / 注销在锁内构建新快照后一次性原子替换引用，读侧无锁且
 * 永不观察到半更新；注册失败时既有快照原样保留。
 *
 * <p>{@code kind} 全局唯一：重复 {@code kind} 在注册期 fail-fast（启动即失败而非带病运行）。某作品类型执行器
 * 缺席（贡献它的插件被禁 / 卸载、或本就未提供该 Bean）时，{@link #resolve} 返回空——调度壳据此把该类型任务
 * 标记为不可用并干净挂起，绝不继续下载、也不因缺执行器而启动失败（{@code List<ScheduledWorkRunner>} 注入在
 * 无任何执行器 Bean 时为空列表，不阻断启动）。
 *
 * <p>{@link #register} / {@link #unregister} 提供可逆注册语义（镜像
 * {@link top.sywyar.pixivdownload.plugin.registry.ScheduledSourceRegistry} 风格），为后续插件热插拔预留；当前生产路径
 * 经构造器注入的执行器 Bean 列表一次性建表。
 */
@Component
public class ScheduledWorkRunnerRegistry {

    /** 单个不可变快照：{@code kind → 执行器}。读侧只读 {@code snapshot} 引用一次即拿到一致视图。 */
    private record Snapshot(Map<String, ScheduledWorkRunner> byKind) {

        static final Snapshot EMPTY = new Snapshot(Map.of());
    }

    private final Object lock = new Object();

    private volatile Snapshot snapshot = Snapshot.EMPTY;

    /**
     * 由 Spring 注入全部 {@link ScheduledWorkRunner} Bean 建表。无任何执行器 Bean 时注入空列表
     * （集合注入不要求至少一个候选），快照为空、不阻断启动。
     */
    public ScheduledWorkRunnerRegistry(List<ScheduledWorkRunner> runners) {
        register(runners);
    }

    /**
     * 注册一批执行器。{@code kind} 与已注册项冲突、执行器非法都立即抛出；失败时既有快照保持不变。
     */
    public void register(List<ScheduledWorkRunner> runners) {
        if (runners == null || runners.isEmpty()) {
            return;
        }
        synchronized (lock) {
            Map<String, ScheduledWorkRunner> next = new LinkedHashMap<>(snapshot.byKind());
            for (ScheduledWorkRunner runner : runners) {
                validate(runner);
                ScheduledWorkRunner clash = next.putIfAbsent(runner.kind(), runner);
                if (clash != null) {
                    throw new IllegalStateException("duplicate scheduled work runner kind: " + runner.kind()
                            + " (" + runner.getClass().getName()
                            + "; already registered: " + clash.getClass().getName() + ")");
                }
            }
            snapshot = new Snapshot(Map.copyOf(next));
        }
    }

    /** 注销某 {@code kind} 的执行器；未注册过则静默返回（不改快照）。 */
    public void unregister(String kind) {
        if (kind == null || kind.isBlank()) {
            return;
        }
        synchronized (lock) {
            if (!snapshot.byKind().containsKey(kind)) {
                return;
            }
            Map<String, ScheduledWorkRunner> next = new LinkedHashMap<>(snapshot.byKind());
            next.remove(kind);
            snapshot = new Snapshot(Map.copyOf(next));
        }
    }

    /** 按作品类型路由键解析执行器；空 / 无匹配返回 {@link Optional#empty()}（即该类型当前不可执行）。 */
    public Optional<ScheduledWorkRunner> resolve(String kind) {
        if (kind == null || kind.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(snapshot.byKind().get(kind));
    }

    /** 按注册顺序返回全部执行器的不可变快照。 */
    public List<ScheduledWorkRunner> runners() {
        return List.copyOf(snapshot.byKind().values());
    }

    private static void validate(ScheduledWorkRunner runner) {
        if (runner == null) {
            throw new IllegalStateException("null scheduled work runner");
        }
        if (runner.kind() == null || runner.kind().isBlank()) {
            throw new IllegalStateException("scheduled work runner without kind: " + runner.getClass().getName());
        }
    }
}
