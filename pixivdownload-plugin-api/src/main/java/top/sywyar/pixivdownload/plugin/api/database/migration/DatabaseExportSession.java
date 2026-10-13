package top.sywyar.pixivdownload.plugin.api.database.migration;

/**
 * 固定到一致快照的同步迁出会话；所有游标先关闭，再关闭会话，最后才能关闭插件。
 * 提供者在工作副本上应用存储文本规范化，再导出按主键排序的行与其摘要。
 * 原始备份和源变化凭据保留原数据；清洗导致键冲突或约束失败时必须拒绝迁移。
 */
public interface DatabaseExportSession extends AutoCloseable {
    /** {@return 已完成校验且含耐久备份身份的不可变快照} */
    MigrationSnapshot snapshot();
    /**
     * 同时最多一个游标；按声明主键稳定排序，一行的类型只允许 null、Long、Double、String、byte[]。
     * @param table 声明中的表名
     * @return 调用方负责关闭的流；不能缓存整表
     * @throws Exception 读取或类型校验失败
     */
    MigrationRows rows(String table) throws Exception;
    /** 释放游标、源锁及工作副本；即使失败也尝试释放全部资源，保留源和耐久备份。 */
    @Override void close() throws Exception;
}
