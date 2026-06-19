package top.sywyar.pixivdownload.core.schedule.work;

/**
 * 计划任务下载设置的中性载体（核心 owned，密封类型、纯数据）。由调度壳从任务快照的 {@code download} 段映射而来，
 * 经 {@link ScheduledWorkRunner#download} 连同 {@link ScheduledWork} 交给对应作品类型执行器组装下载请求。
 *
 * <p>与 {@link ScheduledWork}（抓取所得的作品数据）相对：本接口承载用户的下载配置（文件名模板 / 收藏 / 收藏集 /
 * 自动翻译等）。变体按 {@link #kind()} 与执行器一一对应，执行器据此向下转型取本类型设置。
 */
public sealed interface ScheduledWorkSettings permits ScheduledIllustSettings, ScheduledNovelSettings {

    /** 作品类型执行器路由键（{@link ScheduledWorkKind} 之一）。 */
    String kind();
}
