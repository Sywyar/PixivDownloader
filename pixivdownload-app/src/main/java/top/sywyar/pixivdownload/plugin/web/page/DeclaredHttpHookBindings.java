package top.sywyar.pixivdownload.plugin.web.page;

import java.io.IOException;
import java.util.*;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.plugin.api.operation.*;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.OperationHookCapabilityAdapter;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityInvocationRegistry;
import top.sywyar.pixivdownload.plugin.security.*;

/** 注解只提供待批准的声明；正式要求仍由同一个 publication store 持有。 */
@Component
public final class DeclaredHttpHookBindings {
    private final HttpHookDeclarations declarations;
    private final OperationEndpoints endpoints;
    private final ExternalCapabilityInvocationRegistry registry;
    private final WebPublicationStore store;
    private final HookPublications publications;
    public DeclaredHttpHookBindings(HttpHookDeclarations declarations, OperationEndpoints endpoints,
            ExternalCapabilityInvocationRegistry registry, WebPublicationStore store, HookPublications publications) {
        this.declarations = declarations; this.endpoints = endpoints; this.registry = registry;
        this.store = store; this.publications = publications;
    }
    public record View(String id, String provider, String hook, String callback, HttpHookDeclarations.Target target,
            OperationHook.Phase phase, List<OperationBinding.Field> fields, String endpoint,
            String status, boolean approved, String fingerprint) { }
    public record Listing(long revision, List<View> declarations) { }
    public record Selection(long revision, String provider, String id, String fingerprint, boolean remove) { }
    private record Resolved(View view, OperationBinding binding) { }

    public Listing list(String provider) {
        OperationPolicies.identity(provider);
        var snapshot = store.snapshot();
        return new Listing(snapshot.revision(), resolve(provider, snapshot).stream().map(Resolved::view).toList());
    }

    public HookPublications.ConfigurationPlan prepare(Selection selection) throws IOException {
        var snapshot = store.snapshot();
        if (snapshot.revision() != selection.revision()) throw new IllegalStateException("PAGE_PUBLICATION_STALE");
        OperationPolicies.identity(selection.provider());
        var selected = resolve(selection.provider(), snapshot).stream().filter(row -> row.view().id().equals(selection.id()))
                .findFirst().orElseThrow(() -> new IllegalStateException("PAGE_PUBLICATION_STALE"));
        if (!selected.view().fingerprint().equals(selection.fingerprint())) throw new IllegalStateException("PAGE_PUBLICATION_STALE");
        if (!selection.remove() && selected.binding() == null) throw new IllegalArgumentException("PAGE_HOOK_TARGET_UNAVAILABLE");
        var settings = snapshot.configuration().settings();
        var bindings = new ArrayList<>(settings.bindings().stream().filter(binding -> !belongs(binding.id(), selection.id())).toList());
        if (!selection.remove()) bindings.add(selected.binding());
        return publications.prepareConfiguration(selection.revision(), new WebPublicationStore.HookSettings(settings.groups(), bindings));
    }

    private List<Resolved> resolve(String provider, WebPublicationStore.Snapshot snapshot) {
        var catalog = endpoints.catalog(null);
        var stored = snapshot.configuration().settings().bindings();
        var result = new TreeMap<String, Resolved>();
        for (var published : declarations.snapshot()) {
            if (!published.owner().pluginId().equals(provider)) continue;
            var declaration = published.declaration();
            var candidates = catalog.stream().filter(declaration.target()::matches).toList();
            var previous = stored.stream().filter(binding -> belongs(binding.id(), published.id())).toList();
            String status = candidates.isEmpty() ? "UNDECLARED" : candidates.size() != 1 ? "AMBIGUOUS" : "UNAVAILABLE";
            OperationBinding binding = null;
            String identity = published.fingerprint() + candidates;
            if (candidates.size() == 1) {
                var endpoint = candidates.get(0);
                try (var lease = registry.retainService(OperationHookCapabilityAdapter.key(declaration.hook()))) {
                    var service = lease.provider(); var owner = published.owner(); var actual = service.publication();
                    if (!actual.pluginId().equals(owner.pluginId()) || !actual.packageId().equals(owner.packageId())
                            || actual.generation() != owner.pluginGeneration() || actual.publicationId() != owner.publicationId())
                        throw new IllegalStateException("OPERATION_HOOK_PROVIDER_CHANGED");
                    var definition = HookContracts.definition(service.service().definition());
                    String contract = HttpHookDeclarations.hash(owner.packageId() + ":" + declaration.contract() + definition + HookPublications.targetContract(candidates));
                    binding = declaration.binding(published.id() + ":" + contract.substring(0, 24), endpoint.id());
                    HookContracts.binding(binding, definition); HookFields.contract(binding, definition);
                    if (!endpoint.phases().contains(binding.phase()) || endpoint.shape().equals("STREAM")
                            && binding.fields().stream().anyMatch(field -> field.source() == OperationBinding.Source.RESULT))
                        throw new IllegalArgumentException("PAGE_HOOK_CONTRACT_MISMATCH");
                    identity += definition;
                    status = previous.equals(List.of(binding)) ? "ACTIVE" : previous.isEmpty() ? "PENDING_APPROVAL" : "CHANGED";
                } catch (IOException | RuntimeException unavailable) { binding = null; }
            }
            var view = new View(published.id(), provider, declaration.hook(), declaration.callback(), declaration.target(), declaration.phase(),
                    declaration.fields(), candidates.size() == 1 ? candidates.get(0).id() : "", status, !previous.isEmpty(),
                    HttpHookDeclarations.hash(snapshot.revision() + ":" + identity + ":" + status));
            if (result.putIfAbsent(view.id(), new Resolved(view, binding)) != null)
                throw new IllegalStateException("OPERATION_HOOK_DECLARATION_AMBIGUOUS");
        }
        // provider 撤回后仍显示已批准要求，管理员可以显式撤销，不能因缺席自动删除。
        for (var binding : stored) {
            String id = declarationId(binding.id());
            if (id == null || result.containsKey(id)) continue;
            var requirement = snapshot.configuration().requirements().stream().filter(value -> value.binding().hook().equals(binding.hook())
                    && value.binding().endpoint().equals(binding.endpoint()) && value.provider().equals(provider)).findFirst().orElse(null);
            if (requirement == null) continue;
            result.put(id, new Resolved(new View(id, provider, binding.hook(), "", null, binding.phase(), binding.fields(), binding.endpoint(),
                    "UNAVAILABLE", true, HttpHookDeclarations.hash(snapshot.revision() + ":" + binding)), null));
        }
        return List.copyOf(result.values());
    }

    static boolean belongs(String binding, String id) { return binding.startsWith(id + ":"); }
    static String declarationId(String binding) {
        return binding.matches("http-annotation-[a-f0-9]{64}:[a-f0-9]{24}") ? binding.substring(0, binding.lastIndexOf(':')) : null;
    }
}
