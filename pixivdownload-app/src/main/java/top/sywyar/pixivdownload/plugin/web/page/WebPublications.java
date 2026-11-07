package top.sywyar.pixivdownload.plugin.web.page;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import top.sywyar.pixivdownload.plugin.api.security.OperationVerification;
import top.sywyar.pixivdownload.plugin.api.service.ServiceKey;
import top.sywyar.pixivdownload.plugin.api.service.ServiceProvider;
import top.sywyar.pixivdownload.plugin.api.web.page.*;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.OperationVerificationCapabilityAdapter;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityInvocationRegistry;
import top.sywyar.pixivdownload.plugin.registry.web.PageContributionRegistry;
import top.sywyar.pixivdownload.plugin.security.OperationAdmissions;
import top.sywyar.pixivdownload.plugin.security.OperationPolicies;
import top.sywyar.pixivdownload.plugin.security.OperationRequirement;
import top.sywyar.pixivdownload.plugin.web.remote.RemoteValues;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import static top.sywyar.pixivdownload.plugin.web.page.WebPublicationStore.*;

/** 正式发布 owner 不依赖编辑器的 Bean、文件路径或数据库。 */
@Component
public final class WebPublications implements PagePublications {
    private final WebPublicationStore store;
    private final PageContributionRegistry catalog;
    private final ExternalCapabilityInvocationRegistry registry;
    private final OperationAdmissions admissions;
    private final Map<String, List<String>> required;
    private HookPublications hooks;
    @org.springframework.beans.factory.annotation.Autowired
    public void hooks(HookPublications hooks) { this.hooks = hooks; }
    public WebPublications(WebPublicationStore store, PageContributionRegistry catalog,
            ExternalCapabilityInvocationRegistry registry, OperationAdmissions admissions, List<OperationRequirement> required) {
        this.store = store; this.catalog = catalog; this.registry = registry; this.admissions = admissions;
        this.required = OperationRequirement.collect(required);
    }

    @Override public long revision() { return store.snapshot().revision(); }

    @Override public Optional<Published> read(String id) {
        Snapshot snapshot = store.snapshot();
        Page page = snapshot.pages().get(id);
        return page == null ? Optional.empty() : Optional.of(new Published(snapshot.revision(), page.document(),
                page.projection(), page.previousDocument()));
    }

    /** 正式呈现另外复核活动目录；管理读取仍可取回缺席组件的存档以修复草稿。 */
    public Optional<Published> render(String id, Snapshot snapshot) {
        Page page = snapshot.pages().get(id);
        return page == null ? Optional.empty() : Optional.of(new Published(snapshot.revision(), page.document(),
                projection(id, page, snapshot.identity()).toString(), page.previousDocument()));
    }

    public ObjectNode projection(String id, Page stored, String revision) {
        ObjectNode value = object(stored.projection());
        var owner = catalog.pages().stream().filter(page -> page.definition().id().equals(id)).findFirst();
        if (owner.isEmpty() || !contract(owner.get().definition()).equals(value.path("ownerContract").asString()))
            throw invalid("PAGE_OWNER_CHANGED");
        value.put("revision", revision);
        value.put("publication", owner.map(PageCatalog.AvailablePage::publication).orElse(""));
        Map<String, PageCatalog.AvailableComponent> components = components();
        resolve(value.path("body"), components);
        return value;
    }

    private static void resolve(JsonNode node, Map<String, PageCatalog.AvailableComponent> available) {
        if (node.has("componentType")) {
            var component = available.get(node.path("componentType").asString());
            if (component == null || !contract(component.definition()).equals(node.path("componentContract").asString()))
                throw invalid("PAGE_COMPONENT_UNAVAILABLE");
            else ((ObjectNode) node).put("publication", component.publication());
        }
        node.path("children").forEach(child -> resolve(child, available));
    }

    private static String contract(Object definition) {
        return hash(JSON.valueToTree(definition).toString().getBytes(StandardCharsets.UTF_8));
    }

    @Override public Plan prepare(long revision, List<Change> changes) {
        return compile(revision, changes, (plan, ignored) -> plan);
    }

