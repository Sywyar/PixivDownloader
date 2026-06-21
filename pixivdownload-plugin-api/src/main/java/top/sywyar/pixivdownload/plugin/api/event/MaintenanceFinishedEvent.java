package top.sywyar.pixivdownload.plugin.api.event;

/**
 * 维护窗口关闭。事件骨架，字段随发布链路接入按需扩充。
 *
 * @param durationMillis 本次维护窗口总耗时
 */
public record MaintenanceFinishedEvent(long durationMillis) {
}
