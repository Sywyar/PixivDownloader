package top.sywyar.pixivdownload.plugin.api.web;

import java.util.Set;

/**
 * 路由 / 导航项的<b>访问策略</b>：常用 {@link Audience}（身份）组合的命名常量。
 * <p>
 * 每条 {@link WebRouteContribution} 必须声明一个 {@code AccessPolicy}；{@code AuthFilter} 解析出当前请求
 * 身份后，按命中路由的策略判定放行。本枚举按<b>身份语义</b>命名（不再用 monitor / guest-whitelist 这类按
 * 历史实现机制命名的口径）。每个策略经 {@link #audiences()} 映射到它放行的身份集合，是「该策略允许谁访问」
 * 的权威说明，并被路由镜像守卫与导航可见性过滤消费。
 * <p>
 * {@code AuthFilter} 按策略分三类处置（保持历史可观察行为逐字不变）：
 * <ul>
 *   <li><b>公开</b>（{@link #PUBLIC}）：任何人放行。</li>
 *   <li><b>monitor 受控</b>（{@link #ADMIN}、{@link #INVITED_GUEST}）：阻挡匿名访客；{@code INVITED_GUEST}
 *       额外把受邀访客放进白名单（默认只读 GET/HEAD；POST 端点必须显式声明方法并有测试覆盖）。</li>
 *   <li><b>访客可达</b>（{@link #VISITOR_AND_INVITED_GUEST}）：multi 匿名访客 + 受邀访客 + 登录用户均可只读，
 *       不受 monitor 管控。</li>
 *   <li><b>身份直通 / 流程内联</b>（{@link #VISITOR}、{@link #LOCAL}、{@link #GUI}、{@link #ACTUATOR_PUBLIC}）：
 *       不派生进上述任何清单，命中后落到对应的内联流程分支或默认会话 / 访客分支——声明只为「该 URL 已纳入访问
 *       模型」（消除未声明歧义、纳入路由镜像与全 URL 声明守卫），不改变其内联流程的访问行为。</li>
 * </ul>
 * 纯 JDK 类型，不依赖 Spring / Jackson / JDBC，保持 {@code plugin.api} 轻量。
 */
public enum AccessPolicy {

    /** 公开：任何人无需鉴权即可访问（solo / multi 两种模式一致）。 */
    PUBLIC(Set.of(Audience.PUBLIC)),

    /**
     * 「登录用户或 multi 访客」：<b>multi 匿名访客可访问</b>（黑名单放行、按配额限流）、solo 需登录会话、
     * <b>受邀访客一律 403</b>（不在访客白名单）、<b>不入 monitor</b>。{@code AuthFilter} 不为该策略派生任何
     * 访问清单，命中后落默认会话 / 访客分支——访问行为与历史「未声明 API」逐字等价。用于「访客本就应能为自己
     * 使用、但受邀访客不应触达」的端点：下载工作台提交 / 装配端点、核心导航装配端点、其余随页面消费的访客可用 API。
     * <p>
     * <b>防误用</b>：multi 下对匿名访客开放；管理员专属端点用 {@link #ADMIN}，需对受邀访客开放的只读跨页依赖用
     * {@link #VISITOR_AND_INVITED_GUEST}。
     */
    VISITOR(Set.of(Audience.VISITOR, Audience.USER_SESSION, Audience.ADMIN)),

    /**
     * 访客可读 + 受邀访客可读、<b>不受 monitor 管控</b>：对非访客回退到常规会话鉴权，故 multi 匿名访客的 GET 也可访问。
     * 用于跨页共享只读静态依赖（i18n / 主题 / 侧边模块 / 翻译等脚本样式）与只读代理 / 下载状态轮询端点。
     */
    VISITOR_AND_INVITED_GUEST(Set.of(
            Audience.VISITOR, Audience.INVITED_GUEST, Audience.USER_SESSION, Audience.ADMIN)),

    /**
     * 受邀访客可读（通常为 GET/HEAD；POST 端点必须显式声明方法并有测试覆盖）+ 登录用户 / 管理员；
     * <b>同时受 monitor 管控</b>（既在 monitor 清单又在访客白名单），故 multi 匿名访客被挡。
     * 用于受邀访客可读的画廊 / 小说页面与其 API。
     */
    INVITED_GUEST(Set.of(Audience.INVITED_GUEST, Audience.USER_SESSION, Audience.ADMIN)),

    /** 管理员专用（solo 会话用户或 multi 登录管理员）；受 monitor 管控、绝不入访客 / 公开清单。 */
    ADMIN(Set.of(Audience.USER_SESSION, Audience.ADMIN)),

    /**
     * 本机放行特例：本地回环请求走快速直通分支；非本地请求回退到默认会话 / 访客分支
     * （故其完整可达面是本地 ∪ 默认分支身份）。用于 {@code /api/downloaded/**} 等本地资产端点。
     */
    LOCAL(Set.of(Audience.LOCAL)),

    /** 本机可信请求 + 有效 GUI token（{@code /api/gui/**} 双重校验，由内联分支执行）。 */
    GUI(Set.of(Audience.GUI)),

    /** actuator 探针公开端点（health / info，由内联分支放行）。 */
    ACTUATOR_PUBLIC(Set.of(Audience.PUBLIC));

    private final Set<Audience> audiences;

    AccessPolicy(Set<Audience> audiences) {
        this.audiences = Set.copyOf(audiences);
    }

    /** 本策略放行的身份集合（不可变）。 */
    public Set<Audience> audiences() {
        return audiences;
    }

    /** 该策略是否放行给定身份。 */
    public boolean admits(Audience audience) {
        return audiences.contains(audience);
    }
}
