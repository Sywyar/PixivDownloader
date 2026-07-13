package top.sywyar.pixivdownload.plugin.lifecycle;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 插件长连接服务端推流（SSE 等）宿主注册中心（核心 owned）。按 pluginId 跟踪当前活动的可关闭推流，供
 * {@link PluginLifecycleService} 在插件 quiesce / 卸载时统一主动关闭并通知客户端「插件不可用」——与队列宿主
 * 注册中心 {@code QueueOperationRegistry}（在途下载任务）并列，构成「按 pluginId / queueType 关联在途运行期资源」
 * 的宿主侧入口。
 *
 * <p>注册中心持有 {@link PluginStream} 关闭回调 + 字符串键（pluginId / stream token）。回调可能捕获 child controller，
 * 因此 {@link #closeForPlugin} 成功关闭后必须立即移除；失败项只为安全重试而暂时保留，生命周期在它们清零前不得关闭
 * child context。stream token 必须标识单个物理连接，不能用作品 id 等会被并发连接复用的逻辑键。
 *
 * <p>每个 pluginId 有独立宿主锁与 admission 状态。{@link #closeForPlugin} 在线性化点先禁止后续注册，再逐个关闭；
 * 与它竞态的 {@link #register} 要么先进入活动集合并被本次 close 看见，要么观察到 admission 已关闭并立即关闭新流。
 * 关闭失败的迟到流同样保留供重试。{@link #resume} 只在没有失败残留时重新开放 admission，避免新 serving 与旧连接混代。
 */
@Slf4j
@Component
public class PluginStreamRegistry {

    /** pluginId → 宿主 admission / callback 状态；状态只含核心字符串与传输回调。 */
    private final Map<String, StreamState> byPlugin = new ConcurrentHashMap<>();
    private final Runnable closeClaimProbe;

    public PluginStreamRegistry() {
        this(() -> {
        });
    }

    PluginStreamRegistry(Runnable closeClaimProbe) {
        this.closeClaimProbe = java.util.Objects.requireNonNull(closeClaimProbe, "stream close claim probe");
    }

    private static final class StreamState {
        final Map<String, PluginStream> streams = new LinkedHashMap<>();
        final List<Throwable> concurrentCloseFailures = new ArrayList<>();
        boolean accepting = true;
        boolean closeInProgress;
        int immediateClosesInProgress;
        int concurrentCloseSuccesses;
    }

    /**
     * 注册一条隶属 {@code pluginId} 的可关闭推流。{@code streamId} 在该插件内唯一标识此流（如 SSE 订阅键 /
     * 连接 id），供 {@link #unregister} 在正常完成时摘除。任一入参空白 / 回调为空时静默忽略。
     */
    public void register(String pluginId, String streamId, PluginStream stream) {
        if (isBlank(pluginId) || isBlank(streamId) || stream == null) {
            return;
        }
        StreamState state = byPlugin.computeIfAbsent(pluginId, ignored -> new StreamState());
        synchronized (state) {
            PluginStream existing = state.streams.get(streamId);
            if (existing != null && existing != stream) {
                throw new IllegalStateException("duplicate plugin stream token: "
                        + pluginId + "/" + streamId);
            }
            if (state.accepting) {
                if (existing == null) {
                    state.streams.put(streamId, stream);
                }
                return;
            }
            if (existing == stream && state.closeInProgress) {
                return;
            }
            state.immediateClosesInProgress++;
        }
        try {
            stream.closeUnavailable();
            synchronized (state) {
                state.streams.remove(streamId, stream);
                if (state.closeInProgress) {
                    state.concurrentCloseSuccesses++;
                }
            }
        } catch (Throwable failure) {
            synchronized (state) {
                state.streams.putIfAbsent(streamId, stream);
                if (state.closeInProgress) {
                    state.concurrentCloseFailures.add(failure);
                }
            }
            rethrow(failure);
        } finally {
            synchronized (state) {
                state.immediateClosesInProgress--;
                state.notifyAll();
            }
        }
    }

    /** 摘除某插件下的指定流（客户端正常完成 / 断开 / 显式关闭时调用）；不触发关闭回调。未注册过静默返回。 */
    public void unregister(String pluginId, String streamId) {
        if (isBlank(pluginId) || isBlank(streamId)) {
            return;
        }
        StreamState state = byPlugin.get(pluginId);
        if (state != null) {
            synchronized (state) {
                state.streams.remove(streamId);
            }
        }
    }

    /**
     * 重新开放指定插件的推流 admission。若上次关闭仍有失败 callback，拒绝开放，调用方必须保持插件不可服务并重试清理。
     */
    public void resume(String pluginId) {
        if (isBlank(pluginId)) {
            return;
        }
        StreamState state = byPlugin.computeIfAbsent(pluginId, ignored -> new StreamState());
        synchronized (state) {
            if (state.closeInProgress || state.immediateClosesInProgress != 0 || !state.streams.isEmpty()) {
                throw new IllegalStateException("cannot resume plugin streams with pending callbacks: "
                        + pluginId + " (active=" + state.streams.size() + ")");
            }
            state.accepting = true;
        }
    }

    /**
     * 关闭并清退某插件的全部活动推流：先原子禁止新注册，再逐个调用 {@link PluginStream#closeUnavailable()}。
     * 所有 callback 都会尝试；成功项立即移除，失败项保留。首个失败在完整轮询后原对象重抛，后续失败附为 suppressed。
     */
    public int closeForPlugin(String pluginId) {
        if (isBlank(pluginId)) {
            return 0;
        }
        StreamState state = byPlugin.computeIfAbsent(pluginId, ignored -> new StreamState());
        List<Map.Entry<String, PluginStream>> snapshot = List.of();
        boolean interrupted = false;
        boolean closeClaimed = false;
        int closed = 0;
        Throwable failure = null;
        try {
            synchronized (state) {
                state.accepting = false;
                while (state.closeInProgress) {
                    try {
                        state.wait();
                    } catch (InterruptedException waitFailure) {
                        interrupted = true;
                    }
                }
                // Allocate the work snapshot before publishing closeInProgress; allocation failure remains retryable.
                snapshot = new ArrayList<>(state.streams.entrySet());
                state.concurrentCloseFailures.clear();
                state.concurrentCloseSuccesses = 0;
                closeClaimed = true;
                state.closeInProgress = true;
            }
            closeClaimProbe.run();
            for (Map.Entry<String, PluginStream> entry : snapshot) {
                try {
                    entry.getValue().closeUnavailable();
                    synchronized (state) {
                        state.streams.remove(entry.getKey(), entry.getValue());
                    }
                    closed++;
                } catch (Throwable closeFailure) {
                    synchronized (state) {
                        // callback 可重入 unregister；失败时仍恢复同一精确 token，供下一次 close 重试。
                        state.streams.putIfAbsent(entry.getKey(), entry.getValue());
                    }
                    log.warn("Error closing a plugin stream for '{}' (failureType={})",
                            pluginId, closeFailure.getClass().getName());
                    failure = mergeFailure(failure, closeFailure);
                }
            }
        } finally {
            if (closeClaimed) {
                synchronized (state) {
                    while (state.immediateClosesInProgress != 0) {
                        try {
                            state.wait();
                        } catch (InterruptedException waitFailure) {
                            interrupted = true;
                        }
                    }
                    state.closeInProgress = false;
                    state.notifyAll();
                    closed += state.concurrentCloseSuccesses;
                    for (int index = 0; index < state.concurrentCloseFailures.size(); index++) {
                        failure = mergeFailure(failure, state.concurrentCloseFailures.get(index));
                    }
                    state.concurrentCloseFailures.clear();
                    state.concurrentCloseSuccesses = 0;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        rethrow(failure);
        return closed;
    }

    /** 某插件当前活动推流数（只读观测）。 */
    public int activeStreamCount(String pluginId) {
        StreamState state = byPlugin.get(pluginId);
        if (state == null) {
            return 0;
        }
        synchronized (state) {
            return state.streams.size();
        }
    }

    /** 指定插件当前是否允许注册新推流。 */
    public boolean acceptsNewStreams(String pluginId) {
        StreamState state = byPlugin.get(pluginId);
        if (state == null) {
            return true;
        }
        synchronized (state) {
            return state.accepting;
        }
    }

    private static Throwable mergeFailure(Throwable current, Throwable failure) {
        if (current == null) {
            return failure;
        }
        if (!isFatal(current) && isFatal(failure)) {
            addSuppressedSafely(failure, current);
            return failure;
        }
        if (current != failure) {
            addSuppressedSafely(current, failure);
        }
        return current;
    }

    private static boolean isFatal(Throwable failure) {
        return failure instanceof VirtualMachineError || failure instanceof ThreadDeath;
    }

    private static void addSuppressedSafely(Throwable target, Throwable failure) {
        if (target == failure) {
            return;
        }
        try {
            target.addSuppressed(failure);
        } catch (Throwable ignored) {
            // 诊断附加失败不得覆盖主失败对象。
        }
    }

    private static void rethrow(Throwable failure) {
        if (failure == null) {
            return;
        }
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("plugin stream close failed", failure);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
