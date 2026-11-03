package top.sywyar.pixivdownload.plugin.web.controller;

import java.io.IOException;
import java.util.List;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import top.sywyar.pixivdownload.plugin.api.operation.*;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentityResolver;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityInvocationRegistry;
import top.sywyar.pixivdownload.plugin.security.*;

/** 管理员可发现跨 owner 目标；发现不授予发布权限，正式绑定仍经页面发布事务批准。 */
@RestController
@RequestMapping("/api/web/operation-hooks")
public final class OperationHooksController {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper(com.fasterxml.jackson.core.JsonFactory.builder()
            .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(com.fasterxml.jackson.core.StreamReadConstraints.builder().maxNestingDepth(24).build()).build())
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final java.util.concurrent.Semaphore configurations = new java.util.concurrent.Semaphore(4);
    private final OperationEndpoints endpoints;
    private final ExternalCapabilityInvocationRegistry registry;
    private final RequestOwnerIdentityResolver identities;
    private final top.sywyar.pixivdownload.plugin.web.page.HookPublications publications;
    private final top.sywyar.pixivdownload.plugin.web.page.WebPublicationStore store;
    public OperationHooksController(OperationEndpoints endpoints, ExternalCapabilityInvocationRegistry registry, RequestOwnerIdentityResolver identities,
            top.sywyar.pixivdownload.plugin.web.page.HookPublications publications, top.sywyar.pixivdownload.plugin.web.page.WebPublicationStore store) {
        this.endpoints = endpoints; this.registry = registry; this.identities = identities;
        this.publications = publications; this.store = store;
    }
    @GetMapping("/catalog")
    public ResponseEntity<List<OperationTargets.Endpoint>> catalog(HttpServletRequest request) {
        if (!identities.isAdminAuthenticated(request)) return ResponseEntity.status(403).build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(endpoints.catalog(null));
    }
    public record BindingConfiguration(long revision, top.sywyar.pixivdownload.plugin.web.page.WebPublicationStore.HookSettings settings) { }
    public record Publication(top.sywyar.pixivdownload.plugin.web.page.HookPublications.ConfigurationPlan plan,
            List<top.sywyar.pixivdownload.plugin.api.web.page.PagePublications.Permission> approvals) { }
    public record Failure(String code) { }
    @GetMapping("/configuration")
    public ResponseEntity<BindingConfiguration> configuration(HttpServletRequest request) {
        if (!identities.isAdminAuthenticated(request)) return ResponseEntity.status(403).build();
        var snapshot = store.snapshot();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new BindingConfiguration(snapshot.revision(), snapshot.configuration().settings()));
    }
    @PostMapping("/prepare")
    public ResponseEntity<?> prepare(HttpServletRequest request) {
        if (!identities.isAdminAuthenticated(request)) return ResponseEntity.status(403).build();
        if (!configurations.tryAcquire()) return ResponseEntity.status(429).body(new Failure("HOOK_CONFIGURATION_CAPACITY"));
        try {
            var value = read(request, BindingConfiguration.class);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(publications.prepareConfiguration(value.revision(), value.settings()));
        } catch (IOException | RuntimeException failure) { return failure(failure); }
        finally { configurations.release(); }
    }
    @PostMapping("/publish")
    public ResponseEntity<?> publish(HttpServletRequest request) {
        if (!identities.isAdminAuthenticated(request)) return ResponseEntity.status(403).build();
        if (!configurations.tryAcquire()) return ResponseEntity.status(429).body(new Failure("HOOK_CONFIGURATION_CAPACITY"));
        try {
            var value = read(request, Publication.class);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(publications.publishConfiguration(value.plan(), value.approvals(),
                    HttpOperationHooks.publicationAdmission(request)));
        } catch (IOException | RuntimeException failure) { return failure(failure); }
        finally { configurations.release(); }
    }
    private static <T> T read(HttpServletRequest request, Class<T> type) throws IOException {
        byte[] bytes = request.getInputStream().readNBytes(1024 * 1024 + 1);
        if (bytes.length > 1024 * 1024) throw new IllegalArgumentException("PAGE_HOOK_CAPACITY");
        return JSON.readValue(bytes, type);
    }
    private static ResponseEntity<Failure> failure(Exception failure) {
        String code = failure.getMessage();
        return ResponseEntity.status(failure instanceof IllegalArgumentException ? 400 : failure instanceof IllegalStateException ? 409 : 503)
                .cacheControl(CacheControl.noStore()).body(new Failure(code != null && code.matches("PAGE_[A-Z_]{1,80}") ? code : "HOOK_CONFIGURATION_UNAVAILABLE"));
    }
    @GetMapping("/targets")
    public ResponseEntity<OperationTargets.Resolution> resolve(@RequestParam OperationTargets.Kind kind,
            @RequestParam String id, @RequestParam(defaultValue = "") String targetId, @RequestParam(required = false) String actionId, HttpServletRequest request) {
        if (!identities.isAdminAuthenticated(request)) return ResponseEntity.status(403).build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(endpoints.resolve(null, new OperationTargets.Reference(kind, id, targetId, actionId)));
    }
    @GetMapping("/definitions")
    public ResponseEntity<List<AvailableHook>> definitions(HttpServletRequest request) throws IOException {
        if (!identities.isAdminAuthenticated(request)) return ResponseEntity.status(403).build();
        var keys = registry.serviceKeys(OperationHook.class);
        var hooks = registry.withServices(null, OperationHook.IMPORT, keys, List.of(), providers -> providers.stream()
                .map(provider -> new AvailableHook(provider.service().id(), provider.publication().pluginId(), provider.publication().toString(),
                        HookContracts.definition(provider.service().definition()))).toList());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(hooks);
    }
    public record AvailableHook(String id, String owner, String publication, OperationHook.Definition definition) { }
}
