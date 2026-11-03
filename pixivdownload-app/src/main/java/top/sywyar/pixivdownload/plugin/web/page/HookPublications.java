package top.sywyar.pixivdownload.plugin.web.page;

import java.io.IOException;
import java.util.*;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.plugin.api.operation.*;
import top.sywyar.pixivdownload.plugin.api.web.page.*;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.OperationHookCapabilityAdapter;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityInvocationRegistry;
import top.sywyar.pixivdownload.plugin.registry.web.PageContributionRegistry;
import top.sywyar.pixivdownload.plugin.security.*;
import static top.sywyar.pixivdownload.plugin.web.page.WebPublicationStore.*;

/** 通用绑定参加页面的同一次预检、权限确认、CAS 与提交，不另建策略存储。 */
@Component
public final class HookPublications {
    private final ExternalCapabilityInvocationRegistry registry;
    private final OperationEndpoints endpoints;
    private final PageContributionRegistry pages;
    private final WebPublicationStore store;
    private final OperationAdmissions admissions;
    public HookPublications(ExternalCapabilityInvocationRegistry registry, OperationEndpoints endpoints, PageContributionRegistry pages,
            WebPublicationStore store, OperationAdmissions admissions) {
        this.registry = registry; this.endpoints = endpoints; this.pages = pages;
        this.store = store; this.admissions = admissions;
    }
    record Prepared(Map<String, List<HookRequirement>> requirements, List<PagePublications.Permission> permissions, List<String> identities) { }
    @FunctionalInterface interface Compile<T> { T run(Prepared prepared) throws IOException; }

    <T> T compile(List<PagePublications.Change> changes, Compile<T> compile) throws IOException {
        var bindings = new LinkedHashMap<String, List<OperationBinding>>();
        var definitions = new LinkedHashMap<String, EditablePage>();
        var relations = new LinkedHashMap<String, List<PageInteraction.Action>>();
        var targets = new ArrayList<String>();
        var ids = new TreeSet<String>();
        for (var change : changes) {
            var document = JSON.readTree(change.document());
            var declared = document.path("hooks");
            if (!declared.isMissingNode() && (!declared.isArray() || declared.size() > 16))
                throw invalid("PAGE_HOOK_CAPACITY");
            var selected = new ArrayList<OperationBinding>();
            var unique = new HashSet<String>();
            for (var node : declared) {
                var binding = JSON.treeToValue(node, OperationBinding.class);
                if (binding.id() == null || !unique.add(binding.id())) throw invalid("PAGE_HOOK_DUPLICATE");
                OperationHookCapabilityAdapter.key(binding.hook());
                ids.add(binding.hook()); selected.add(binding);
            }
            var page = pages.pages().stream().filter(value -> value.definition().id().equals(change.page()))
                    .findFirst().orElseThrow(() -> invalid("PAGE_OWNER_UNAVAILABLE")).definition();
            page = PageActions.apply(page, document, pages);
            definitions.put(change.page(), page);
            var actions = PageActions.read(document);
            relations.put(change.page(), actions);
            for (var action : actions) {
                var reference = new OperationTargets.Reference(action.endpoint().isEmpty() ? OperationTargets.Kind.OPERATION : OperationTargets.Kind.ENDPOINT,
                        action.endpoint().isEmpty() ? action.operation() : action.endpoint(), "");
                var resolution = endpoints.resolve(null, reference);
                if (resolution.status() != OperationTargets.Status.AVAILABLE) throw invalid("PAGE_HOOK_TARGET_UNAVAILABLE");
                targets.add(resolution.revision());
            }
            addComponents(JSON.readTree(change.projection()).path("body"), page, actions, selected, 0);
            if (selected.size() > 16) throw invalid("PAGE_HOOK_CAPACITY");
            selected.forEach(binding -> ids.add(binding.hook()));
            bindings.put(change.page(), List.copyOf(selected));
        }
        var ordered = List.copyOf(ids);
        var keys = ordered.stream().map(OperationHookCapabilityAdapter::key).toList();
        return registry.withServices(null, OperationHook.IMPORT, keys, List.of(), providers -> {
            var groups = store.snapshot().configuration().settings().groups();
            var requirements = new TreeMap<String, List<HookRequirement>>();
            var permissions = new ArrayList<PagePublications.Permission>();
            var identities = new ArrayList<String>(targets);
            providers.forEach(provider -> identities.add(provider.publication().toString()));
            for (var page : bindings.entrySet()) {
                var definition = definitions.get(page.getKey());
                var allowed = definition.targets().stream().map(PageInteraction.Target::operation).collect(java.util.stream.Collectors.toSet());
                var compiled = new ArrayList<HookRequirement>();
                for (var declaredBinding : page.getValue()) {
                    var requirement = compileBinding(declaredBinding, providers.get(ordered.indexOf(declaredBinding.hook())),
                            groups, permissions, identities);
                    var binding = requirement.binding(); var hook = requirement.definition(); String operation = requirement.operation();
                    if (!(binding.endpoint().isEmpty() ? allowed.contains(operation)
                            : allowed.contains(operation) || relations.get(page.getKey()).stream().anyMatch(action -> action.endpoint().equals(binding.endpoint()))
                            || allowed.stream().anyMatch(group -> groups.getOrDefault(group, List.of()).contains(binding.endpoint()))))
                        throw invalid("PAGE_HOOK_TARGET_FORBIDDEN");
                    if (binding.phase() == OperationHook.Phase.BEFORE_INVOKE && hook.protocol() != null
                            && definition.targets().stream().filter(target -> target.operation().equals(operation) || target.operation().equals(binding.endpoint()))
                            .anyMatch(target -> !target.protocols().isEmpty() && target.protocols().stream().noneMatch(protocol -> OperationPolicies.compatible(protocol, hook.protocol()))))
                        throw invalid("PAGE_PROTOCOL_MISMATCH");
                    compiled.add(requirement);
                }
                requirements.put(page.getKey(), List.copyOf(compiled));
                for (var first : compiled) if (first.binding().phase() == OperationHook.Phase.BEFORE_INVOKE)
                    for (var second : compiled) if (first != second && second.binding().phase() == OperationHook.Phase.BEFORE_INVOKE
                            && first.binding().hook().equals(second.binding().hook()) && first.operation().equals(second.operation())
                            && (first.binding().endpoint().isEmpty() || second.binding().endpoint().isEmpty()
                            || first.binding().endpoint().equals(second.binding().endpoint()))) throw invalid("PAGE_HOOK_DUPLICATE");
            }
            return compile.run(new Prepared(Map.copyOf(requirements), List.copyOf(permissions), List.copyOf(identities)));
        });
    }

