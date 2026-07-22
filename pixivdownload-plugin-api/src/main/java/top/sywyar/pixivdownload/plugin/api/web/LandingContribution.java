package top.sywyar.pixivdownload.plugin.api.web;

/**
 * 插件声明的「默认落点 / 入口」（landing entrypoint）：某<b>身份</b>在某业务流程结束后应被送往的默认页面，
 * 例如受邀访客（{@link Audience#INVITED_GUEST}）兑换邀请码成功后的落地页。
 * <p>
 * 本契约与 UI 导航排序<b>刻意解耦</b>：落点选择只消费本记录的 {@link #priority()}（landing/entrypoint 优先级），
 * <b>不</b>复用 {@link NavigationContribution#priority()}（导航展示顺序）。landing priority 与 navigation
 * priority 是两个独立契约。因此第三方插件即便注册一个 priority 极小的导航项，也不会意外改变业务落点；
 * 要参与落点选择，插件必须显式声明 {@link LandingContribution}。
 * <p>
 * 落点只负责「选择跳转目标」，<b>不</b>扩大权限：后端鉴权仍由 {@code AuthFilter} / route access 按 {@code href}
 * 对应路由的访问策略执行。声明一个对该 {@code audience} 不可达的 {@code href} 属配置错误，由测试覆盖捕获
 * （见 {@code LandingRegistryTest} 的可达性守卫），不应静默产生坏入口。
 *
 * @param id       落点项唯一 id（全局唯一，便于诊断 / 去重）
 * @param audience 该落点服务的目标身份
 * @param href     落点目标页（必须以 {@code /} 开头，且应是对该 {@code audience} 可达的已声明路由）
 * @param priority 落点优先级，<b>越小越优先</b>（landing/entrypoint priority，<b>不是</b>导航 order）
 */
public record LandingContribution(
        String id,
        Audience audience,
        String href,
        int priority
) {
}
