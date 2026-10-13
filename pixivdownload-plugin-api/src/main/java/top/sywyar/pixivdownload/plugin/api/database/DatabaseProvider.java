package top.sywyar.pixivdownload.plugin.api.database;

import java.sql.SQLException;

/**
 * 外置插件在进程启动时提供的数据库能力。实现与功能插件入口属于同一个已验证的包，
 * 不依赖业务子上下文或已建好的数据库。宿主选择精确 owner，能力缺席时进入恢复模式，不能改投其它实现。
 *
 * <p>实现必须声明完整进程重启生命周期；安装、停用或替换只影响下次进程启动。
 * 宿主在关闭全部连接后才释放持有此能力的插件，普通插件不能取得该能力或主库连接。
 */
public interface DatabaseProvider extends top.sywyar.pixivdownload.plugin.api.database.migration.DatabaseMigrationProvider {
    /** {@return 所提供 SQL 方言的稳定标识；同一方言可以有多个实现，不能代替插件 owner 身份} */
    String databaseId();

    /**
     * 声明需要用户填写的连接参数及类型；空列表表示无需输入。
     * 不连接数据库；返回不可变且无重复键的声明，不包含默认凭据或配置值。
     * @return 按显示顺序排列的连接字段
     */
    java.util.List<DatabaseConnectionField> connectionFields();

    /**
     * 在宿主取得实例锁且固定选择后准备主库存储位置；只处理本提供者的历史布局。
     * 不初始化业务结构、不清空冲突文件、不更改配置。检查与迁移预览不得调用。
     * 远程提供者须明确保留其已有服务端位置；失败阻止绑定，不能创建另一空库继续。
     * @param settings 宿主绑定的完整连接设置，不得记录凭据
     * @throws java.sql.SQLException 配置或存储位置不受支持；异常不得包含凭据
     */
    void prepareStorage(DatabaseConnectionSettings settings) throws SQLException;

    /**
     * 将管理员明确选择的本地文件映射为本提供者的配置；非文件实现明确拒绝。
     * @param file 管理员明确选择的本地数据库文件
     * @param settings 宿主绑定的完整连接设置，不得记录凭据
     * @return 映射到所选文件的新连接设置
     * @throws java.sql.SQLException 配置或存储位置不受支持；异常不得包含凭据
     */
    DatabaseConnectionSettings localFileSettings(DatabaseConnectionSettings settings, java.nio.file.Path file) throws SQLException;

    /**
     * 规范化完整配置草稿，清除本提供者不使用的字段，不连接数据库或改变文件。
     * 宿主已处理密码保留与显式清除；返回值随后仍须经过 resolve 校验。
     * @param selectionChanged 本次草稿是否更换了提供者
     * @param settings 宿主绑定的完整连接设置，不得记录凭据
     * @return 规范化后的草稿，仍须经过 resolve 校验
     * @throws java.sql.SQLException 配置或存储位置不受支持；异常不得包含凭据
     */
    DatabaseConnectionSettings normalizeConfiguration(DatabaseConnectionSettings settings, boolean selectionChanged) throws SQLException;

    /**
     * 解析并验证连接配置，不创建文件、连接、schema 或后台任务。
     * @param settings 宿主提供的连接设置，不得记录密码或完整连接 URL
     * @return 固定到同一个连接目标的能力
     * @throws SQLException 配置非法；异常只包含安全机器码及 SQLState
     */
    DatabaseConnectionTarget resolve(DatabaseConnectionSettings settings) throws SQLException;
}