    private HookRequirement compileBinding(OperationBinding declared, top.sywyar.pixivdownload.plugin.api.service.ServiceProvider<OperationHook> provider,
            Map<String, List<String>> groups, List<PagePublications.Permission> permissions, List<String> identities) throws IOException {
        var hook = HookContracts.definition(provider.service().definition());
        HookContracts.binding(declared, hook);
        var configuration = JSON.readTree(declared.configuration());
        var binding = new OperationBinding("hook-" + contract(List.of(declared.hook(), declared.operation(), declared.endpoint(), declared.phase(),
                declared.order(), declared.completion(), configuration, declared.fields())), declared.hook(), declared.operation(), declared.endpoint(), declared.phase(),
                declared.order(), declared.completion(), configuration.toString(), declared.fields());
        if (!binding.hook().equals(provider.service().id())) throw invalid("PAGE_HOOK_ID_INVALID");
        var resolution = endpoints.resolve(null, reference(binding), groups);
        if (resolution.status() != OperationTargets.Status.AVAILABLE || resolution.operations().size() != 1) throw invalid("PAGE_HOOK_TARGET_UNAVAILABLE");
        String input = hook.inputSchema(), result = hook.resultSchema();
        for (var endpoint : resolution.endpoints()) if (!endpoint.phases().contains(binding.phase())
                || endpoint.shape().equals("STREAM") && binding.fields().stream().anyMatch(field -> field.source() == OperationBinding.Source.RESULT))
            throw invalid("PAGE_HOOK_CONTRACT_MISMATCH");
        top.sywyar.pixivdownload.plugin.security.HookFields.contract(binding, hook);
        identities.add(resolution.revision()); identities.add(provider.publication().toString());
        permissions.add(new PagePublications.Permission(provider.publication().pluginId(), binding.hook(), new PageResourceProfile("hook-binding", contract(binding), List.of())));
        for (var resource : hook.resources()) permissions.add(new PagePublications.Permission(provider.publication().pluginId(), binding.hook(), resource));
        return new HookRequirement(binding, resolution.operations().get(0), input, result, provider.publication().pluginId(), provider.publication().packageId(),
                hook, targetContract(resolution.endpoints()), targetAnchors(resolution.endpoints()));
    }

    public record ConfigurationPlan(long revision, String fingerprint, HookSettings settings,
            List<PagePublications.Permission> permissions, List<String> pages) { }
    @FunctionalInterface private interface Configured<T> { T run(ConfigurationPlan plan, Snapshot next) throws IOException; }

    public ConfigurationPlan prepareConfiguration(long revision, HookSettings settings) throws IOException {
        return configure(revision, settings, (plan, ignored) -> plan);
    }

    public long publishConfiguration(ConfigurationPlan requested, List<PagePublications.Permission> approvals) throws IOException {
        return publishConfiguration(requested, approvals, null);
    }

