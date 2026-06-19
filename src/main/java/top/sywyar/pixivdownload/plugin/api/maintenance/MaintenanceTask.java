package top.sywyar.pixivdownload.plugin.api.maintenance;

/**
 * 维护任务接口（跨插件契约）。每周维护窗口由维护协调器顺序遍历所有 Bean 化实现执行。
 *
 * <p>实现要点：
 * <ul>
 *   <li>{@link #name()} 用于日志辨识，固定且稳定。</li>
 *   <li>{@link #execute(MaintenanceContext)} 内部异常应自行吞掉/记录；协调器会捕获并继续后续任务，
 *       但抛出的异常会被记录为本任务失败。</li>
 *   <li>执行顺序由 Spring 的 {@code @Order} 决定；未标注则使用 Spring 默认顺序。</li>
 *   <li>新增维护任务请实现本接口并注册为 Bean，不要绕过协调器自行写新的 {@code @Scheduled} 清理。</li>
 *   <li>归属：交互 / 聚合 / 展示型维护任务应由所属功能插件经
 *       {@code PixivFeaturePlugin#maintenanceTasks()} 声明其类型——插件被禁用时协调器据此跳过它。
 *       不被任何插件声明的任务视为核心任务、始终执行。</li>
 * </ul>
 */
public interface MaintenanceTask {

    String name();

    void execute(MaintenanceContext context) throws Exception;
}
