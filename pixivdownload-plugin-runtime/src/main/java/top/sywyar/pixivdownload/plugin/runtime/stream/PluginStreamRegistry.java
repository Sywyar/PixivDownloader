package top.sywyar.pixivdownload.plugin.runtime.stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.sywyar.pixivdownload.plugin.api.stream.PluginStream;
import top.sywyar.pixivdownload.plugin.api.stream.PluginStreamRegistrar;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 插件长连接服务端推流的宿主注册中心。注册中心按可信 plugin id 跟踪当前活动的可关闭推流，供宿主在插件
 * quiesce / 卸载时统一主动关闭；插件子 context 只获得由 {@link #registrarForPlugin(String)} 创建的 owner-scoped
 * {@link PluginStreamRegistrar}，不能自报或切换 owner。
 *
 * <p>注册中心只持有 {@link PluginStream} 关闭回调与字符串键。成功关闭后立即移除回调；失败项只为安全重试而保留，
 * 宿主在它们清零前不得关闭对应 child context。stream token 必须标识单个物理连接，不能使用会被并发连接复用的逻辑键。
 *
 * <p>每个 plugin id 有独立宿主锁与 admission 状态。{@link #closeForPlugin(String)} 在线性化点先禁止后续注册，再逐个
 * 关闭；竞态注册要么先进入活动集合并被本次 close 看见，要么观察到 admission 已关闭并立即关闭新流。关闭失败的迟到流
 * 同样保留供重试。{@link #resume(String)} 只在没有失败残留时重新开放 admission，避免新 serving 与旧连接混代。
 */
public class PluginStreamRegistry {

    private static final Logger log = LoggerFactory.getLogger(PluginStreamRegistry.class);

    /** pluginId → 宿主 admission / callback 状态；状态只含宿主字符串与传输关闭回调。 */
    private final Map<String, StreamState> byPlugin = new ConcurrentHashMap<>();
    private final Runnable closeClaimProbe;

    public PluginStreamRegistry() {
        this(() -> {
        });
    }

    PluginStreamRegistry(Runnable closeClaimProbe) {
        this.closeClaimProbe = Objects.requireNonNull(closeClaimProbe, "stream close claim probe");
    }

    /**
     * 为可信插件 owner 创建专属登记入口。返回对象只保存本注册中心和固定 plugin id，不保存插件实例、classloader
     * 或 child context；同一 owner 可创建多个等价入口。
     *
     * @param pluginId 宿主已验证的插件 id
     * @return 固定绑定该 owner 的推流登记入口
     */
    public PluginStreamRegistrar registrarForPlugin(String pluginId) {
        return new ScopedPluginStreamRegistrar(this, requirePluginId(pluginId));
    }

    private static final class ScopedPluginStreamRegistrar implements PluginStreamRegistrar {
        private final PluginStreamRegistry registry;
        private final String pluginId;

        private ScopedPluginStreamRegistrar(PluginStreamRegistry registry, String pluginId) {
            this.registry = registry;
            this.pluginId = pluginId;
        }

        @Override
        public void register(String streamToken, PluginStream stream) {
            registry.register(pluginId, streamToken, stream);
        }

        @Override
        public void unregister(String streamToken) {
            registry.unregister(pluginId, streamToken);
        }

        @Override
        public boolean acceptsNewStreams() {
            return registry.acceptsNewStreams(pluginId);
        }
    }

    private static final class StreamState {
        final Map<String, PluginStream> streams = new LinkedHashMap<>();
        final List<Throwable> concurrentCloseFailures = new ArrayList<>();
        boolean accepting = true;
        boolean closeInProgress;
        int immediateClosesInProgress;
        int concurrentCloseSuccesses;
    }

    private void register(String pluginId, String streamToken, PluginStream stream) {
        if (isBlank(pluginId) || isBlank(streamToken) || stream == null) {
            return;
        }
        StreamState state = byPlugin.computeIfAbsent(pluginId, ignored -> new StreamState());
        synchronized (state) {
            PluginStream existing = state.streams.get(streamToken);
            if (existing != null && existing != stream) {
                throw new IllegalStateException("duplicate plugin stream token: "
                        + pluginId + "/" + streamToken);
            }
            if (state.accepting) {
                if (existing == null) {
                    state.streams.put(streamToken, stream);
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
                state.streams.remove(streamToken, stream);
                if (state.closeInProgress) {
                    state.concurrentCloseSuccesses++;
                }
            }
        } catch (Throwable failure) {
            synchronized (state) {
                state.streams.putIfAbsent(streamToken, stream);
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

    private void unregister(String pluginId, String streamToken) {
        if (isBlank(pluginId) || isBlank(streamToken)) {
            return;
        }
        StreamState state = byPlugin.get(pluginId);
        if (state != null) {
            synchronized (state) {
                state.streams.remove(streamToken);
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

    /**
     * 某插件当前活动推流数（只读观测）。
     */
    public int activeStreamCount(String pluginId) {
        if (isBlank(pluginId)) {
            return 0;
        }
        StreamState state = byPlugin.get(pluginId);
        if (state == null) {
            return 0;
        }
        synchronized (state) {
            return state.streams.size();
        }
    }

    /**
     * 指定插件当前是否允许注册新推流。
     */
    public boolean acceptsNewStreams(String pluginId) {
        if (isBlank(pluginId)) {
            return true;
        }
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

    private static String requirePluginId(String pluginId) {
        Objects.requireNonNull(pluginId, "pluginId");
        if (pluginId.isBlank()) {
            throw new IllegalArgumentException("pluginId must not be blank");
        }
        return pluginId;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
