package top.sywyar.pixivdownload.plugin.api.maintenance;

/**
 * 维护任务接口（跨插件契约）。每个维护窗口由协调器顺序执行根上下文核心任务与活动外置
 * publication 的宿主代理稳定快照。
 *
 * <p>实现要点：
 * <ul>
 *   <li>{@link #name()} 用于日志辨识，固定且稳定。</li>
 *   <li>{@link #execute(MaintenanceContext)} 内部异常应自行吞掉/记录；协调器会捕获并继续后续任务，
 *       但抛出的异常会被记录为本任务失败。</li>
 *   <li>执行顺序保留 Spring 的 {@code PriorityOrdered}、{@code Ordered}、类型级及工厂方法
 *       {@code @Order} 语义；未声明顺序时使用 Spring 默认顺序。</li>
 *   <li>新增维护任务请实现本接口并注册为 Bean，不要绕过协调器自行写新的 {@code @Scheduled} 清理。</li>
 *   <li>外置插件任务由其 child context 提供，宿主按可信 owner/publication 发布并随生命周期撤回；
 *       核心根上下文任务始终保留。</li>
 * </ul>
 */
public interface MaintenanceTask {

    String name();

    void execute(MaintenanceContext context) throws Exception;
}
