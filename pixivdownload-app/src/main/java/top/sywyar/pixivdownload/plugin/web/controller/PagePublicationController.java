package top.sywyar.pixivdownload.plugin.web.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.sywyar.pixivdownload.plugin.registry.web.PageContributionRegistry;
import top.sywyar.pixivdownload.plugin.registry.route.RouteAccessRegistry;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentityResolver;
import top.sywyar.pixivdownload.plugin.api.web.HttpMethod;
import top.sywyar.pixivdownload.plugin.api.web.Audience;

/** 匿名请求只读公开页面；私有投影仍要求真实管理员身份和原页面的常规访问策略。 */
@RestController
public final class PagePublicationController {
    private final PageContributionRegistry registry;
    private final top.sywyar.pixivdownload.plugin.web.page.PagePublicationRequest publications;
    private final RequestOwnerIdentityResolver identities;
    private final RouteAccessRegistry routes;
    private final ObjectMapper json = new ObjectMapper();
    public PagePublicationController(PageContributionRegistry registry,
            top.sywyar.pixivdownload.plugin.web.page.PagePublicationRequest publications,
            RequestOwnerIdentityResolver identities, RouteAccessRegistry routes) {
        this.registry = registry; this.publications = publications; this.identities = identities; this.routes = routes;
    }
    @GetMapping("/api/web/page-publications/{page}")
    public ResponseEntity<JsonNode> published(@PathVariable String page, jakarta.servlet.http.HttpServletRequest request) throws java.io.IOException {
        var definition = registry.pages().stream().filter(p -> p.definition().id().equals(page)).findFirst();
        if (definition.isEmpty()) return ResponseEntity.notFound().build();
        var declared = definition.get().definition();
        if (!declared.publicPage() && (!identities.isAdminAuthenticated(request)
                || routes.resolve(declared.path(), HttpMethod.GET).filter(route ->
                    declared.id().startsWith(route.pluginId() + ":")
                    && route.route().accessPolicy().supportsUiVisibility()
                    && route.route().accessPolicy().isVisibleTo(Audience.ADMIN)).isEmpty()))
            return ResponseEntity.notFound().build();
        java.util.Optional<top.sywyar.pixivdownload.plugin.api.web.page.PagePublications.Published> value;
        try {
            String revision = publications.revision(request);
            String requested = request.getParameter("revision");
            if (requested != null && !requested.equals(revision)) return ResponseEntity.status(409).build();
            value = publications.read(page, request);
        }
        catch (RuntimeException unavailable) { return ResponseEntity.status(503).build(); }
        JsonNode projection = value.isPresent() ? json.readTree(value.get().projection()) : null;
        if (projection == null || projection.path("defaultLayout").asBoolean())
            return ResponseEntity.status(404).cacheControl(org.springframework.http.CacheControl.noStore())
                .header("X-Pixiv-Page-Revision", publications.revision(request)).build();
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .header("X-Pixiv-Page-Revision", publications.revision(request))
                .body(projection);
    }
}
