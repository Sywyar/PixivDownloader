package top.sywyar.pixivdownload.plugin.security;

import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import top.sywyar.pixivdownload.plugin.api.operation.*;
import top.sywyar.pixivdownload.plugin.api.web.HttpMethod;
import top.sywyar.pixivdownload.plugin.registry.route.RouteAccessRegistry;
import top.sywyar.pixivdownload.plugin.registry.web.PageContributionRegistry;
import static top.sywyar.pixivdownload.plugin.api.operation.OperationTargets.*;

/** 直接投影真实分发表和原路由 owner；不维护第二份访问策略或长期持有插件 handler。 */
@Component
public final class OperationEndpoints {
    private final ObjectProvider<RequestMappingHandlerMapping> mappings;
    private final RouteAccessRegistry routes;
    private final PageContributionRegistry pages;
    private final String epoch = UUID.randomUUID().toString();
    private top.sywyar.pixivdownload.plugin.lifecycle.request.PluginRequestLeaseRegistry requests;
    private top.sywyar.pixivdownload.plugin.web.page.WebPublicationStore publications;
    private List<Endpoint> catalog = List.of();
    private long catalogRevision = -1;
    private List<RouteAccessRegistry.RegisteredRoute> catalogRoutes = List.of();
    private String coreDeployment;
    private top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager runtime;

    @org.springframework.beans.factory.annotation.Autowired
    public void runtime(top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager value) { runtime = value; }

    @org.springframework.beans.factory.annotation.Autowired
    public void publications(top.sywyar.pixivdownload.plugin.web.page.WebPublicationStore value) { publications = value; }

    @org.springframework.beans.factory.annotation.Autowired
    public void requests(top.sywyar.pixivdownload.plugin.lifecycle.request.PluginRequestLeaseRegistry requests) { this.requests = requests; }

    public top.sywyar.pixivdownload.plugin.lifecycle.request.PluginRequestLease retain(Endpoint endpoint) {
        var owner = requestOwner(endpoint);
        if (owner == null) {
            if (!endpoint.owner().equals("core")) throw new IllegalStateException("OPERATION_ENDPOINT_UNAVAILABLE");
            return null;
        }
        if (requests == null || !owner.toString().equals(endpoint.publication())) throw new IllegalStateException("OPERATION_ENDPOINT_STALE");
        var lease = requests.prepareLease(owner).orElseThrow(() -> new IllegalStateException("OPERATION_ENDPOINT_UNAVAILABLE"));
        if (!requests.activate(lease)) { lease.close(); throw new IllegalStateException("OPERATION_ENDPOINT_UNAVAILABLE"); }
        return lease;
    }

    private top.sywyar.pixivdownload.plugin.lifecycle.request.PluginRequestOwner requestOwner(Endpoint endpoint) {
        return routes.routes().stream().filter(route -> route.pluginId().equals(endpoint.owner()))
                .map(RouteAccessRegistry.RegisteredRoute::requestOwner).filter(Objects::nonNull).distinct().findFirst().orElse(null);
    }

    public OperationEndpoints(@Qualifier("requestMappingHandlerMapping") ObjectProvider<RequestMappingHandlerMapping> mappings,
            RouteAccessRegistry routes, PageContributionRegistry pages) {
        this.mappings = mappings; this.routes = routes; this.pages = pages;
    }

    public Resolution resolve(String caller, Reference reference) {
        return resolve(caller, reference, publications == null ? Map.of() : publications.snapshot().configuration().settings().groups());
    }

    public Resolution resolve(String caller, Reference reference, Map<String, List<String>> groups) {
        List<top.sywyar.pixivdownload.plugin.api.web.page.PageInteraction.Action> actions = List.of();
        if (reference != null && reference.kind() == Kind.FORM_ACTION && publications != null) {
            var page = publications.snapshot().pages().get(reference.id());
            if (page != null) actions = top.sywyar.pixivdownload.plugin.web.page.PageActions.read(page.document());
        }
        return resolve(caller, reference, groups, actions);
    }

