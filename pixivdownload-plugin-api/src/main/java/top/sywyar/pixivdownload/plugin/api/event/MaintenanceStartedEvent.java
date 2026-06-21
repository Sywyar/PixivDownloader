package top.sywyar.pixivdownload.plugin.api.event;

/**
 * 维护窗口开启。事件骨架，字段随发布链路接入按需扩充。
 *
 * @param trigger 触发来源（如 scheduled / manual）
 */
public record MaintenanceStartedEvent(String trigger) {
}
