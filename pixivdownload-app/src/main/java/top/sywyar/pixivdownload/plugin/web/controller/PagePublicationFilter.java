package top.sywyar.pixivdownload.plugin.web.controller;

import tools.jackson.databind.json.JsonMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import top.sywyar.pixivdownload.common.web.SafeRequestPath;
import top.sywyar.pixivdownload.plugin.api.web.HttpMethod;
import top.sywyar.pixivdownload.plugin.registry.route.RouteAccessRegistry;
import top.sywyar.pixivdownload.plugin.registry.web.PageContributionRegistry;
import java.io.IOException;

/** 组件客户端绑定精确贡献代际，身份与 CSRF 校验仍由原过滤链执行。 */
@Component
@Order(3)
public final class PagePublicationFilter extends OncePerRequestFilter {
    private final RouteAccessRegistry routes;
    public static final String PAGE_HEADER = "X-Pixiv-Page-Publication";
    public static final String SLOT_HEADER = "X-Pixiv-Ui-Publication";
    public static final String PAGE_QUERY = "pagePublication";
    public static final String SLOT_QUERY = "uiPublication";
    private final PageContributionRegistry pages;
    private final top.sywyar.pixivdownload.plugin.registry.web.WebUiSlotRegistry slots;
    private final top.sywyar.pixivdownload.i18n.MessageResolver messages;
    public PagePublicationFilter(RouteAccessRegistry routes, PageContributionRegistry pages, top.sywyar.pixivdownload.i18n.MessageResolver messages) {
        this(routes, pages, messages, null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public PagePublicationFilter(RouteAccessRegistry routes, PageContributionRegistry pages,
            top.sywyar.pixivdownload.i18n.MessageResolver messages,
            top.sywyar.pixivdownload.plugin.registry.web.WebUiSlotRegistry slots) {
        this.routes = routes; this.pages = pages; this.messages = messages; this.slots = slots;
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String token = request.getHeader(PAGE_HEADER);
        if (token == null) token = request.getParameter(PAGE_QUERY);
        String slotToken = request.getHeader(SLOT_HEADER);
        if (slotToken == null) slotToken = request.getParameter(SLOT_QUERY);
        if (token != null || slotToken != null) {
            HttpMethod method;
            try { method = HttpMethod.valueOf(request.getMethod()); }
            catch (IllegalArgumentException invalid) { response.sendError(400); return; }
            var route = routes.resolve(SafeRequestPath.resolve(request).orElse(""), method).orElse(null);
            if (route == null || token != null && !pages.permits(token, route.pluginId(), route.requestOwner())
                    || slotToken != null && (slots == null || (request.getHeader(SLOT_HEADER) != null
                        ? !slots.permits(slotToken) : !slots.permits(slotToken, route.pluginId(), route.requestOwner())))) {
                response.setStatus(409); response.setContentType("application/json");
                response.setCharacterEncoding("UTF-8");
                JsonMapper.builderWithJackson2Defaults().build().writeValue(response.getWriter(),
                        top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse.of(
                                slotToken != null ? "UI_PUBLICATION_STALE" : "PAGE_PUBLICATION_STALE",
                                messages.get("error.request.param.invalid")));
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
