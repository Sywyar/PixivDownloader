package top.sywyar.pixivdownload.plugin.lifecycle;

/**
 * 宿主插件生命周期编排器调用 schedule contribution mutation 的不透明授权。
 *
 * <p>只有同包生命周期编排代码可以创建实例；外置插件即使取得父 context Bean，也不能构造可用授权。
 */
public final class ScheduleContributionLifecycleAuthority {

    ScheduleContributionLifecycleAuthority() {
    }

    @Override
    public String toString() {
        return "ScheduleContributionLifecycleAuthority[opaque]";
    }
}
