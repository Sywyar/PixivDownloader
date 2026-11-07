package top.sywyar.pixivdownload.plugin.web.controller;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.sywyar.pixivdownload.plugin.registry.web.PageContributionRegistry;
import top.sywyar.pixivdownload.plugin.registry.route.RouteAccessRegistry;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentityResolver;
import top.sywyar.pixivdownload.plugin.api.web.HttpMethod;
import top.sywyar.pixivdownload.plugin.api.web.Audience;
import top.sywyar.pixivdownload.plugin.api.operation.*;
import top.sywyar.pixivdownload.plugin.api.web.page.EditablePage;
import top.sywyar.pixivdownload.plugin.security.OperationEndpoints;
import top.sywyar.pixivdownload.plugin.web.page.WebPublicationStore;
import java.util.*;

/** 匿名请求只读公开页面；私有投影仍要求真实管理员身份和原页面的常规访问策略。 */
@RestController
public final class PagePublicationController {
    private final PageContributionRegistry registry;
    private final top.sywyar.pixivdownload.plugin.web.page.PagePublicationRequest publications;
    private final RequestOwnerIdentityResolver identities;
    private final RouteAccessRegistry routes;
    private final OperationEndpoints endpoints;
    private final WebPublicationStore store;
    private final ObjectMapper json = JsonMapper.builderWithJackson2Defaults().build();
    public PagePublicationController(PageContributionRegistry registry,
            top.sywyar.pixivdownload.plugin.web.page.PagePublicationRequest publications,
            RequestOwnerIdentityResolver identities, RouteAccessRegistry routes, OperationEndpoints endpoints, WebPublicationStore store) {
        this.registry = registry; this.publications = publications; this.identities = identities; this.routes = routes;
        this.endpoints = endpoints; this.store = store;
    }
    @GetMapping("/api/web/page-publications/{page}")
    public ResponseEntity<JsonNode> published(@PathVariable String page, jakarta.servlet.http.HttpServletRequest request) throws java.io.IOException {
        if (visible(page, request) == null) return ResponseEntity.notFound().build();
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

    private EditablePage visible(String page, jakarta.servlet.http.HttpServletRequest request) {
        var definition = registry.pages().stream().filter(p -> p.definition().id().equals(page)).findFirst();
        if (definition.isEmpty()) return null;
        var declared = definition.get().definition();
        if (!declared.publicPage() && (!identities.isAdminAuthenticated(request)
                || routes.resolve(declared.path(), HttpMethod.GET).filter(route ->
                    declared.id().startsWith(route.pluginId() + ":")
                    && route.route().accessPolicy().supportsUiVisibility()
                    && route.route().accessPolicy().isVisibleTo(Audience.ADMIN)).isEmpty()))
            return null;
        return declared;
    }

    public record HttpAction(String target, String operation, String formId, String actionId, List<HttpRequestTarget> requests) { }
    public record HttpRequestTarget(String endpoint, String method, String url, Map<String, String> headers, List<String> parameters,
            List<String> headerConditions, List<String> consumes, List<String> produces, List<OperationBinding.Field> fields) { }
    public record HttpActions(String revision, List<HttpAction> targets) { }

    @GetMapping("/api/web/page-publications/{page}/targets")
    public ResponseEntity<HttpActions> targets(@PathVariable String page, jakarta.servlet.http.HttpServletRequest request) {
        var definition = visible(page, request);
        if (definition == null) return ResponseEntity.notFound().build();
        try {
            String revision = publications.revision(request);
            String requested = request.getParameter("revision");
            if (requested != null && !requested.equals(revision)) return ResponseEntity.status(409).build();
            var snapshot = store.snapshot();
            if (!revision.equals(snapshot.identity())) return ResponseEntity.status(409).build();
            var actions = new ArrayList<HttpAction>();
            var pageState = snapshot.pages().get(page);
            var relations = pageState == null ? List.<top.sywyar.pixivdownload.plugin.api.web.page.PageInteraction.Action>of()
                    : top.sywyar.pixivdownload.plugin.web.page.PageActions.read(pageState.document());
            var targets = new LinkedHashMap<String, String>();
            definition.targets().forEach(target -> targets.put(target.id(), target.operation()));
            relations.forEach(action -> targets.put(action.id(), action.operation().isEmpty() ? action.endpoint() : action.operation()));
            for (var target : targets.entrySet()) {
                var resolution = endpoints.resolve(null, new OperationTargets.Reference(OperationTargets.Kind.FORM_ACTION, page, target.getKey()));
                if (resolution.status() != OperationTargets.Status.AVAILABLE) return ResponseEntity.status(503).build();
                var requests = new ArrayList<HttpRequestTarget>();
                for (var endpoint : resolution.endpoints()) {
                    var fields = snapshot.hooks(endpoint.operation(), endpoint.id()).stream()
                            .filter(binding -> binding.binding().phase() == OperationHook.Phase.BEFORE_INVOKE)
                            .flatMap(binding -> binding.binding().fields().stream())
                            .filter(field -> field.source() != OperationBinding.Source.RESULT).distinct().toList();
                    var headers = new TreeMap<String, String>();
                    for (String condition : endpoint.headers()) {
                        int equals = condition.indexOf('=');
                        if (equals > 0 && condition.charAt(equals - 1) != '!') headers.put(condition.substring(0, equals), condition.substring(equals + 1));
                    }
                    if (endpoint.consumes().size() == 1 && !endpoint.consumes().get(0).contains("*")) headers.put("Content-Type", endpoint.consumes().get(0));
                    for (var route : endpoint.routes()) requests.add(new HttpRequestTarget(endpoint.id(), route.method().name(), route.template(), Map.copyOf(headers),
                            endpoint.parameters(), endpoint.headers(), endpoint.consumes(), endpoint.produces(), fields));
                }
                var relation = relations.stream().filter(action -> action.id().equals(target.getKey())).findFirst().orElse(null);
                actions.add(new HttpAction(target.getKey(), target.getValue(), relation == null ? "" : relation.formId(),
                        relation == null ? "" : relation.actionId(), List.copyOf(requests)));
            }
            return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                    .header("X-Pixiv-Page-Revision", revision).body(new HttpActions(revision, List.copyOf(actions)));
        }
        catch (RuntimeException unavailable) { return ResponseEntity.status(503).build(); }
    }
}
