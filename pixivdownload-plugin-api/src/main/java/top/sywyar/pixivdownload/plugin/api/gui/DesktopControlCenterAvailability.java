package top.sywyar.pixivdownload.plugin.api.gui;

/**
 * 控制中心只读事实的可用性。
 *
 * <p>该状态只描述非权威展示快照，不表示持久化或写操作成功；能力缺席、quiesce、替换或撤回时，
 * 宿主会移除对应 publication 的事实，不保留旧插件引用。
 */
public enum DesktopControlCenterAvailability {
    /** 快照可正常使用。 */
    AVAILABLE,
    /** 快照仍可展示，但已超过宿主的新鲜度窗口。 */
    STALE,
    /** 事实源当前无法提供可靠快照。 */
    UNAVAILABLE
}
