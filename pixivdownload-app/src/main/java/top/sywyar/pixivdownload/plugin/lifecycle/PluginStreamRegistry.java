package top.sywyar.pixivdownload.plugin.lifecycle;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 插件长连接服务端推流（SSE 等）宿主注册中心（核心 owned）。按 pluginId 跟踪当前活动的可关闭推流，供
 * {@link PluginLifecycleService} 在插件 quiesce / 卸载时统一主动关闭并通知客户端「插件不可用」——与队列宿主
 * 注册中心 {@code QueueOperationRegistry}（在途下载任务）并列，构成「按 pluginId / queueType 关联在途运行期资源」
 * 的宿主侧入口。
 *
 * <p><b>不持插件强引用</b>：只持有 {@link PluginStream} 关闭回调 + 字符串键（pluginId / streamId）；回调本身
 * 只捕获传输句柄（{@code SseEmitter}）与核心值（见 {@link PluginStream} 约束），不含外置插件 Bean / classloader /
 * 子 context。{@link #closeForPlugin} 关闭后即移除该插件全部回调——连接不残留为插件 classloader 的泄漏点。
 *
 * <p>读 / 写无锁：外层 {@link ConcurrentHashMap}（pluginId → 内层 map）+ 内层 {@link ConcurrentHashMap}
 * （streamId → 回调）。{@link #closeForPlugin} 以 {@code remove(pluginId)} 原子摘下整份内层 map 再逐个关闭，
 * 故关闭过程中由回调反向触发的 {@link #unregister}（典型：emitter complete 回调反过来注销自身）只作用于已摘下的
 * map、为安全 no-op，不重复关闭、不并发改动。无任何活动推流时完全透明、零开销。
 */
@Slf4j
@Component
public class PluginStreamRegistry {

    /** pluginId → (streamId → 关闭回调)。 */
    private final Map<String, Map<String, PluginStream>> byPlugin = new ConcurrentHashMap<>();

    /**
     * 注册一条隶属 {@code pluginId} 的可关闭推流。{@code streamId} 在该插件内唯一标识此流（如 SSE 订阅键 /
     * 连接 id），供 {@link #unregister} 在正常完成时摘除。任一入参空白 / 回调为空时静默忽略。
     */
    public void register(String pluginId, String streamId, PluginStream stream) {
        if (isBlank(pluginId) || isBlank(streamId) || stream == null) {
            return;
        }
        byPlugin.computeIfAbsent(pluginId, k -> new ConcurrentHashMap<>()).put(streamId, stream);
    }

    /** 摘除某插件下的指定流（客户端正常完成 / 断开 / 显式关闭时调用）；不触发关闭回调。未注册过静默返回。 */
    public void unregister(String pluginId, String streamId) {
        if (isBlank(pluginId) || isBlank(streamId)) {
            return;
        }
        Map<String, PluginStream> streams = byPlugin.get(pluginId);
        if (streams != null) {
            streams.remove(streamId);
        }
    }

    /**
     * 关闭并清退某插件的全部活动推流：原子摘下其内层 map，逐个调用 {@link PluginStream#closeUnavailable()}
     * 通知客户端、隔离单个回调异常。返回实际关闭的流数；关闭后该插件不再残留任何回调引用。
     */
    public int closeForPlugin(String pluginId) {
        if (isBlank(pluginId)) {
            return 0;
        }
        Map<String, PluginStream> streams = byPlugin.remove(pluginId);
        if (streams == null || streams.isEmpty()) {
            return 0;
        }
        int closed = 0;
        for (PluginStream stream : streams.values()) {
            try {
                stream.closeUnavailable();
                closed++;
            } catch (RuntimeException e) {
                log.warn("Error closing a plugin stream for '{}': {}", pluginId, e.toString());
            }
        }
        return closed;
    }

    /** 某插件当前活动推流数（只读观测）。 */
    public int activeStreamCount(String pluginId) {
        Map<String, PluginStream> streams = byPlugin.get(pluginId);
        return streams == null ? 0 : streams.size();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
