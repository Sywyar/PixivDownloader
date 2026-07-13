package top.sywyar.pixivdownload.plugin.lifecycle;

/** 仅供测试驱动真实宿主 schedule 生命周期授权路径。 */
public final class ScheduleContributionLifecycleAuthorityTestAccess {

    private ScheduleContributionLifecycleAuthorityTestAccess() {
    }

    public static ScheduleContributionLifecycleAuthority create() {
        return new ScheduleContributionLifecycleAuthority();
    }
}