    @Override public long publish(Plan requested, List<Permission> approvals) {
        if (registry.serviceInvocationOnCurrentThread()) throw invalid("PAGE_PUBLICATION_REENTRANT");
        return compile(requested.revision(), requested.changes(), (plan, next) -> {
            if (!plan.equals(requested)) throw invalid("PAGE_PUBLICATION_STALE");
            if (approvals.size() != new HashSet<>(approvals).size()
                    || !new HashSet<>(approvals).equals(new HashSet<>(plan.permissions())))
                throw invalid("PAGE_RESOURCE_APPROVAL_REQUIRED");
            Set<String> affected = new HashSet<>();
            plan.impacts().forEach(impact -> affected.add(impact.operation()));
            admissions.closeAndDrain(affected, Duration.ofSeconds(10),
                    top.sywyar.pixivdownload.plugin.security.HttpOperationHooks.publicationAdmission());
            try {
                if (!prepare(plan.revision(), plan.changes()).equals(plan)) throw invalid("PAGE_PUBLICATION_STALE");
                store.commit(plan.revision(), next);
                org.slf4j.LoggerFactory.getLogger(getClass()).info(top.sywyar.pixivdownload.i18n.MessageBundles.getForLog("web-publication.log.committed",
                        next.identity(), plan.changes().size(), affected.size()));
                return next.revision();
            } finally {
                // commit 后的异常也先恢复权威记录；恢复失败则保持封闭。
                store.snapshot();
                admissions.reopen(affected);
            }
        });
    }

