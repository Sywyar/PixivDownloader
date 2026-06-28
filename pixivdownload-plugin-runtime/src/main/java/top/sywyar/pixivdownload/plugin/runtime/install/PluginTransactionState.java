package top.sywyar.pixivdownload.plugin.runtime.install;

/** 插件文件事务的持久化状态。 */
public enum PluginTransactionState {
    PREPARED,
    OLD_ISOLATED,
    NEW_PLACED,
    ACTIVATED,
    COMMITTED
}
