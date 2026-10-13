package top.sywyar.pixivdownload.plugin.api.storage;

/**
 * 绑定当前插件 owner 的私有持久化入口，使用本进程选定的数据库 provider。
 * 插件声明 privateSchema 并表达中性操作；提供者负责结构和方言，不暴露连接、路径或凭据。
 *
 * <p>缺席或故障时 {@link #available()} 为 false，操作抛出 SQLState 08003 的 SQLException；
 * 查询消费方应返回带不可用状态的空结果，写入和事务不能伪报成功，也不能继续副作用。
 * 该对象归宿主管理；事务只作用于当前私库，不加入宿主 Spring 事务。
 * 任意第三方自带的驱动和 SQLite 专用 SQL 不会被自动翻译。
 */
public interface PluginDatabase {
    /**
     * 执行只读回调，拒绝写入，会话在回调返回后失效。
     * @param <T> 回调结果类型
     * @param work 在当前线程同步执行的回调
     * @return 回调的已物化结果
     * @throws java.sql.SQLException 数据库不可用、能力不支持或执行失败；当前事务不得继续提交
     */
    <T> T read(top.sywyar.pixivdownload.plugin.api.storage.relational.DatabaseSession.Work<T> work) throws java.sql.SQLException;

    /**
     * 原子执行当前 owner 私库写入；任意操作失败后，事务只能回滚。
     * @param <T> 回调结果类型
     * @param work 在当前线程同步执行的回调
     * @return 成功执行后的回调结果
     * @throws java.sql.SQLException 数据库不可用、能力不支持或执行失败；当前事务不得继续提交
     */
    <T> T transaction(top.sywyar.pixivdownload.plugin.api.storage.relational.DatabaseSession.Work<T> work) throws java.sql.SQLException;
    /** {@return 当前提供者的诊断标识；业务操作不得据此选择方言} */
    String databaseId();

    /** {@return 私有数据库当前是否可接受操作；不可用不代表业务库为空} */
    boolean available();
}
