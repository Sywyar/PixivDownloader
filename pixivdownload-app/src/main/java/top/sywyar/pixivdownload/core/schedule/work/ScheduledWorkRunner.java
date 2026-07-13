package top.sywyar.pixivdownload.core.schedule.work;

import java.io.IOException;

/**
 * 计划任务里某一<b>作品类型</b>（插画 / 小说 ...）下载一侧的核心契约。各作品类型由一个执行器承载，经
 * {@link top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistry} 按 {@link #kind()} 解析并在
 * generation lease 内由调度壳调用——调度主编排因此不再为
 * 小说单列分支、也不强依赖任一具体下载实现。
 *
 * <p>这是清偿「schedule 编排层显式依赖小说具体服务」耦合、并把插画下载也统一到同一执行器抽象的接缝：
 * 调度壳只依赖本核心接口与中性载体（{@link ScheduledWork} / {@link ScheduledWorkSettings}），把已发现 / 抓取 /
 * 筛选 / 系列补全好的中性数据交给对应执行器去构造下载请求、落盘下载、系列合订与翻译状态查询。发现 / 服务端
 * 筛选 / sidecar 捕获 / 异常分类 / 限流 / 熔断 / 代理 / 运行队列等共享调度机器仍留在调度壳，不随某作品类型移走。
 *
 * <p>执行器由贡献该作品类型的一侧实现并显式装配（插画执行器住调度壳 / 下载工作台，小说执行器住小说插件、随
 * 小说插件生命周期归属）。某作品类型执行器缺席（如对应插件被禁 / 卸载）时，统一 registry 不会发放该能力租约，
 * 调度壳把该类型任务标记为不可用并干净挂起，绝不继续下载、也不导致启动失败。
 */
public interface ScheduledWorkRunner {

    /** 本执行器承载的作品类型路由键（{@link ScheduledWorkKind} 之一），在注册中心内全局唯一。 */
    String kind();

    /**
     * 按中性载体与下载设置同步下载一件作品，直到落盘、入库与后置 best-effort 动作结束后才返回。
     * 实现可假定 {@code work} / {@code settings} 是本 {@link #kind()} 对应的变体（由注册中心解析保证），
     * 向下转型取用。
     *
     * @param work     待下载作品的中性数据（已含系列富信息补全结果）
     * @param settings 下载设置（含自动翻译等参数）
     * @param cookie   本次下载使用的 Pixiv Cookie；可为 {@code null}（匿名 / 受限）
     * @return 下载服务完成且未进入顶层失败 / 取消分支时返回 {@code true}
     */
    boolean download(ScheduledWork work, ScheduledWorkSettings settings, String cookie);

    /**
     * 重新生成作品系列译文 / 原文合订本（best-effort，由调度壳在本轮有新成员时触发，幂等）。
     * 仅支持系列合订的作品类型（如小说）才覆盖本方法；默认空实现。调度壳负责捕获异常、不让合订失败翻转运行结果。
     *
     * @param seriesId    系列 ID
     * @param mergeFormat 合订本格式（{@code epub} / {@code txt} / {@code html}，不区分大小写）
     */
    default void mergeSeries(long seriesId, String mergeFormat) throws IOException {
    }

    /**
     * 取某件作品「下载即自动翻译」的实时状态，供队列视图叠加（非阻塞读）。
     * 仅提供该能力的作品类型才覆盖本方法；默认无能力（返回 {@code null}、不叠加）。
     *
     * @return 该作品本轮无翻译状态、或本类型无翻译能力时返回 {@code null}
     */
    default ScheduledWorkTranslateStatus translateStatus(long workId) {
        return null;
    }
}