    public long publishConfiguration(ConfigurationPlan requested, List<PagePublications.Permission> approvals, OperationAdmissions.Lease publisher) throws IOException {
        if (registry.serviceInvocationOnCurrentThread()) throw invalid("PAGE_PUBLICATION_REENTRANT");
        return configure(requested.revision(), requested.settings(), (plan, next) -> {
            if (!plan.equals(requested)) throw invalid("PAGE_PUBLICATION_STALE");
            if (approvals == null || approvals.size() != new HashSet<>(approvals).size()
                    || !new HashSet<>(approvals).equals(new HashSet<>(plan.permissions()))) throw invalid("PAGE_RESOURCE_APPROVAL_REQUIRED");
            var affected = new TreeSet<String>();
            for (var state : List.of(store.snapshot().configuration(), next.configuration())) {
                state.requirements().forEach(binding -> {
                    affected.add(binding.operation());
                    if (!binding.binding().endpoint().isEmpty()) affected.add(binding.binding().endpoint());
                });
                state.settings().groups().forEach((group, members) -> { affected.add(group); affected.addAll(members); });
            }
            admissions.closeAndDrain(affected, java.time.Duration.ofSeconds(10), publisher);
            try {
                if (!prepareConfiguration(plan.revision(), plan.settings()).equals(plan)) throw invalid("PAGE_PUBLICATION_STALE");
                store.commit(plan.revision(), next); return next.revision();
            } finally { admissions.reopen(affected); }
        });
    }

    private <T> T configure(long revision, HookSettings settings, Configured<T> action) throws IOException {
        var before = store.snapshot();
        if (before.revision() != revision) throw invalid("PAGE_REVISION_CONFLICT");
        if (settings == null || settings.bindings().size() > 64 || settings.groups().size() > 64 || JSON.writeValueAsBytes(settings).length > 1024 * 1024)
            throw invalid("PAGE_HOOK_CAPACITY");
        var catalog = endpoints.catalog(null);
        for (var group : before.configuration().settings().groups().entrySet()) {
            if (!Objects.equals(group.getValue(), settings.groups().get(group.getKey())) && before.pages().values().stream().anyMatch(page ->
                    page.hooks().stream().anyMatch(item -> item.operation().equals(group.getKey()))
                            || page.requirements().stream().anyMatch(item -> item.operation().equals(group.getKey())))) throw invalid("PAGE_OPERATION_CONFLICT");
        }
        for (var group : settings.groups().entrySet()) {
            OperationPolicies.identity(group.getKey());
            if (group.getValue().isEmpty() || group.getValue().size() > 64 || new HashSet<>(group.getValue()).size() != group.getValue().size()
                    || catalog.stream().anyMatch(endpoint -> endpoint.operation().equals(group.getKey()))) throw invalid("PAGE_HOOK_GROUP_INVALID");
            for (String id : group.getValue()) {
                var endpoint = catalog.stream().filter(value -> value.id().equals(id)).findFirst().orElseThrow(() -> invalid("PAGE_HOOK_TARGET_UNAVAILABLE"));
                if (!group.getKey().startsWith(endpoint.owner() + ":")) throw invalid("PAGE_HOOK_TARGET_FORBIDDEN");
            }
        }
        var ids = settings.bindings().stream().map(OperationBinding::hook).distinct().sorted().toList();
        var keys = ids.stream().map(OperationHookCapabilityAdapter::key).toList();
        return registry.withServices(null, OperationHook.IMPORT, keys, List.of(), providers -> {
            var requirements = new ArrayList<HookRequirement>(); var permissions = new ArrayList<PagePublications.Permission>(); var identities = new ArrayList<String>();
            identities.add(contract(catalog));
            for (var binding : settings.bindings()) requirements.add(compileBinding(binding, providers.get(ids.indexOf(binding.hook())), settings.groups(), permissions, identities));
            if (requirements.stream().map(value -> value.binding().id()).distinct().count() != requirements.size()) throw invalid("PAGE_HOOK_DUPLICATE");
            var next = new Snapshot(revision + 1, before.epoch(), before.pages(), new HookState(settings, requirements, permissions.stream().distinct().toList()));
            validate(next, catalog);
            var affectedPages = before.pages().entrySet().stream().filter(entry -> entry.getValue().hooks().stream().anyMatch(hook ->
                    requirements.stream().anyMatch(value -> value.operation().equals(hook.operation())))).map(Map.Entry::getKey).sorted().toList();
            return action.run(new ConfigurationPlan(revision, contract(List.of(next, identities)), settings, next.configuration().permissions(), affectedPages), next);
        });
    }

