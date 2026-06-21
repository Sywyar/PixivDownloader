package top.sywyar.pixivdownload.plugin.api.web;

import java.util.List;

/**
 * 插件声明的下载工作台「获取方式」标签页（acquisition 轴：怎么找作品）。
 * <p>
 * 与 {@link QueueTypeContribution}（work-type 轴：下载什么）正交：一个标签页声明它能产出哪些
 * 作品类型（{@link #supportedQueueTypes}），宿主把「该标签页支持的类型 ∩ 当前已启用类型」渲染为
 * 子模式（kind 单选）——某类型的插件被禁用时，其选项在所有标签页自动消失。标签页 id 与后端
 * {@code ScheduledSourceProvider} 的来源 type 共享口径（同一「来源」的交互面 vs 计划面）。
 *
 * @param pluginId            声明该标签页的插件 id
 * @param tabId               标签页 id（与页面 DOM 的模式标识一致，如 {@code user} / {@code search} / {@code series}）
 * @param order               标签页排序，越小越靠前
 * @param supportedQueueTypes 该标签页能产出的作品类型 id 列表（与 {@link QueueTypeContribution#type()} 对齐）
 */
public record TabContribution(
        String pluginId,
        String tabId,
        int order,
        List<String> supportedQueueTypes
) {
    public TabContribution {
        supportedQueueTypes = List.copyOf(supportedQueueTypes);
    }
}
