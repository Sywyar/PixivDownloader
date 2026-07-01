package top.sywyar.pixivdownload.plugin.web;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.Audience;
import top.sywyar.pixivdownload.plugin.api.web.NavigationContribution;
import top.sywyar.pixivdownload.setup.SetupService;
import top.sywyar.pixivdownload.setup.guest.GuestInviteSession;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import top.sywyar.pixivdownload.plugin.registry.NavigationRegistry;
import top.sywyar.pixivdownload.plugin.BuiltInPlugins;

/**
 * 导航接口：返回 {@link NavigationRegistry} 合并后、按当前请求<b>身份</b>可见性过滤并按
 * 「来源层级 → placement 内 priority → id」三级排序的导航项，供页面动态渲染跨插件导航
 * （取代 HTML 中的硬编码入口）。每项带 {@link NavigationView#placements()}，前端据此把它渲染进对应的空 slot。
 * <p>
 * 可见性以请求身份（{@link Audience}）为准，复用 {@link AccessPolicy#audiences()} 这一「该策略允许谁访问」
 * 的权威映射——某导航项可见当且仅当其 {@code visibleTo} 策略放行当前身份（{@code PUBLIC} 项对所有人可见）。
 * 故导航显隐与 {@code AuthFilter} 的路由放行口径一致：能进入导航栏的入口正是该身份点开后不会被挡的入口。
 * <p>
 * 请求身份按三档解析（访客优先于管理员，因 solo 模式下 {@code hasAdminScope} 对任意请求为真，须先排除访客身份）：
 * <ul>
 *   <li>访客邀请会话 → {@link Audience#INVITED_GUEST}：可见 {@code PUBLIC} 与放行受邀访客的项
 *       （{@code INVITED_GUEST} / {@code VISITOR_AND_INVITED_GUEST}），看不到 {@code VISITOR}（如下载页）
 *       与 {@code ADMIN}（监控 / 统计 / 疑似重复 / 邀请码管理）项——这些点开本就会 403，故不进其导航栏；</li>
 *   <li>管理员范围（solo 任意请求 / multi 登录管理员）→ {@link Audience#ADMIN}：可见全部；</li>
 *   <li>其余（multi 匿名访客）→ {@link Audience#VISITOR}：可见 {@code PUBLIC} 与放行访客的项（如下载页），
 *       看不到仅受邀访客 / 管理员可达的项。</li>
 * </ul>
 * 访客身份从请求上下文的 {@link GuestInviteSession#REQUEST_ATTR} 读取（由 {@code AuthFilter} 在非公开请求上
 * 解析挂载）。本端点 {@code /api/navigation} 由 {@code CorePlugin.routes()} 以
 * {@link AccessPolicy#VISITOR_AND_INVITED_GUEST} 声明：multi 普通访客与受邀访客均可只读（各自得到对应身份
 * 可见导航）、solo 未登录 401、不入 monitor。<b>受邀访客现可真实读取本端点</b>（历史上曾以 {@code VISITOR}
 * 声明而被 {@code AuthFilter} 挡成 403，现改为放行使其页面能拉取动态导航），故上面「访客邀请会话」一档现已生效。
 * <p>
 * 响应只暴露渲染所需字段（不含 {@code visibleTo}），不泄露内部访问策略模型；标签只返回 i18n key，
 * 文案由前端按当前语言解析。
 * <p>
 * 排序三级稳定：① <b>来源层级</b>——内置插件（{@code BuiltInPlugins.isBuiltIn}）恒先于第三方插件，
 * 故第三方插件即便填很小的 priority 也只能追加在内置项之后，不能插到内置基础 / 功能页面之前；
 * ② placement 内 {@link NavigationContribution#priority()}（内置基础页面取较小值——下载工作台 10、监控 20——
 * 功能页面其次、管理入口最大，故同一 slot 内自带页面在前、后续在后）；③ id 兜底稳定。来源层级只区分
 * 内置 / 第三方这条硬边界；内置项之间的基础页面 vs 功能页面先后由 priority 表达。
 */
@RestController
@RequestMapping("/api/navigation")
@RequiredArgsConstructor
public class NavigationController {

    private final NavigationRegistry navigationRegistry;
    private final SetupService setupService;

    @GetMapping
    public List<NavigationView> navigation(HttpServletRequest request) {
        Audience audience = resolveAudience(request);
        return navigationRegistry.navigation().stream()
                .filter(registered -> isVisibleTo(registered.navigation().visibleTo(), audience))
                .sorted(Comparator
                        .comparingInt(NavigationController::sourceRank)
                        .thenComparingInt(registered -> registered.navigation().priority())
                        .thenComparing(registered -> registered.navigation().id()))
                .map(registered -> {
                    NavigationContribution item = registered.navigation();
                    return new NavigationView(item.id(), item.placements(),
                            item.labelNamespace(), item.labelI18nKey(),
                            item.href(), item.icon(), item.priority());
                })
                .toList();
    }

    /**
     * 来源层级：内置插件（核心 / 必选基础页面 / 内置功能插件）= 0，第三方插件 = 1。仅此一条硬边界——
     * 第三方项恒排在全部内置项之后（与其 priority 大小无关）；内置项之间的先后由 priority 决定。
     */
    private static int sourceRank(NavigationRegistry.RegisteredNavigation registered) {
        return BuiltInPlugins.isBuiltIn(registered.pluginId()) ? 0 : 1;
    }

    private Audience resolveAudience(HttpServletRequest request) {
        if (request.getAttribute(GuestInviteSession.REQUEST_ATTR) instanceof GuestInviteSession) {
            return Audience.INVITED_GUEST;
        }
        if (setupService.hasAdminScope(request)) {
            return Audience.ADMIN;
        }
        return Audience.VISITOR;
    }

    /** 某导航策略是否对给定身份可见：{@code PUBLIC} 对所有人可见，其余按策略放行的身份集合判定。 */
    private static boolean isVisibleTo(AccessPolicy policy, Audience audience) {
        return policy == AccessPolicy.PUBLIC || policy.admits(audience);
    }

    /**
     * 导航项的对外视图：只含渲染所需字段，刻意不含 {@code visibleTo}（内部访问级别）。
     * {@link #placements()} 供前端把本项渲染进对应的空 slot（{@code data-nav-slot="<placement>"}）。
     */
    public record NavigationView(String id, Set<String> placements, String labelNamespace, String labelI18nKey,
                                 String href, String icon, int priority) {
    }
}