    @FunctionalInterface private interface Compiled<T> { T run(Plan plan, Snapshot next); }
    private <T> T compile(long expected, List<Change> changes, Compiled<T> action) {
        if (changes == null || changes.isEmpty() || changes.size() > 128) throw invalid("PAGE_DOCUMENT_BUDGET");
        changes.forEach(change -> object(change.document()));
        if (hooks == null) return compile(expected, changes, action, new HookPublications.Prepared(Map.of(), List.of(), List.of()));
        try { return hooks.compile(changes, prepared -> compile(expected, changes, action, prepared)); }
        catch (IOException failure) { throw new IllegalStateException("PAGE_PUBLICATION_FAILED", failure); }
    }
    private <T> T compile(long expected, List<Change> changes, Compiled<T> action, HookPublications.Prepared hooks) {
        Snapshot before = store.snapshot();
        if (before.revision() != expected) throw invalid("PAGE_REVISION_CONFLICT");
        if (changes.isEmpty() || changes.size() > 128) throw invalid("PAGE_DOCUMENT_BUDGET");
        Map<String, PageCatalog.AvailableComponent> components = components();
        Map<String, PageCatalog.AvailablePage> pages = new TreeMap<>();
        catalog.pages().forEach(page -> pages.put(page.definition().id(), page));
        Set<String> changed = new HashSet<>();
        Map<String, ObjectNode> projections = new LinkedHashMap<>();
        Map<String, List<Binding>> bindings = new LinkedHashMap<>();
        Set<String> policies = new TreeSet<>();
        Set<String> publications = new HashSet<>();
        for (Change change : changes) {
            if (!changed.add(change.page())) throw invalid("PAGE_ID_CONFLICT");
            var page = pages.get(change.page());
            if (page == null) throw invalid("PAGE_OWNER_UNAVAILABLE");
            publications.add(page.publication());
            var document = object(change.document());
            var definition = PageActions.apply(page.definition(), document, catalog);
            ObjectNode view = object(change.projection());
            if (!view.path("pageId").asString().equals(change.page()) || view.path("title").asString().length() > 256)
                throw invalid("PAGE_DOCUMENT_INVALID");
            List<Binding> found = new ArrayList<>();
            var regions = new HashSet<String>();
            inspect(view.path("body"), definition, components, "", regions, new HashSet<>(), new HashMap<>(), found, publications, new int[1], 0);
            if (!regions.equals(page.definition().regions().stream().map(PageRegion::id).collect(java.util.stream.Collectors.toSet())))
                throw invalid("PAGE_REQUIRED_REGION");
            PageProjection.validate(view);
            for (var target : page.definition().targets()) for (String policy : required.getOrDefault(target.operation(), List.of()))
                found.add(new Binding(policy, null, target));
            found.removeIf(binding -> hooks.requirements().getOrDefault(change.page(), List.of()).stream()
                    .anyMatch(hook -> (hook.binding().endpoint().isEmpty() && hook.operation().equals(binding.target().operation())
                            || hook.binding().endpoint().equals(binding.target().operation())) && hook.binding().hook().equals(binding.policy())
                            && hook.binding().phase() == top.sywyar.pixivdownload.plugin.api.operation.OperationHook.Phase.BEFORE_INVOKE));
            found.forEach(binding -> policies.add(binding.policy()));
            view.remove(List.of("publication", "previewModuleUrl", "revision"));
            view.put("namespace", page.definition().namespace());
            view.put("ownerContract", contract(page.definition()));
            view.set("targets", JSON.valueToTree(definition.targets()));
            view.remove("actions");
            if (document.has("actions")) view.set("actions", JSON.valueToTree(PageActions.read(document)));
            view.set("stylesheets", JSON.valueToTree(page.definition().stylesheets()));
            projections.put(change.page(), view); bindings.put(change.page(), found);
        }
        List<ServiceKey<OperationVerification>> keys = policies.stream().map(OperationVerificationCapabilityAdapter::key).toList();
        try {
            return registry.withServices(null, OperationVerification.IMPORT, keys, catalog.owners(publications), providers -> {
                Map<String, ServiceProvider<OperationVerification>> selected = new HashMap<>();
                for (int i = 0; i < keys.size(); i++) selected.put(new ArrayList<>(policies).get(i), providers.get(i));
                Map<String, Page> next = new TreeMap<>(before.pages());
                List<Permission> permissions = new ArrayList<>(hooks.permissions());
                List<String> identities = new ArrayList<>(publications.stream().sorted().toList());
                identities.addAll(hooks.identities());
                providers.forEach(provider -> identities.add(provider.publication().toString()));
                for (Change change : changes) {
                    List<Requirement> requirements = new ArrayList<>();
                    List<Permission> resources = new ArrayList<>();
                    for (var hook : hooks.requirements().getOrDefault(change.page(), List.of()))
                        for (var profile : hook.definition().resources()) resources.add(new Permission(hook.provider(), hook.binding().hook(), profile));
                    identities.add(pages.get(change.page()).publication());
                    for (Binding binding : bindings.get(change.page())) {
                        var provider = selected.get(binding.policy());
                        var policy = OperationPolicies.validate(provider.service().policy());
                        if (!binding.policy().equals(provider.service().policyId())) throw invalid("PAGE_POLICY_MISMATCH");
                        if (binding.protocol() != null && !OperationPolicies.compatible(binding.protocol(), policy.protocol())
                                || binding.target().protocols().stream().noneMatch(protocol -> OperationPolicies.compatible(protocol, policy.protocol())))
                            throw invalid("PAGE_PROTOCOL_MISMATCH");
                        if (!policy.contextSchema().isEmpty() && !objectSchema(policy.contextSchema()).equals(objectSchema(binding.target().contextSchema())))
                            throw invalid("PAGE_CONTEXT_MISMATCH");
                        requirements.add(new Requirement(binding.target().operation(), binding.target().contextSchema(), binding.policy(),
                                provider.publication().pluginId(), provider.publication().packageId(), policy));
                        for (var profile : policy.resources()) resources.add(new Permission(provider.publication().pluginId(), binding.policy(), profile));
                    }
                    requirements = requirements.stream().distinct().sorted(Comparator.comparing(Requirement::policy)).toList();
                    resources = resources.stream().distinct().toList(); permissions.addAll(resources);
                    Page old = before.pages().get(change.page());
                    next.put(change.page(), new Page(change.document(), projections.get(change.page()).toString(),
                            old == null ? "" : old.document().equals(change.document()) ? old.previousDocument() : old.document(),
                            requirements, resources, hooks.requirements().getOrDefault(change.page(), List.of())));
                }
                List<Impact> impacts = impacts(before.pages(), next, changed);
                validateHookConflicts(next, changed, before.configuration().settings().groups());
                impacts = impacts.stream().map(impact -> new Impact(impact.operation(),
                        hookImpacts(impact.before(), before.pages(), impact.operation()),
                        hookImpacts(impact.after(), next, impact.operation()), impact.pages())).toList();
                Snapshot candidate = new Snapshot(Math.addExact(expected, 1), before.epoch(), next, before.configuration());
                if (this.hooks != null) this.hooks.validate(candidate);
                String fingerprint = hash(JSON.writeValueAsBytes(List.of(candidate, identities)));
                var hookChanges = changes.stream().map(change -> new HookChange(change.page(),
                        hookAccess(before.pages().get(change.page())), hookAccess(next.get(change.page())))).toList();
                Plan plan = new Plan(expected, fingerprint, changes, impacts, permissions.stream().distinct().toList(), hookChanges);
                return action.run(plan, candidate);
            });
        } catch (JacksonException | IOException failure) { throw new IllegalStateException("PAGE_PUBLICATION_FAILED", failure); }
    }

