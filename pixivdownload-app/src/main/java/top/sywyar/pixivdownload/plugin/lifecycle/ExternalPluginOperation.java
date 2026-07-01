package top.sywyar.pixivdownload.plugin.lifecycle;

/** 与稳定运行阶段正交的包级写操作状态。 */
public enum ExternalPluginOperation {
    IDLE,
    INSTALLING,
    UPDATING,
    REMOVING,
    ROLLING_BACK,
    FAILED
}
