package top.sywyar.pixivdownload.core.database;

/**
 * 宿主拥有的当前数据库状态；绑定本进程选定的 provider，不随保存或安装替换目标。
 * 展示查询在缺库时返回原类型的空值，调用方必须同时检查此状态，不能把空值当作有效空库。
 * 判重、清理、下载和任务推进须先调用 {@link #requireAvailable()}；不可用时不得继续副作用。
 * 此检查不替代事务与底层连接的生命周期校验，也不允许回落到其它数据库。
 */
@FunctionalInterface
public interface DatabaseAvailability {
    /** {@return 当前数据库是否接受业务访问；false 表示不可用而非没有数据} */
    boolean available();

    /** 缺库时明确拒绝尚未执行的操作。 */
    default void requireAvailable() {
        if (!available()) throw new DatabaseUnavailableException();
    }
}
