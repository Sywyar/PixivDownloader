package top.sywyar.pixivdownload.plugin.api.web;

import java.util.Objects;
import java.util.Set;

/**
 * 路由的<b>访问策略</b>，以及其中可安全投影为 Web UI 可见性的策略子集。
 * <p>
 * 每条 {@link WebRouteContribution} 必须声明一个 {@code AccessPolicy}；宿主鉴权流程按命中路由的策略执行。
 * 路由策略包含两类语义：面向页面身份的常规策略，以及依赖本机来源、GUI token、actuator 分支等额外上下文的
 * 流程策略。后者无法仅凭 {@link Audience} 判定，因此不得用于导航、页面区块或下钻贡献的 {@code visibleTo}。
 * 调用方应在注册 / 聚合期用 {@link #supportsUiVisibility()} 拒绝这类错误声明；当前页面身份的显隐统一通过
 * {@link #isVisibleTo(Audience)} 判定。
 * <p>
 * {@code AuthFilter} 按策略分四类处置（保持历史可观察行为逐字不变）：
 * <ul>
 *   <li><b>公开</b>（{@link #PUBLIC}）：任何人放行。</li>
 *   <li><b>monitor 受控</b>（{@link #ADMIN}、{@link #INVITED_GUEST}）：阻挡匿名访客；{@code INVITED_GUEST}
 *       额外把受邀访客放进白名单（默认只读 GET/HEAD；POST 端点必须显式声明方法并有测试覆盖）。</li>
 *   <li><b>访客可达</b>（{@link #VISITOR_AND_INVITED_GUEST}）：multi 匿名访客 + 受邀访客 + 管理员均可只读，
 *       不受 monitor 管控。</li>
 *   <li><b>默认分支 / 流程内联</b>（{@link #VISITOR}、{@link #LOCAL}、{@link #GUI}、{@link #ACTUATOR_PUBLIC}）：
 *       不派生进上述任何清单，命中后落到对应的内联流程分支或默认会话 / 访客分支——声明只为「该 URL 已纳入访问
 *       模型」（消除未声明歧义、纳入路由镜像与全 URL 声明守卫），不改变其内联流程的访问行为。</li>
 * </ul>
 * 纯 JDK 类型，不依赖 Spring / Jackson / JDBC，保持 {@code plugin.api} 轻量。
 */
public enum AccessPolicy {

    /** 公开：任何人无需鉴权即可访问（solo / multi 两种模式一致）。 */
    PUBLIC(true, Set.of()),

    /**
     * 「管理员或 multi 访客」：<b>multi 匿名访客可访问</b>（黑名单放行、按配额限流）、solo 需管理员会话、
     * <b>受邀访客一律 403</b>（不在访客白名单）、<b>不入 monitor</b>。{@code AuthFilter} 不为该策略派生任何
     * 访问清单，命中后落默认会话 / 访客分支——访问行为与历史「未声明 API」逐字等价。用于「访客本就应能为自己
     * 使用、但受邀访客不应触达」的端点：下载工作台提交 / 装配端点、核心导航装配端点、其余随页面消费的访客可用 API。
     * <p>
     * <b>防误用</b>：multi 下对匿名访客开放；管理员专属端点用 {@link #ADMIN}，需对受邀访客开放的只读跨页依赖用
     * {@link #VISITOR_AND_INVITED_GUEST}。
     */
    VISITOR(true, Set.of(Audience.VISITOR, Audience.ADMIN)),

    /**
     * 访客可读 + 受邀访客可读、<b>不受 monitor 管控</b>：对非访客回退到常规会话鉴权，故 multi 匿名访客的 GET 也可访问。
     * 用于跨页共享只读静态依赖（i18n / 主题 / 侧边模块 / 翻译等脚本样式）与只读代理 / 下载状态轮询端点。
     */
    VISITOR_AND_INVITED_GUEST(true, Set.of(
            Audience.VISITOR, Audience.INVITED_GUEST, Audience.ADMIN)),

    /**
     * 受邀访客可读（通常为 GET/HEAD；POST 端点必须显式声明方法并有测试覆盖）+ 管理员；
     * <b>同时受 monitor 管控</b>（既在 monitor 清单又在访客白名单），故 multi 匿名访客被挡。
     * 用于受邀访客可读的画廊 / 小说页面与其 API。
     */
    INVITED_GUEST(true, Set.of(Audience.INVITED_GUEST, Audience.ADMIN)),

    /** 管理员专用（solo 管理员会话或 multi 登录管理员）；受 monitor 管控、绝不入访客 / 公开清单。 */
    ADMIN(true, Set.of(Audience.ADMIN)),

    /**
     * 本机放行特例：本地回环请求走快速直通分支；非本地请求回退到默认会话 / 访客分支
     * （故其完整可达面是本地 ∪ 默认分支身份）。用于 {@code /api/downloaded/**} 等本地资产端点。
     */
    LOCAL(false, Set.of()),

    /** 本机可信请求 + 有效 GUI token（{@code /api/gui/**} 双重校验，由内联分支执行）。 */
    GUI(false, Set.of()),

    /** actuator 探针公开端点（health / info，由内联分支放行）。 */
    ACTUATOR_PUBLIC(false, Set.of());

    private final boolean uiVisibility;
    private final Set<Audience> visibleAudiences;

    AccessPolicy(boolean uiVisibility, Set<Audience> visibleAudiences) {
        this.uiVisibility = uiVisibility;
        this.visibleAudiences = Set.copyOf(visibleAudiences);
    }

    /** 本策略是否能仅凭页面身份安全判定 UI contribution 的可见性。 */
    public boolean supportsUiVisibility() {
        return uiVisibility;
    }

    /**
     * 本策略对给定页面身份是否可见。
     *
     * @throws IllegalStateException 本策略依赖额外流程上下文，不能投影为 UI 可见性
     * @throws NullPointerException audience 为 {@code null}
     */
    public boolean isVisibleTo(Audience audience) {
        if (!uiVisibility) {
            throw new IllegalStateException("access policy cannot be projected to UI visibility: " + this);
        }
        Objects.requireNonNull(audience, "audience");
        return this == PUBLIC || visibleAudiences.contains(audience);
    }
}
