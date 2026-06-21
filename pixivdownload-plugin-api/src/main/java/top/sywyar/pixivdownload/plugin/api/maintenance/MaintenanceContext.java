package top.sywyar.pixivdownload.plugin.api.maintenance;

/**
 * 维护任务执行时的上下文。预留以便未来扩展（例如携带触发原因、运行 ID）。
 *
 * @param triggeredBy 触发来源，"schedule" / "manual" 等
 * @param startedAt   维护窗口的起始毫秒时间戳
 */
public record MaintenanceContext(String triggeredBy, long startedAt) {
}
