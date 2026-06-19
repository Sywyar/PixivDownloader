package top.sywyar.pixivdownload.core.schedule.work;

/**
 * 计划任务交给「作品类型执行器」（{@link ScheduledWorkRunner}）下载的「一件待下载作品」的中性载体
 * （核心 owned，密封类型、纯数据）。
 *
 * <p>计划任务下载分两侧：调度壳（下载工作台的调度引擎）负责发现 / 抓详情 / 服务端筛选 / 系列富信息补全 /
 * sidecar 捕获 / 异常分类 / 运行队列；真正构造下载请求并落盘下载是各作品类型执行器的职责。两侧都依赖核心，
 * 故本载体落在 {@code core.schedule.work}：调度壳把已解析好的作品数据拷进某个 kind 变体，连同
 * {@link ScheduledWorkSettings} 经 {@link ScheduledWorkRunner#download} 交给对应执行器
 * （插画执行器住调度壳、小说执行器住小说插件），从而避免调度壳反向 import 小说包、也避免执行器反向 import
 * 调度壳的抓取结果类型。变体按 {@link #kind()} 与执行器一一对应，执行器据此向下转型取本类型数据。
 */
public sealed interface ScheduledWork permits ScheduledIllustWork, ScheduledNovelWork {

    /** 作品类型执行器路由键（{@link ScheduledWorkKind} 之一）。 */
    String kind();

    /** 作品 ID。 */
    long workId();

    /** 作品标题（供运行队列 / 日志展示）。 */
    String title();
}
