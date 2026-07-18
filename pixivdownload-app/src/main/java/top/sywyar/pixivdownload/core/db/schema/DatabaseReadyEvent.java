package top.sywyar.pixivdownload.core.db.schema;

/**
 * 受管 schema 初始化（建表、补列与索引）完成后的宿主内部事件。
 *
 * <p>事件由 {@link DatabaseInitializer} 在 DDL 全部执行完毕后发布。发布时机位于
 * 单例实例化早期，因此监听方必须实现 Spring {@code ApplicationListener}，不能依赖
 * 尚未注册完成的 {@code @EventListener} 方法。
 */
public record DatabaseReadyEvent() {
}
