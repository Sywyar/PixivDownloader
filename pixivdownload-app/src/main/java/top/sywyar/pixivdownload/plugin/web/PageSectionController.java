package top.sywyar.pixivdownload.plugin.web;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.Audience;
import top.sywyar.pixivdownload.plugin.api.web.PageSectionContribution;
import top.sywyar.pixivdownload.setup.SetupService;
import top.sywyar.pixivdownload.setup.guest.GuestInviteSession;

import java.util.Comparator;
import java.util.List;
import top.sywyar.pixivdownload.plugin.registry.PageSectionRegistry;
import top.sywyar.pixivdownload.plugin.BuiltInPlugins;

/**
 * 页面区块接口：返回 {@link PageSectionRegistry} 合并后、按当前请求<b>身份</b>可见性过滤并排序的页面区块，
 * 供宿主页面把活动插件贡献的区块渲染进对应的空 section slot——取代页面里硬编码、按插件 id 显隐的业务块
 * （宿主不再需要知道是哪个插件、是否启用）。
 * <p>
 * 可见性与排序口径同 {@link NavigationController}：复用 {@link AccessPolicy#isVisibleTo(Audience)} 的页面身份投影，
 * 按「来源层级（内置先于第三方）→ placement 内 {@link PageSectionContribution#priority()} → id」三级稳定排序。
 * 请求身份按三档解析（访客优先于管理员，因 solo 模式下 {@code hasAdminScope} 对任意请求为真）。
 * <p>
 * 响应只暴露渲染所需字段（不含 {@code visibleTo}），不泄露内部访问策略模型；标签 / 操作标题只返回 i18n key，
 * 文案由前端按当前语言解析。前端隐藏不是安全边界——区块内任何 href / API 仍由 {@code AuthFilter} 鉴权。
 * 本端点 {@code /api/page-sections} 由 {@code CorePlugin.routes()} 以 {@link AccessPolicy#VISITOR_AND_INVITED_GUEST}
 * 声明（同 {@code /api/navigation}）：multi 普通访客与受邀访客均可只读、solo 未登录 401、不入 monitor。
 */
@RestController
@RequestMapping("/api/page-sections")
@RequiredArgsConstructor
public class PageSectionController {

    private final PageSectionRegistry pageSectionRegistry;
    private final SetupService setupService;

    @GetMapping
    public List<PageSectionView> sections(HttpServletRequest request,
                                          @RequestParam(value = "placement", required = false) String placement) {
        Audience audience = resolveAudience(request);
        return pageSectionRegistry.sections().stream()
                .filter(registered -> placement == null || placement.equals(registered.section().placement()))
                .filter(registered -> registered.section().visibleTo().isVisibleTo(audience))
                .sorted(Comparator
                        .comparingInt(PageSectionController::sourceRank)
                        .thenComparingInt(registered -> registered.section().priority())
                        .thenComparing(registered -> registered.section().id()))
                .map(registered -> {
                    PageSectionContribution s = registered.section();
                    return new PageSectionView(s.id(), s.placement(),
                            s.titleNamespace(), s.titleI18nKey(), s.navPlacement(),
                            s.actionHref(), s.actionIcon(), s.actionTitleNamespace(), s.actionTitleI18nKey(),
                            s.moduleUrl(), s.priority());
                })
                .toList();
    }

    /** 来源层级：内置插件 = 0，第三方插件 = 1（第三方区块恒排在全部内置区块之后，与 priority 大小无关）。 */
    private static int sourceRank(PageSectionRegistry.RegisteredSection registered) {
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

    /**
     * 区块的对外视图：只含渲染所需字段，刻意不含 {@code visibleTo}（内部访问级别）。
     * {@code navPlacement} / {@code actionHref} / {@code actionIcon} / {@code actionTitleI18nKey} / {@code moduleUrl}
     * 为 {@code null} 表示不提供对应能力。
     */
    public record PageSectionView(String id, String placement, String titleNamespace, String titleI18nKey,
                                  String navPlacement, String actionHref, String actionIcon,
                                  String actionTitleNamespace, String actionTitleI18nKey,
                                  String moduleUrl, int priority) {
    }
}