    public static void validate(Snapshot snapshot, List<OperationTargets.Endpoint> catalog) {
        for (var endpoint : catalog) {
            var requirements = snapshot.hooks(endpoint.operation(), endpoint.id());
            if (requirements.size() > 16) throw invalid("PAGE_HOOK_CAPACITY");
            var gates = requirements.stream().filter(item -> item.binding().phase() == OperationHook.Phase.BEFORE_INVOKE).toList();
            if (gates.stream().map(item -> item.binding().hook()).distinct().count() != gates.size()) throw invalid("PAGE_HOOK_DUPLICATE");
        }
    }
    void validate(Snapshot snapshot) { validate(snapshot, endpoints.catalog(null)); }
    List<OperationTargets.Endpoint> targets() { return endpoints.catalog(null); }

    private static OperationTargets.Reference reference(OperationBinding binding) {
        return new OperationTargets.Reference(binding.endpoint().isEmpty() ? OperationTargets.Kind.OPERATION : OperationTargets.Kind.ENDPOINT,
                binding.endpoint().isEmpty() ? binding.operation() : binding.endpoint(), "");
    }

    private void addComponents(com.fasterxml.jackson.databind.JsonNode node, EditablePage page, List<PageInteraction.Action> actions, List<OperationBinding> bindings, int depth) {
        if (depth > 24) throw invalid("PAGE_DOCUMENT_BUDGET");
        if (node.path("type").asText().equals("page-component")) {
            var component = pages.components().stream().filter(value -> value.definition().type().equals(node.path("componentType").asText()))
                    .findFirst().map(PageCatalog.AvailableComponent::definition).orElse(null);
            if (component != null && component.participation() != null) {
                var target = page.targets().stream().filter(value -> value.id().equals(node.path("target").asText()))
                        .findFirst().orElseThrow(() -> invalid("PAGE_BINDING_INVALID"));
                var action = actions.stream().filter(value -> value.id().equals(target.id())).findFirst().orElse(null);
                String endpoint = action == null ? "" : action.endpoint();
                var resolution = endpoints.resolve(null, new OperationTargets.Reference(endpoint.isEmpty() ? OperationTargets.Kind.OPERATION : OperationTargets.Kind.ENDPOINT,
                        endpoint.isEmpty() ? target.operation() : endpoint, ""));
                // 未声明 HTTP 合同的旧 owner 继续使用原操作验证；不会伪造接口发现。
                if (resolution.status() != OperationTargets.Status.UNDECLARED) {
                    String hook = component.participation().policy();
                    if (bindings.stream().noneMatch(binding -> binding.hook().equals(hook) && (endpoint.isEmpty()
                            ? binding.operation().equals(target.operation()) : binding.endpoint().equals(endpoint))
                            && binding.phase() == OperationHook.Phase.BEFORE_INVOKE)) {
                        bindings.add(new OperationBinding("component-" + hook + "-" + Integer.toUnsignedString(target.operation().hashCode()), hook,
                                endpoint.isEmpty() ? target.operation() : "", endpoint, OperationHook.Phase.BEFORE_INVOKE, 0, OperationHook.Completion.ALL, "{}"));
                    }
                }
            }
        }
        for (var child : node.path("children")) addComponents(child, page, actions, bindings, depth + 1);
    }

    public boolean current(HookRequirement requirement) {
        var binding = requirement.binding();
        var reference = new OperationTargets.Reference(binding.endpoint().isEmpty() ? OperationTargets.Kind.OPERATION : OperationTargets.Kind.ENDPOINT,
                binding.endpoint().isEmpty() ? binding.operation() : binding.endpoint(), "");
        var resolution = endpoints.resolve(null, reference);
        if (resolution.status() != OperationTargets.Status.AVAILABLE) return false;
        return requirement.targetContract().equals(targetContract(resolution.endpoints()));
    }

    public static Set<String> targetAnchors(List<OperationTargets.Endpoint> endpoints) {
        var anchors = new TreeSet<String>();
        for (var endpoint : endpoints) {
            anchors.add("handler:" + endpoint.handler());
            endpoint.routes().forEach(route -> anchors.add("route:" + route.method() + ":" + route.template()));
        }
        return Set.copyOf(anchors);
    }

    public static String targetContract(List<OperationTargets.Endpoint> endpoints) {
        return contract(endpoints.stream().map(endpoint -> List.of(endpoint.id(), endpoint.operation(), endpoint.owner(), endpoint.revision(), endpoint.routes(),
                endpoint.phases().stream().sorted().toList(), endpoint.inputSchema(), endpoint.resultSchema())).toList());
    }

    private static boolean same(String a, String b) throws IOException { return JSON.readTree(a).equals(JSON.readTree(b)); }
    private static String contract(Object value) {
        try { return hash(JSON.writeValueAsBytes(value)); }
        catch (IOException failure) { throw invalid("PAGE_HOOK_CONTRACT_INVALID"); }
    }
    private static IllegalArgumentException invalid(String code) { return new IllegalArgumentException(code); }
}