    private record Binding(String policy, PageInteraction protocol, PageInteraction.Target target) { }
    private static List<HookAccess> hookAccess(Page page) {
        if (page == null) return List.of();
        return page.hooks().stream().map(requirement -> {
            var binding = requirement.binding();
            return new HookAccess(requirement.provider(), binding.hook(), requirement.operation(), binding.endpoint(),
                    binding.phase(), binding.order(), binding.completion(), binding.fields());
        }).toList();
    }
    private static boolean targets(HookRequirement hook, String reference) {
        return hook.operation().equals(reference) || hook.binding().endpoint().equals(reference);
    }
    private static List<String> hookImpacts(List<String> verification, Map<String, Page> pages, String operation) {
        return java.util.stream.Stream.concat(verification.stream(), pages.values().stream().flatMap(page -> page.hooks().stream())
                .filter(hook -> targets(hook, operation)).map(hook -> hook.binding().hook() + "@" + hook.binding().phase()
                        + ":" + (hook.binding().endpoint().isEmpty() ? operation : hook.binding().endpoint()))).distinct().sorted().toList();
    }
    private void validateHookConflicts(Map<String, Page> pages, Set<String> changed, Map<String, List<String>> groups) {
        var endpoints = new HashMap<String, top.sywyar.pixivdownload.plugin.api.operation.OperationTargets.Endpoint>();
        if (hooks != null) hooks.targets().forEach(endpoint -> endpoints.put(endpoint.id(), endpoint));
        var expected = new TreeMap<String, List<HookRequirement>>();
        var conflicts = new TreeSet<String>();
        for (String id : changed) for (String target : coveredTargets(pages.get(id), endpoints.values(), groups)) {
            var requirements = effectiveHooks(pages.get(id), target, endpoints.get(target), groups);
            var previous = expected.putIfAbsent(target, requirements);
            if (previous != null && !previous.equals(requirements)) throw invalid("PAGE_OPERATION_CONFLICT");
        }
        pages.forEach((id, page) -> {
            if (changed.contains(id)) return;
            for (String target : coveredTargets(page, endpoints.values(), groups))
                if (expected.containsKey(target) && !expected.get(target).equals(effectiveHooks(page, target, endpoints.get(target), groups))) conflicts.add(id);
        });
        if (!conflicts.isEmpty()) throw new PagePublications.Conflict(List.copyOf(conflicts));
    }

    private static Set<String> coveredTargets(Page page, Collection<top.sywyar.pixivdownload.plugin.api.operation.OperationTargets.Endpoint> endpoints,
            Map<String, List<String>> groups) {
        var covered = new HashSet<String>();
        var actions = PageActions.read(page.document());
        for (var target : object(page.projection()).path("targets")) {
            String reference = target.path("operation").asString();
            boolean single = actions.stream().anyMatch(action -> action.id().equals(target.path("id").asString()) && !action.endpoint().isEmpty());
            boolean resolved = false;
            for (var endpoint : endpoints) if (single ? endpoint.id().equals(reference)
                    : endpoint.operation().equals(reference) || groups.getOrDefault(reference, List.of()).contains(endpoint.id())) {
                covered.add(endpoint.id()); resolved = true;
            }
            // 无 HTTP 映射的操作仍按原身份检查，不因提供者缺席丢失正式要求。
            if (!resolved) covered.add(reference);
        }
        return covered;
    }

