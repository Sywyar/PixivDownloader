package top.sywyar.pixivdownload.core.database;

/** 数据库能力不可用，当前命令未执行；不得重试到其它目标或继续依赖该命令的副作用。 */
public final class DatabaseUnavailableException extends IllegalStateException {
    /** 创建不携带连接地址或凭据的不可用异常。 */
    public DatabaseUnavailableException() {
        super("DATABASE_UNAVAILABLE");
    }
}
