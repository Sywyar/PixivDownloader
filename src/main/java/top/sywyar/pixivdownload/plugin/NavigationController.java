package top.sywyar.pixivdownload.plugin;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.NavigationContribution;
import top.sywyar.pixivdownload.setup.SetupService;
import top.sywyar.pixivdownload.setup.guest.GuestInviteSession;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 导航接口：返回 {@link NavigationRegistry} 合并后、按当前请求可见性过滤并按 {@code order}
 * 排序的导航项，供页面动态渲染导航（取代 HTML 中的硬编码）。
 * <p>
 * 可见性按三档判定（访客优先于管理员，因 solo 模式下 {@code hasAdminScope} 对任意请求为真，
 * 须先排除访客身份）：
 * <ul>
 *   <li>访客邀请会话：可见 {@code PUBLIC} 与 {@code INVITED_GUEST}；</li>
 *   <li>管理员范围（solo 任意请求 / multi 登录管理员）：可见全部；</li>
 *   <li>其余（multi 匿名）：仅可见 {@code PUBLIC}。</li>
 * </ul>
 * 访客身份从请求上下文的 {@link GuestInviteSession#REQUEST_ATTR} 读取（由 {@code AuthFilter}
 * 在非公开请求上解析挂载）。本端点 {@code /api/navigation} 由 {@code CorePlugin.routes()} 以
 * {@link AccessPolicy#VISITOR} 声明，访问行为保持历史现状：multi 普通访客可读（得到匿名可见
 * 导航）、solo 未登录 401、<b>邀请访客在 {@code AuthFilter} 即被 403</b>、不入 monitor。因此上面
 * 「访客邀请会话」一档目前不会被真实邀请访客触达，是<b>防御性预置</b>——仅在未来若放开邀请访客对本端点的
 * 可达性时才生效，<b>不代表当前已对邀请访客开放导航</b>。
 * <p>
 * 响应只暴露渲染所需字段（不含 {@code visibleTo}），不泄露内部访问级别模型；标签只返回
 * i18n key，文案由前端按当前语言解析。
 */
@RestController
@RequestMapping("/api/navigation")
@RequiredArgsConstructor
public class NavigationController {

    /** 访客邀请会话可见的访问策略。 */
    private static final Set<AccessPolicy> GUEST_VISIBLE =
            Set.of(AccessPolicy.PUBLIC, AccessPolicy.INVITED_GUEST);

    /** 管理员范围可见的访问策略（全部导航相关策略）。 */
    private static final Set<AccessPolicy> ADMIN_VISIBLE =
            Set.of(AccessPolicy.PUBLIC, AccessPolicy.INVITED_GUEST, AccessPolicy.ADMIN);

    /** 匿名请求可见的访问策略。 */
    private static final Set<AccessPolicy> ANONYMOUS_VISIBLE = Set.of(AccessPolicy.PUBLIC);

    private final NavigationRegistry navigationRegistry;
    private final SetupService setupService;

    @GetMapping
    public List<NavigationView> navigation(HttpServletRequest request) {
        Set<AccessPolicy> visible = visibleLevels(request);
        return navigationRegistry.navigation().stream()
                .map(NavigationRegistry.RegisteredNavigation::navigation)
                .filter(item -> visible.contains(item.visibleTo()))
                .sorted(Comparator.comparingInt(NavigationContribution::order)
                        .thenComparing(NavigationContribution::id))
                .map(item -> new NavigationView(
                        item.id(), item.labelI18nKey(), item.href(), item.icon(), item.order()))
                .toList();
    }

    private Set<AccessPolicy> visibleLevels(HttpServletRequest request) {
        if (request.getAttribute(GuestInviteSession.REQUEST_ATTR) instanceof GuestInviteSession) {
            return GUEST_VISIBLE;
        }
        if (setupService.hasAdminScope(request)) {
            return ADMIN_VISIBLE;
        }
        return ANONYMOUS_VISIBLE;
    }

    /**
     * 导航项的对外视图：只含渲染所需字段，刻意不含 {@code visibleTo}（内部访问级别）。
     */
    public record NavigationView(String id, String labelI18nKey, String href, String icon, int order) {
    }
}
