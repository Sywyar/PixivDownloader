package top.sywyar.pixivdownload.plugin.web;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.Audience;
import top.sywyar.pixivdownload.plugin.api.web.DrilldownContribution;
import top.sywyar.pixivdownload.setup.SetupService;
import top.sywyar.pixivdownload.setup.guest.GuestInviteSession;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import top.sywyar.pixivdownload.plugin.registry.DrilldownRegistry;
import top.sywyar.pixivdownload.plugin.BuiltInPlugins;

/**
 * 下钻接口：返回 {@link DrilldownRegistry} 合并后、按当前请求<b>身份</b>可见性过滤并排序的「下钻链接模板」，
 * 供宿主页面在某语义 placement 上以运行期变量解析出可点击的下钻 href——取代页面里硬编码、按插件 id 拼接的跨插件
 * 下钻链接（宿主不再需要知道是哪个插件、目标页面路径或查询参数名）。
 * <p>
 * 可见性与排序口径同 {@link NavigationController} / {@link PageSectionController}：复用 {@link AccessPolicy#admits(Audience)}
 * 这一「该策略允许谁访问」的权威映射，按「来源层级（内置先于第三方）→ placement 内 {@link DrilldownContribution#priority()}
 * → id」三级稳定排序。请求身份按三档解析（访客优先于管理员，因 solo 模式下 {@code hasAdminScope} 对任意请求为真）。
 * <p>
 * 响应只暴露渲染所需字段（{@code id} / {@code placements} / {@code hrefTemplate} / {@code priority}，<b>不含</b>
 * {@code visibleTo}），不泄露内部访问策略模型。前端隐藏不是安全边界——下钻链接指向的目标 URL 仍由 {@code AuthFilter}
 * 鉴权。本端点 {@code /api/drilldowns} 由 {@code CorePlugin.routes()} 以 {@link AccessPolicy#VISITOR_AND_INVITED_GUEST}
 * 声明（同 {@code /api/navigation} / {@code /api/page-sections}）：multi 普通访客与受邀访客均可只读、solo 未登录 401、
 * 不入 monitor。
 */
@RestController
@RequestMapping("/api/drilldowns")
@RequiredArgsConstructor
public class DrilldownController {

    private final DrilldownRegistry drilldownRegistry;
    private final SetupService setupService;

    @GetMapping
    public List<DrilldownView> drilldowns(HttpServletRequest request,
                                          @RequestParam(value = "placement", required = false) String placement) {
        Audience audience = resolveAudience(request);
        return drilldownRegistry.drilldowns().stream()
                .filter(registered -> placement == null
                        || registered.drilldown().placements().contains(placement))
                .filter(registered -> isVisibleTo(registered.drilldown().visibleTo(), audience))
                .sorted(Comparator
                        .comparingInt(DrilldownController::sourceRank)
                        .thenComparingInt(registered -> registered.drilldown().priority())
                        .thenComparing(registered -> registered.drilldown().id()))
                .map(registered -> {
                    DrilldownContribution d = registered.drilldown();
                    return new DrilldownView(d.id(), d.placements(), d.hrefTemplate(), d.priority());
                })
                .toList();
    }

    /** 来源层级：内置插件 = 0，第三方插件 = 1（第三方贡献恒排在全部内置贡献之后，与 priority 大小无关）。 */
    private static int sourceRank(DrilldownRegistry.RegisteredDrilldown registered) {
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

    /** 某下钻策略是否对给定身份可见：{@code PUBLIC} 对所有人可见，其余按策略放行的身份集合判定。 */
    private static boolean isVisibleTo(AccessPolicy policy, Audience audience) {
        return policy == AccessPolicy.PUBLIC || policy.admits(audience);
    }

    /**
     * 下钻的对外视图：只含渲染所需字段，刻意不含 {@code visibleTo}（内部访问级别）。
     * {@code hrefTemplate} 带 {@code {变量名}} 占位，由前端通用下钻渲染器以 {@code encodeURIComponent} 后的变量替换；
     * {@code placements} 供前端按语义 placement 归类。
     */
    public record DrilldownView(String id, Set<String> placements, String hrefTemplate, int priority) {
    }
}
