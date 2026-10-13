package top.sywyar.pixivdownload.plugin.api.storage;

import top.sywyar.pixivdownload.plugin.api.storage.relational.DatabaseSession;

/**
 * 当前 child context 的受限主库入口。仅能访问本 owner 的主库表及明确请求的宿主公开存储域，
 * 不暴露连接，也不能访问其它 owner 的主库表或私库。写入加入宿主事务，支持文件发布的共同提交。
 * 缺少数据库、context 关闭或声明失效时拒绝操作；查询不可用不等于没有数据。
 * 会话仅在当前线程的同步回调内有效；任一失败使当前事务不可提交。
 */
public interface PluginMainDatabase {
    /**
     * 返回当前 owner 是否仍可访问主库；不可用不代表业务结果为空。
     * @return 当前 owner 是否可接受存储操作
     */
    boolean available();
    /**
     * 执行只读操作；不可用或操作失败时抛出运行时异常。
     * @param <T> 回调结果类型
     * @param work 在当前线程同步执行的回调
     * @return 回调的已物化结果
     */
    <T> T read(DatabaseSession.Work<T> work);
    /**
     * 加入宿主事务执行写入；不可用或操作失败时抛出运行时异常并标记回滚。
     * @param <T> 回调结果类型
     * @param work 在当前线程同步执行的回调
     * @return 成功执行后的回调结果
     */
    <T> T transaction(DatabaseSession.Work<T> work);
}
