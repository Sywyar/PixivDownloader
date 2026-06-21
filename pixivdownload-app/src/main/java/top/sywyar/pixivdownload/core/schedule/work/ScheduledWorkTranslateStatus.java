package top.sywyar.pixivdownload.core.schedule.work;

/**
 * 计划任务队列视图里某件作品「下载即自动翻译」的实时状态（核心 owned，纯数据）。
 * 由提供该能力的作品类型执行器经 {@link ScheduledWorkRunner#translateStatus} 提供，调度壳叠加到队列视图，
 * 避免调度壳反向 import 具体翻译服务。无该能力的执行器返回 {@code null}（不叠加）。
 *
 * @param phase          翻译阶段标识（翻译服务自报的阶段枚举名）
 * @param elapsedSeconds 已耗时（秒）
 * @param seriesPending  本系列尚待翻译的章节数
 */
public record ScheduledWorkTranslateStatus(String phase, long elapsedSeconds, int seriesPending) {
}
