package top.sywyar.pixivdownload.plugin.api.gui;

/**
 * 插件 child context 可选发布的自动化只读事实源。
 *
 * <p>publication 归声明该 Bean 的插件 owner；宿主只在精确 publication 活动时调用，并把返回值
 * 物化为有界纯值。能力缺席、quiesce、替换或撤回时自动化投影自然缺席；普通异常按 owner 隔离，
 * 可降级为陈旧或不可用。本契约仅用于 best-effort 观察，不得暴露定义、凭据、写动作、持久化或
 * 安全副作用。
 */
@FunctionalInterface
public interface DesktopAutomationSource {
    /** @return 当前 owner 的自动化只读快照 */
    DesktopAutomationSnapshot snapshot();
}
