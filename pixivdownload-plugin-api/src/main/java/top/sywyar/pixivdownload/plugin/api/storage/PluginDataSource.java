package top.sywyar.pixivdownload.plugin.api.storage;

import javax.sql.DataSource;

/**
 * 当前插件私有 SQLite 数据库的数据源。
 *
 * <p>宿主将该能力绑定到 {@code data/{owner}/plugin.db}，且绝不暴露宿主数据库。因此插件可以
 * 拥有自己的 schema，并使用普通 JDBC 或随插件打包的持久化库，而无需依赖应用 mapper、SQL session
 * 或数据库配置。
 *
 * <p>该数据源的生命周期归宿主管理。插件不得关闭该数据源，也不得将其解包为宿主特定实现。
 */
public interface PluginDataSource extends DataSource {
}