    public Resolution resolve(String caller, Reference reference, Map<String, List<String>> groups,
            List<top.sywyar.pixivdownload.plugin.api.web.page.PageInteraction.Action> actions) {
        if (reference == null || reference.kind() == null || reference.id() == null
                || reference.id().length() > 256 || reference.targetId() == null || reference.targetId().length() > 128
                || reference.actionId() != null && reference.actionId().length() > 128)
            return result(Status.UNDECLARED, List.of(), List.of());
        if (caller != null && !reference.id().startsWith(caller + ":"))
            return result(Status.FORBIDDEN, List.of(), List.of());
        String operation = reference.kind() == Kind.OPERATION ? reference.id() : null;
        if (reference.kind() == Kind.FORM_ACTION) {
            var relations = actions.stream().filter(action -> reference.actionId() == null ? action.id().equals(reference.targetId())
                    : action.formId().equals(reference.targetId()) && (reference.actionId().isEmpty() || action.actionId().equals(reference.actionId()))).toList();
            if (relations.size() > 1) return result(Status.AMBIGUOUS, relations.stream().map(action -> action.operation().isEmpty()
                    ? action.endpoint() : action.operation()).distinct().sorted().toList(), List.of());
            if (relations.size() == 1) {
                var action = relations.get(0);
                return resolve(null, new Reference(action.endpoint().isEmpty() ? Kind.OPERATION : Kind.ENDPOINT,
                        action.endpoint().isEmpty() ? action.operation() : action.endpoint(), ""), groups, List.of());
            }
            if (reference.actionId() != null) return result(Status.UNDECLARED, List.of(), List.of());
            var candidates = pages.pages().stream().filter(page -> page.definition().id().equals(reference.id()))
                    .flatMap(page -> page.definition().targets().stream())
                    .filter(target -> reference.targetId().isEmpty() || target.id().equals(reference.targetId())).toList();
            if (candidates.isEmpty()) return result(Status.UNDECLARED, List.of(), List.of());
            if (candidates.size() != 1) return result(Status.AMBIGUOUS,
                    candidates.stream().map(target -> target.operation()).distinct().sorted().toList(), List.of());
            operation = candidates.get(0).operation();
        }
        List<Endpoint> endpoints;
        var members = operation == null ? null : groups.get(operation);
        try { endpoints = snapshot(reference.kind() == Kind.ENDPOINT ? reference.id() : null, members == null ? operation : null); }
        catch (IllegalArgumentException invalid) { return result(Status.INCOMPATIBLE, List.of(), List.of()); }
        String selected = operation;
        var matches = endpoints.stream().filter(endpoint -> reference.kind() == Kind.ENDPOINT
                ? endpoint.id().equals(reference.id()) : members == null ? endpoint.operation().equals(selected) : members.contains(endpoint.id())).toList();
        if (members != null && matches.size() != members.size()) return result(Status.UNAVAILABLE, List.of(operation), matches);
        if (matches.isEmpty()) return result(Status.UNDECLARED, operation == null ? List.of() : List.of(operation), List.of());
        if (matches.stream().map(Endpoint::id).distinct().count() != matches.size())
            return result(Status.AMBIGUOUS, List.of(), matches);
        if (matches.stream().map(Endpoint::inputSchema).distinct().count() != 1)
            return result(Status.INCOMPATIBLE, List.of(), matches);
        return result(Status.AVAILABLE, members == null ? matches.stream().map(Endpoint::operation).distinct().sorted().toList() : List.of(operation), matches);
    }

