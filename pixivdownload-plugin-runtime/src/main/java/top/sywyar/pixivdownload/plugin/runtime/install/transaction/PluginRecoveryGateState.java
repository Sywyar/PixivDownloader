package top.sywyar.pixivdownload.plugin.runtime.install.transaction;

/** 当前进程的插件事务恢复准入状态。 */
public enum PluginRecoveryGateState {
    UNCHECKED,
    SAFE,
    BLOCKED
}