    private static List<HookRequirement> effectiveHooks(Page page, String reference,
            top.sywyar.pixivdownload.plugin.api.operation.OperationTargets.Endpoint endpoint, Map<String, List<String>> groups) {
        return page.hooks().stream().filter(hook -> endpoint == null ? targets(hook, reference) : hook.matches(endpoint.operation(), endpoint.id(), groups))
                .sorted(Comparator.comparing(hook -> hook.binding().id())).toList();
    }
    private static void inspect(JsonNode node, EditablePage page, Map<String, PageCatalog.AvailableComponent> components,
            String region, Set<String> regions, Set<String> instances, Map<String, Integer> counts, List<Binding> bindings,
            Set<String> publications, int[] budget, int depth) {
        if (!node.isObject() || ++budget[0] > 512 || depth > 24) throw invalid("PAGE_DOCUMENT_BUDGET");
        ((ObjectNode) node).remove("publication");
        if (node.path("type").asString().equals("page-region")) {
            String parent = region;
            region = node.path("region").asString();
            String current = region;
            var definition = page.regions().stream().filter(value -> value.id().equals(current) && value.parent().equals(parent))
                    .findFirst().orElseThrow(() -> invalid("PAGE_REGION_PARENT"));
            if (!regions.add(region)) throw invalid("PAGE_REGION_DUPLICATE");
            ((ObjectNode) node).put("container", definition.container()).put("previewHidden", definition.previewHidden()).put("labelKey", definition.labelKey());
        }
        if (node.path("type").asString().equals("page-component")) {
            String instance = node.path("instanceId").asString();
            if (!instance.matches("[A-Za-z0-9_-]{1,64}") || !instances.add(instance)) throw invalid("PAGE_INSTANCE_INVALID");
            var available = components.get(node.path("componentType").asString());
            if (available == null || available.definition().version() != node.path("configVersion").asInt())
                throw invalid("PAGE_COMPONENT_UNAVAILABLE");
            PageComponent component = available.definition();
            publications.add(available.publication());
            if (page.publicPage() && !component.publicRenderable()) throw invalid("PAGE_COMPONENT_PRIVATE");
            RemoteValues.validateConfiguration(component.schema(), node.path("configuration").toString());
            ObjectNode out = (ObjectNode) node;
            out.put("componentContract", contract(component));
            out.put("moduleUrl", component.moduleUrl()).put("namespace", component.namespace());
            out.set("inputs", JSON.valueToTree(component.inputs())); out.set("outputs", JSON.valueToTree(component.outputs()));
            out.remove(List.of("unavailable", "interaction", "bindingStatus"));
            if (component.participation() == null) {
                if (node.has("target")) throw invalid("PAGE_BINDING_INVALID");
            } else {
                String scope = region;
                List<PageInteraction.Target> targets = List.of();
                while (!scope.isEmpty()) {
                    String current = scope;
                    targets = page.targets().stream().filter(target -> target.region().equals(current)).toList();
                    if (!targets.isEmpty()) break;
                    scope = page.regions().stream().filter(value -> value.id().equals(current)).findFirst().orElseThrow().parent();
                }
                var target = targets.stream().filter(value -> value.id().equals(node.path("target").asString())
                        && value.protocols().stream().anyMatch(protocol -> OperationPolicies.compatible(protocol, component.participation().protocol())))
                        .findFirst().orElseThrow(() -> invalid("PAGE_BINDING_INVALID"));
                if (counts.merge(target.id(), 1, Integer::sum) > target.maximumParticipants()) throw invalid("PAGE_BINDING_CAPACITY");
                out.set("interaction", JSON.valueToTree(component.participation().protocol()));
                out.put("policy", component.participation().policy()).put("operation", target.operation());
                if (bindings.stream().anyMatch(binding -> binding.target().id().equals(target.id())
                        && binding.policy().equals(component.participation().policy()))) throw invalid("PAGE_BINDING_DUPLICATE");
                bindings.add(new Binding(component.participation().policy(), component.participation().protocol(), target));
            }
        }
        for (JsonNode child : node.path("children")) inspect(child, page, components, region, regions, instances, counts, bindings, publications, budget, depth + 1);
    }

