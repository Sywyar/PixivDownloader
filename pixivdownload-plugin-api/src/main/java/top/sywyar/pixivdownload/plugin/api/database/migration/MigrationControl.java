package top.sywyar.pixivdownload.plugin.api.database.migration;

/** 同步检查点；provider 在逐行处理与不可逆提交前调用，取消不得被吞掉或变成成功。 */
@FunctionalInterface
public interface MigrationControl {
    /**
     * @param table 当前表，准备与关闭阶段为空字符串
     * @param rows 当前表已处理行数
     * @throws Exception 取消或持久进度写入失败；provider 必须停止并释放资源
     */
    void checkpoint(String table, long rows) throws Exception;
}
