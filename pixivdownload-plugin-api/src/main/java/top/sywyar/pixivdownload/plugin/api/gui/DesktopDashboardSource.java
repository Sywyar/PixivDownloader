package top.sywyar.pixivdownload.plugin.api.gui;

/**
 * 插件 child context 可选发布的控制中心首页只读事实源。
 *
 * <p>publication 归声明该 Bean 的插件 owner；宿主只在精确 publication 仍活动时调用，并把返回值
 * 物化为有界纯值。能力缺席、quiesce、替换或撤回时，对应卡片与任务自然缺席；普通异常按 owner
 * 隔离，允许保留先前快照的陈旧展示。本契约仅用于 best-effort 只读展示，不得执行写入、持久化、
 * 鉴权判断或其它安全副作用。
 */
@FunctionalInterface
public interface DesktopDashboardSource {
    /** @return 当前 owner 的卡片与运行任务只读快照 */
    DesktopDashboardSnapshot snapshot();
}
