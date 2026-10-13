package top.sywyar.pixivdownload.plugin.api.database.migration;

/** 目标单库事务；不提供默认空实现，不承诺多个库之间的原子性。 */
public interface DatabaseImportSession extends AutoCloseable {
    /**
     * 完整写入声明表，不能截断、重复或省略行。
     * @param table 声明表名
     * @param rows 中性行流，由调用方关闭
     * @throws Exception 写入失败
     */
    void write(String table, MigrationRows rows) throws Exception;
    /**
     * 核对所有表的行数、类型化摘要、引用及序列高水位；成功后才允许 commit。
     * @param expected 源校验结果，必须拒绝未知格式或与准备结构不符的快照
     * @return 目标回读校验结果
     * @throws Exception 数据、结构、引用或序列不一致
     */
    MigrationVerification verify(MigrationSnapshot expected) throws Exception;
    /**
     * 提交前检查取消；调用开始后出错可能已提交，调用方必须报告 UNKNOWN，不能声称已回滚。
     * @throws Exception 取消或提交结果无法确认
     */
    void commit() throws Exception;
    /**
     * 中止尚未提交的事务；失败不能声称回滚成功，已提交事务不能中止。
     * @throws Exception 中止失败或已尝试提交
     */
    void abort() throws Exception;
    /** 关闭时中止未提交事务并释放全部资源；不删除已提交数据或自动重试。 */
    @Override void close() throws Exception;
}