    private static List<Impact> impacts(Map<String, Page> before, Map<String, Page> after, Set<String> changed) {
        Map<String, List<Requirement>> requirements = new TreeMap<>();
        Map<String, List<String>> references = new TreeMap<>();
        Map<String, Map<String, List<Requirement>>> byOperation = new TreeMap<>();
        for (var page : after.entrySet()) {
            var grouped = new TreeMap<String, List<Requirement>>();
            object(page.getValue().projection()).path("targets").forEach(target -> grouped.put(target.path("operation").asString(), new ArrayList<>()));
            page.getValue().requirements().forEach(value -> grouped.computeIfAbsent(value.operation(), ignored -> new ArrayList<>()).add(value));
            grouped.forEach((operation, values) -> {
                byOperation.computeIfAbsent(operation, ignored -> new TreeMap<>()).put(page.getKey(), values);
                references.computeIfAbsent(operation, ignored -> new ArrayList<>()).add(page.getKey());
            });
        }
        Set<String> conflicts = new TreeSet<>();
        byOperation.forEach((operation, pages) -> {
            var selected = pages.entrySet().stream().filter(page -> changed.contains(page.getKey())).toList();
            List<Requirement> expected = (selected.isEmpty() ? pages.entrySet().iterator().next() : selected.get(0)).getValue();
            if (selected.stream().anyMatch(page -> !expected.equals(page.getValue()))) throw invalid("PAGE_OPERATION_CONFLICT");
            pages.forEach((page, values) -> { if (!expected.equals(values)) conflicts.add(page); });
            requirements.put(operation, expected);
        });
        if (!conflicts.isEmpty()) throw new PagePublications.Conflict(List.copyOf(conflicts));
        Set<String> affected = new TreeSet<>();
        for (String page : changed) {
            if (before.containsKey(page)) object(before.get(page).projection()).path("targets").forEach(target -> affected.add(target.path("operation").asString()));
            object(after.get(page).projection()).path("targets").forEach(target -> affected.add(target.path("operation").asString()));
            for (var state : List.of(before, after)) if (state.containsKey(page)) state.get(page).hooks().forEach(hook -> {
                affected.add(hook.operation());
                if (!hook.binding().endpoint().isEmpty()) affected.add(hook.binding().endpoint());
            });
        }
        return affected.stream().map(operation -> new Impact(operation,
                before.values().stream().flatMap(page -> page.requirements().stream()).filter(value -> value.operation().equals(operation))
                        .map(Requirement::policy).distinct().sorted().toList(),
                requirements.getOrDefault(operation, List.of()).stream().map(Requirement::policy).toList(),
                references.getOrDefault(operation, List.of()))).toList();
    }

    private Map<String, PageCatalog.AvailableComponent> components() {
        Map<String, PageCatalog.AvailableComponent> values = new HashMap<>();
        catalog.components().forEach(component -> values.put(component.definition().type(), component)); return values;
    }
    private static JsonNode objectSchema(String json) {
        try { return JSON.readTree(json); } catch (JacksonException failure) { throw invalid("PAGE_DOCUMENT_INVALID"); }
    }
    private static ObjectNode object(String value) {
        if (value == null || value.length() > 1024 * 1024 || value.getBytes(StandardCharsets.UTF_8).length > 1024 * 1024)
            throw invalid("PAGE_DOCUMENT_BUDGET");
        JsonNode node = objectSchema(value);
        if (node == null || !node.isObject()) throw invalid("PAGE_DOCUMENT_INVALID"); return (ObjectNode) node;
    }
    private static IllegalArgumentException invalid(String code) { return new IllegalArgumentException(code); }
}
