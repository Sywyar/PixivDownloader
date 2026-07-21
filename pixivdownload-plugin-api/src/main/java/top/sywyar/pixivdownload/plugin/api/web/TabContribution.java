package top.sywyar.pixivdownload.plugin.api.web;

import java.util.List;

/**
 * 插件声明的下载工作台「获取方式」标签页（acquisition 轴：怎么找作品）。
 * <p>
 * 与 {@link QueueTypeContribution}（work-type 轴：下载什么）正交：一个标签页声明它能产出哪些
 * 作品类型兼容上限（{@link #supportedQueueTypes}）。作品类型真正支持哪些取得模式由
 * {@link DownloadTypeDescriptor#acquisitionModes()} 声明；宿主先按标签页对应模式筛选，再与非空兼容上限取交集。
 * 空兼容上限表示完全由 descriptor 推导，避免标签页 owner 硬编码其它插件类型。某类型插件被禁用时，
 * 其选项在所有标签页自动消失。标签页 id 与计划来源描述符的
 * {@link top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceDescriptor#acquisitionModes()
 * acquisitionModes} 共享取得模式口径。
 *
 * @param pluginId            声明该标签页的插件 id
 * @param tabId               标签页 id（与页面 DOM 的模式标识一致，如 {@code user} / {@code search} / {@code series}）
 * @param order               标签页排序，越小越靠前
 * @param supportedQueueTypes 可选兼容上限；空列表表示由 {@link DownloadTypeDescriptor#acquisitionModes()} 完全推导
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