    private Resolution result(Status status, List<String> operations, List<Endpoint> endpoints) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(endpoints.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return new Resolution(status, epoch + ":" + HexFormat.of().formatHex(digest), operations, endpoints);
        } catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }

    public List<Endpoint> catalog(String caller) {
        return snapshot(null, null).stream().filter(value -> caller == null || value.owner().equals(caller)).toList();
    }

    public Set<top.sywyar.pixivdownload.plugin.web.page.WebPublicationStore.HookTarget> targetAnchors(List<Endpoint> targets) {
        var mapping = mappings.getIfAvailable();
        if (mapping == null) throw new IllegalArgumentException("OPERATION_ENDPOINT_UNAVAILABLE");
        var selected = targets.stream().collect(java.util.stream.Collectors.toMap(Endpoint::id, value -> value));
        var handlers = targets.stream().map(Endpoint::handler).collect(java.util.stream.Collectors.toSet());
        var directory = catalog(null);
        int entries = 0;
        var anchors = new HashSet<top.sywyar.pixivdownload.plugin.web.page.WebPublicationStore.HookTarget>();
        for (var entry : mapping.getHandlerMethods().entrySet()) {
            if (!handlers.contains(entry.getValue().getBeanType().getName() + "#" + entry.getValue().getMethod().getName())) continue;
            var endpoint = materialize(mapping, entry.getKey(), entry.getValue());
            if (!endpoint.equals(selected.get(endpoint.id()))) continue;
            var anchor = new top.sywyar.pixivdownload.plugin.web.page.WebPublicationStore.HookTarget(endpoint.owner(), endpoint.handler(),
                    endpoint.routes().stream().map(route -> route.method() + ":" + route.template()).collect(java.util.stream.Collectors.toSet()),
                    endpoint.parameters(), endpoint.headers(), endpoint.consumes(), endpoint.produces(), entry.getKey().getConsumesCondition().isBodyRequired(),
                    entry.getKey().getPatternValues(), entry.getKey().getMethodsCondition().getMethods().stream().map(Enum::name)
                            .collect(java.util.stream.Collectors.toSet()), Set.of());
            var alternatives = directory.stream().filter(candidate -> !candidate.id().equals(endpoint.id()))
                    .filter(candidate -> candidate.routes().stream().anyMatch(route -> anchor.relatedTo(candidate.owner(), candidate.handler(), route.method().name(), route.template())))
                    .map(this::identity).collect(java.util.stream.Collectors.toSet());
            var captured = anchor.withAlternatives(alternatives);
            if ((entries += captured.entries()) > 8192) throw new IllegalArgumentException("PAGE_HOOK_CAPACITY");
            anchors.add(captured);
        }
        if (anchors.size() != selected.size()) throw new IllegalArgumentException("OPERATION_ENDPOINT_STALE");
        return Set.copyOf(anchors);
    }

    private String identity(Endpoint endpoint) {
        // 进程 epoch、generation 和 servingId 只管租约；持久化候选复核冻结部署内容。
        String deployment;
        if (endpoint.owner().equals("core")) deployment = coreDeployment();
        else {
            var owner = requestOwner(endpoint);
            if (runtime == null || owner == null || !owner.toString().equals(endpoint.publication()))
                throw new IllegalArgumentException("OPERATION_ENDPOINT_UNAVAILABLE");
            deployment = runtime.deploymentIdentity(owner.pluginId(), owner.generation())
                    .orElseThrow(() -> new IllegalArgumentException("OPERATION_ENDPOINT_UNAVAILABLE"));
        }
        return hash(endpoint.id() + "\n" + deployment + "\n" + endpoint.revision());
    }

    private synchronized String coreDeployment() {
        if (coreDeployment == null) {
            coreDeployment = "";
            try {
                var location = OperationEndpoints.class.getProtectionDomain().getCodeSource().getLocation();
                var connection = location.openConnection(); connection.setUseCaches(false);
                java.nio.file.Path source;
                if (connection instanceof java.net.JarURLConnection archive) {
                    try (var jar = archive.getJarFile()) {
                        String name = jar.getName(); int nested = name.indexOf("!/");
                        source = java.nio.file.Path.of(nested < 0 ? name : name.substring(0, nested));
                    }
                } else source = java.nio.file.Path.of(location.toURI());
                coreDeployment = top.sywyar.pixivdownload.plugin.runtime.artifact.DeploymentFingerprint.of(source);
            } catch (Exception unavailable) { throw new IllegalArgumentException("OPERATION_ENDPOINT_UNAVAILABLE"); }
        }
        if (coreDeployment.isEmpty()) throw new IllegalArgumentException("OPERATION_ENDPOINT_UNAVAILABLE");
        return coreDeployment;
    }

    public boolean anchoredAt(top.sywyar.pixivdownload.plugin.web.page.WebPublicationStore.HookRequirement requirement,
            Endpoint endpoint, String route, jakarta.servlet.http.HttpServletRequest request) {
        for (var anchor : requirement.targetAnchors()) {
            if (!anchor.relatedTo(endpoint.owner(), endpoint.handler(), request.getMethod(), route)) continue;
            var mapping = mappings.getIfAvailable();
            if (mapping == null) throw new IllegalArgumentException("OPERATION_ENDPOINT_UNAVAILABLE");
            var conditions = RequestMappingInfo.paths(anchor.paths().toArray(String[]::new)).options(mapping.getBuilderConfiguration())
                    .methods(anchor.methods().stream().map(org.springframework.web.bind.annotation.RequestMethod::valueOf)
                            .toArray(org.springframework.web.bind.annotation.RequestMethod[]::new))
                    .params(anchor.parameters().toArray(String[]::new)).headers(anchor.headers().toArray(String[]::new))
                    .consumes(anchor.consumes().toArray(String[]::new)).produces(anchor.produces().toArray(String[]::new)).build();
            conditions.getConsumesCondition().setBodyRequired(anchor.bodyRequired());
            boolean unchanged;
            try { unchanged = anchor.alternatives().contains(identity(endpoint)); }
            catch (IllegalArgumentException unavailable) { unchanged = false; }
            var previous = conditions.getMatchingCondition(request);
            // 新增别名仍受原 handler 的保护；已存在的其它映射只按原声明比较。
            if (previous == null && !unchanged) previous = conditions.mutate().paths(route).build().getMatchingCondition(request);
            if (previous == null) continue;
            if (unchanged && request.getAttribute(top.sywyar.pixivdownload.plugin.web.mapping.PluginAwareRequestMappingHandlerMapping.MATCH_ATTRIBUTE)
                    instanceof RequestMappingInfo selected) {
                var matched = selected.getMatchingCondition(request);
                // 只有发布时已存在且身份未变的候选，才能凭 Spring 的实际优先级排除旧保护。
                if (matched != null && matched.compareTo(previous, request) < 0) continue;
            }
            return true;
        }
        return false;
    }

    public Endpoint matched(jakarta.servlet.http.HttpServletRequest request, HandlerMethod handler) {
        var mapping = mappings.getIfAvailable();
        if (mapping == null) throw new IllegalArgumentException("OPERATION_ENDPOINT_UNAVAILABLE");
        if (mapping instanceof top.sywyar.pixivdownload.plugin.web.mapping.PluginAwareRequestMappingHandlerMapping aware) {
            if (aware.mappingsFor(handler.getMethod()).isEmpty()) return null;
            if (!(request.getAttribute(top.sywyar.pixivdownload.plugin.web.mapping.PluginAwareRequestMappingHandlerMapping.MATCH_ATTRIBUTE)
                    instanceof RequestMappingInfo selected)) throw new IllegalArgumentException("OPERATION_ENDPOINT_UNDECLARED");
            return materialize(mapping, selected, handler);
        }
        // 使用 MVC 已选中的方法；不按请求自报的接口身份重新分派。
        var candidates = mapping.getHandlerMethods().entrySet().stream().filter(entry -> entry.getValue().equals(handler)
                    || entry.getValue().getMethod().equals(handler.getMethod()) && entry.getValue().getBeanType().equals(handler.getBeanType()))
                    .map(Map.Entry::getKey).toList();
        var matches = candidates.stream().filter(candidate -> candidate.getMatchingCondition(request) != null).toList();
        if (matches.size() != 1) throw new IllegalArgumentException("OPERATION_ENDPOINT_AMBIGUOUS");
        request.setAttribute(top.sywyar.pixivdownload.plugin.web.mapping.PluginAwareRequestMappingHandlerMapping.MATCH_ATTRIBUTE, matches.get(0));
        return materialize(mapping, matches.get(0), handler);
    }

    private synchronized List<Endpoint> snapshot(String id, String operation) {
        var mapping = mappings.getIfAvailable();
        if (mapping == null) return List.of();
        var aware = mapping instanceof top.sywyar.pixivdownload.plugin.web.mapping.PluginAwareRequestMappingHandlerMapping value ? value : null;
        long revision = aware == null ? -1 : aware.revision();
        var routeSnapshot = routes.routes();
        if (aware != null && revision == catalogRevision && routeSnapshot == catalogRoutes) return select(catalog, id, operation);
        var values = new ArrayList<Endpoint>();
        var counts = new HashMap<String, Integer>();
        for (var entry : mapping.getHandlerMethods().entrySet()) {
            Endpoint endpoint = materialize(mapping, entry.getKey(), entry.getValue());
            if (counts.merge(endpoint.owner(), 1, Integer::sum) > 1024 || values.size() >= 4096)
                throw new IllegalArgumentException("OPERATION_ENDPOINT_CAPACITY");
            values.add(endpoint);
        }
        var result = values.stream().sorted(Comparator.comparing(Endpoint::id)).toList();
        if (aware != null && aware.revision() == revision && routes.routes() == routeSnapshot) {
            catalog = result; catalogRevision = revision; catalogRoutes = routeSnapshot;
        }
        return select(result, id, operation);
    }

    private static List<Endpoint> select(List<Endpoint> values, String id, String operation) {
        return id == null && operation == null ? values : values.stream().filter(value -> (id == null || id.equals(value.id()))
                && (operation == null || operation.equals(value.operation()))).toList();
    }

    private Endpoint materialize(RequestMappingHandlerMapping registry, RequestMappingInfo mapping, HandlerMethod handler) {
        var declaration = handler.getMethodAnnotation(OperationEndpoint.class);
        String owner = registry instanceof top.sywyar.pixivdownload.plugin.web.mapping.PluginAwareRequestMappingHandlerMapping aware
                ? aware.ownerOf(mapping) : "core";
        String source = handler.getBeanType().getName() + "#" + handler.getMethod().getName();
        if (source.length() > 512 || mapping.toString().length() > 16384) throw new IllegalArgumentException("OPERATION_ENDPOINT_CAPACITY");
        String id = owner + ":http-" + hash(mapping.toString()).substring(0, 32);
        String operation = declaration == null ? id : declaration.operation();
        if (!id.startsWith(owner + ":") || !operation.startsWith(owner + ":")) throw new IllegalArgumentException("OPERATION_ENDPOINT_OWNER");
        var declaredRoutes = new ArrayList<Route>();
        var methods = new LinkedHashSet<>(mapping.getMethodsCondition().getMethods().isEmpty() ? Arrays.stream(HttpMethod.values()).toList()
                : mapping.getMethodsCondition().getMethods().stream().filter(method -> !method.name().equals("TRACE"))
                        .map(method -> HttpMethod.valueOf(method.name())).toList());
        if (methods.contains(HttpMethod.GET)) methods.add(HttpMethod.HEAD);
        for (String path : mapping.getPatternValues()) for (var method : methods) {
            if (path.length() > 1024 || declaredRoutes.size() >= 64) throw new IllegalArgumentException("OPERATION_ENDPOINT_CAPACITY");
            declaredRoutes.add(new Route(method, path));
        }
        var publications = routes.routes().stream().filter(route -> route.pluginId().equals(owner))
                .map(route -> route.requestOwner() == null ? epoch : route.requestOwner().toString()).distinct().toList();
        String publication = publications.size() == 1 ? publications.get(0) : "unavailable";
        String empty = "{\"type\":\"object\",\"properties\":{},\"required\":[],\"additionalProperties\":false}";
        return new Endpoint(id, operation, owner, publication, hash(mapping + "\n" + handler.getMethod().toGenericString()),
                declaredRoutes.stream().sorted(Comparator.comparing(route -> route.method() + route.template())).toList(),
                Set.of(OperationHook.Phase.values()), empty, empty,
                mapping.getParamsCondition().getExpressions().stream().map(Object::toString).sorted().toList(),
                mapping.getHeadersCondition().getExpressions().stream().map(Object::toString).sorted().toList(),
                mapping.getConsumesCondition().getExpressions().stream().map(Object::toString).sorted().toList(),
                mapping.getProducesCondition().getExpressions().stream().map(Object::toString).sorted().toList(), source, shape(handler));
    }

    private static String shape(HandlerMethod handler) {
        Class<?> type = handler.getReturnType().getParameterType();
        if (org.springframework.http.HttpEntity.class.isAssignableFrom(type)) type = org.springframework.core.ResolvableType
                .forMethodParameter(handler.getReturnType()).as(org.springframework.http.HttpEntity.class).getGeneric(0).resolve(Object.class);
        if (org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.class.isAssignableFrom(type)
                || org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody.class.isAssignableFrom(type)
                || java.io.InputStream.class.isAssignableFrom(type) || org.springframework.core.io.Resource.class.isAssignableFrom(type)) return "STREAM";
        return java.util.concurrent.Callable.class.isAssignableFrom(type) || java.util.concurrent.CompletionStage.class.isAssignableFrom(type)
                || org.springframework.web.context.request.async.DeferredResult.class.isAssignableFrom(type)
                || org.springframework.web.context.request.async.WebAsyncTask.class.isAssignableFrom(type)
                || org.springframework.core.ReactiveAdapterRegistry.getSharedInstance().getAdapter(type) != null ? "ASYNC" : "SYNC";
    }

    private static String hash(String value) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
}
