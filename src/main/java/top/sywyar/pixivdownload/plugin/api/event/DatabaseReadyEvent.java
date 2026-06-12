package top.sywyar.pixivdownload.plugin.api.event;

/**
 * 受管 schema 初始化（建表 / 补列 / 索引）完成，数据库可用。
 * 由核心 {@code DatabaseInitializer} 在 DDL 全部执行完毕后发布；
 * 依赖表结构就绪的启动迁移（如路径前缀迁移）应监听本事件而不是依赖注入副作用排序。
 * <p>
 * 当前经 Spring 事件发布，且发布时机在单例实例化早期（{@code @PostConstruct}）——
 * {@code @EventListener} 注解方法此刻尚未注册，监听方必须是 {@code ApplicationListener} bean。
 */
public record DatabaseReadyEvent() {
}
