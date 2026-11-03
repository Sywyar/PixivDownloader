package top.sywyar.pixivdownload.plugin.security;

import java.io.IOException;
import java.util.*;
import java.util.function.LongSupplier;
import org.slf4j.LoggerFactory;
import top.sywyar.pixivdownload.plugin.api.operation.*;
import top.sywyar.pixivdownload.plugin.api.service.*;
import top.sywyar.pixivdownload.plugin.api.security.OperationGuard;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.OperationHookCapabilityAdapter;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityInvocationRegistry;
import top.sywyar.pixivdownload.plugin.web.page.WebPublicationStore.HookRequirement;
import top.sywyar.pixivdownload.plugin.web.remote.RemoteValues;

/** 一次请求的回调与纯值投影；所有已取得的租约持续到整个同步调用返回。 */
final class OperationHookCalls implements AutoCloseable {
    record Identity(ServicePublication publication, OperationHook.Definition definition, String generation) { }
    @FunctionalInterface interface Call<T> { T run(OperationHookCalls hooks) throws IOException; }
    private final List<HookRequirement> bindings;
    private final Map<String, ServiceProvider<OperationHook>> providers;
    private final String operation, endpoint, subject, input, correlation;
    private final LongSupplier clock;
    private volatile boolean beforeExpired, afterExpired;
    private final List<Identity> identities = new ArrayList<>();
    private final Set<String> observers = new HashSet<>();
    private final List<ExternalCapabilityInvocationRegistry.RetainedService<OperationHook>> leases = new ArrayList<>();
    private ExternalCapabilityInvocationRegistry registry;
    private OperationHook.Http http;
    private Map<String, String> sources = Map.of();
    private final Map<String, String> callbacks = new HashMap<>();
    private Map<String, String> inputs, results;
    private static final class NotInvoked extends RuntimeException {
        NotInvoked(String reason) { super(reason, null, false, false); }
    }
    private static final class CallbackFailed extends RuntimeException {
        CallbackFailed() { super("HOOK_CALLBACK_FAILED", null, false, false); }
    }

    private OperationHookCalls(List<HookRequirement> bindings, Map<String, ServiceProvider<OperationHook>> providers,
            String operation, String endpoint, String subject, String input, String correlation, LongSupplier clock) {
        this.bindings = bindings; this.providers = providers; this.operation = operation; this.endpoint = endpoint;
        this.subject = subject; this.input = input; this.correlation = correlation; this.clock = clock;
    }

    static OperationHookCalls open(ExternalCapabilityInvocationRegistry registry, List<HookRequirement> bindings,
            OperationTargets.Endpoint endpoint, String subject, String input, String correlation,
            OperationHook.Http http, Map<String, String> sources, List<String> required,
            Map<String, HookRequirement> configured) {
        if (bindings.size() > 16) throw new IllegalArgumentException("OPERATION_HOOK_CAPACITY");
        var selected = new ArrayList<>(bindings);
        var calls = new OperationHookCalls(selected, new LinkedHashMap<>(), endpoint.operation(), endpoint.id(), subject, input, correlation, System::nanoTime);
        calls.registry = registry; calls.http = http; calls.sources = sources;
        try {
            for (String id : java.util.stream.Stream.concat(bindings.stream().map(item -> item.binding().hook()), required.stream()).distinct().sorted().toList()) {
                try {
                    var lease = registry.retainService(OperationHookCapabilityAdapter.key(id));
                    calls.leases.add(lease); calls.providers.put(id, lease.provider());
                    calls.callbacks.put(id, lease.source());
                } catch (ServiceAccessException unavailable) { /* prepare 将区分必需 gate 与可选观察。 */ }
            }
            for (String id : required) {
                String key = endpoint.operation() + "/" + id;
                var published = selected.stream().filter(item -> item.binding().phase() == OperationHook.Phase.BEFORE_INVOKE && item.binding().hook().equals(id)).findFirst();
                if (published.isPresent()) { configured.put(key, published.get()); continue; }
                var provider = calls.providers.get(id);
                var binding = new OperationBinding("configured-" + id, id, endpoint.operation(), "", OperationHook.Phase.BEFORE_INVOKE, 0, OperationHook.Completion.ALL, "{}");
                var current = provider == null ? new HookRequirement(binding, endpoint.operation(), "", "", "unresolved", "unresolved", null, "")
                        : new HookRequirement(binding, endpoint.operation(), "", "", provider.publication().pluginId(), provider.publication().packageId(),
                                HookContracts.definition(provider.service().definition()), "");
                if (provider != null) configured.putIfAbsent(key, current);
                var pinned = configured.getOrDefault(key, current);
                selected.add(new HookRequirement(binding, endpoint.operation(), "", "", pinned.provider(), pinned.packageId(), pinned.definition(), ""));
            }
            if (selected.size() > 16) throw new IllegalArgumentException("OPERATION_HOOK_CAPACITY");
            selected.sort(Comparator.comparing((HookRequirement item) -> item.binding().phase()).thenComparingInt(item -> item.binding().order()).thenComparing(item -> item.binding().id()));
            return calls;
        } catch (RuntimeException | Error failure) { calls.close(); throw failure; }
    }

    void http(OperationHook.Http value) { http = value; }
    void inputs(Map<String, String> value) { inputs = value; }
    void results(Map<String, String> value) { results = value; }

    @Override public void close() {
        providers.clear(); observers.clear(); identities.clear(); callbacks.clear(); inputs = null; results = null;
        Error fatal = null;
        for (int i = leases.size() - 1; i >= 0; i--) {
            try { leases.get(i).close(); } catch (Error failure) { if (fatal == null) fatal = failure; }
        }
        leases.clear();
        if (fatal != null) throw fatal;
    }

    static <T> T acquire(ExternalCapabilityInvocationRegistry registry, PluginServices services,
            List<HookRequirement> bindings, String operation, String endpoint, String subject, String input,
            String correlation, LongSupplier clock, Call<T> action) throws IOException {
        if (bindings.size() > 16) throw new IllegalArgumentException("OPERATION_HOOK_CAPACITY");
        var keys = bindings.stream().map(item -> item.binding().hook()).distinct().sorted().toList();
        return registry.withServices(services, OperationHook.IMPORT, List.of(), List.of(), ignored ->
                acquire(registry, services, keys, 0, new LinkedHashMap<>(), providers -> {
                    var calls = new OperationHookCalls(bindings, providers, operation, endpoint, subject, input, correlation, clock);
                    calls.registry = registry;
                    providers.forEach((id, provider) -> calls.callbacks.put(id, registry.serviceSource(provider.publication(), OperationHookCapabilityAdapter.key(id))));
                    return action.run(calls);
                }));
    }

    @FunctionalInterface private interface Selected<T> { T run(Map<String, ServiceProvider<OperationHook>> providers) throws IOException; }
    private static <T> T acquire(ExternalCapabilityInvocationRegistry registry, PluginServices services, List<String> keys,
            int index, Map<String, ServiceProvider<OperationHook>> providers, Selected<T> action) throws IOException {
        if (index == keys.size()) return action.run(providers);
        boolean[] entered = {false};
        String key = keys.get(index);
        try {
            return registry.withServices(services, OperationHook.IMPORT, List.of(OperationHookCapabilityAdapter.key(key)), List.of(), selected -> {
                entered[0] = true; providers.put(key, selected.get(0));
                return acquire(registry, services, keys, index + 1, providers, action);
            });
        } catch (ServiceAccessException failure) {
            if (entered[0]) throw failure;
            return acquire(registry, services, keys, index + 1, providers, action);
        }
    }

    boolean prepare() {
        identities.clear();
        observers.clear();
        boolean ready = true;
        for (var item : bindings) {
            var provider = providers.get(item.binding().hook());
            if (provider == null) {
                diagnostic(item, "UNAVAILABLE");
                if (item.binding().phase() == OperationHook.Phase.BEFORE_INVOKE) ready = false;
                continue;
            }
            try {
                var definition = HookContracts.definition(provider.service().definition());
                String generation = provider.service().generation();
                OperationPolicies.identity(generation);
                if (!provider.service().id().equals(item.binding().hook()) || !item.provider().equals(provider.publication().pluginId())
                        || !item.packageId().equals(provider.publication().packageId()) || !item.definition().equals(definition))
                    throw new IllegalArgumentException("OPERATION_HOOK_CHANGED");
                HookContracts.binding(item.binding(), definition);
                if (item.binding().phase() == OperationHook.Phase.BEFORE_INVOKE)
                    identities.add(new Identity(provider.publication(), definition, generation));
                else observers.add(item.binding().id());
            } catch (RuntimeException failure) {
                diagnostic(item, "INCOMPATIBLE");
                if (item.binding().phase() == OperationHook.Phase.BEFORE_INVOKE) ready = false;
            }
        }
        return ready;
    }

    List<Identity> identities() { return List.copyOf(identities); }

    /** 旧验证合同只负责适配请求信封，排序、拒绝、挑战和超时进入同一前置管线。 */
    OperationHookCalls withVerifications(List<ServiceProvider<top.sywyar.pixivdownload.plugin.api.security.OperationVerification>> verification) {
        var all = new ArrayList<>(bindings);
        var selected = new LinkedHashMap<>(providers);
        for (var provider : verification) {
            String id = provider.service().policyId();
            if (all.stream().anyMatch(item -> item.binding().hook().equals(id) && item.binding().phase() == OperationHook.Phase.BEFORE_INVOKE))
                throw new IllegalArgumentException("OPERATION_HOOK_DUPLICATE");
            var binding = new OperationBinding("verification-" + id, id, operation, "", OperationHook.Phase.BEFORE_INVOKE,
                    0, OperationHook.Completion.ALL, "{}");
            all.add(new HookRequirement(binding, operation, "", "", provider.publication().pluginId(), provider.publication().packageId(),
                    provider.service().definition(), ""));
            selected.put(id, new ServiceProvider<>(provider.publication(), provider.service()));
        }
        if (all.size() > 16) throw new IllegalArgumentException("OPERATION_HOOK_CAPACITY");
        all.sort(Comparator.comparing((HookRequirement item) -> item.binding().phase()).thenComparingInt(item -> item.binding().order())
                .thenComparing(item -> item.binding().id()));
        var combined = new OperationHookCalls(List.copyOf(all), selected, operation, endpoint, subject, input, correlation, clock);
        combined.registry = registry;
        combined.callbacks.putAll(callbacks);
        for (var provider : verification) combined.callbacks.put(provider.service().policyId(), registry.serviceSource(provider.publication(),
                top.sywyar.pixivdownload.plugin.lifecycle.capability.OperationVerificationCapabilityAdapter.key(provider.service().policyId())));
        return combined;
    }

    OperationGuard.Status before(String attempt, boolean response, Map<String, String> proofs, Map<String, String> challenges) {
        boolean rejected = false;
        long started = clock.getAsLong();
        for (var item : bindings) {
            if (item.binding().phase() != OperationHook.Phase.BEFORE_INVOKE) continue;
            try {
                if (beforeExpired || clock.getAsLong() - started >= 10_000_000_000L || Thread.currentThread().isInterrupted()) {
                    diagnostic(item, "TIMEOUT"); return OperationGuard.Status.UNAVAILABLE;
                }
                var decision = invoke(item, OperationHook.Outcome.NONE, "{}", attempt, response, proofs.getOrDefault(item.binding().hook(), ""));
                if (beforeExpired || clock.getAsLong() - started >= 10_000_000_000L || Thread.currentThread().isInterrupted()) {
                    diagnostic(item, "COMPLETED_AFTER_DEADLINE"); return OperationGuard.Status.UNAVAILABLE;
                }
                if (decision == null || decision.verdict() == null) throw new IllegalArgumentException("OPERATION_HOOK_RESULT_INVALID");
                if (decision.verdict() == OperationHook.Verdict.REJECT) rejected = true;
                if (decision.verdict() == OperationHook.Verdict.CHALLENGE) {
                    if (item.definition().protocol() == null) throw new IllegalArgumentException("OPERATION_HOOK_PROTOCOL_INVALID");
                    String challenge = decision.challenge();
                    HookContracts.json(WebValue.string(challenge));
                    RemoteValues.validateConfiguration(item.definition().protocol().stateSchema(), WebValue.string(challenge));
                    if (challenges.putIfAbsent(item.binding().hook(), challenge) != null)
                        throw new IllegalArgumentException("OPERATION_HOOK_DUPLICATE");
                }
            } catch (CallbackFailed failure) { return OperationGuard.Status.UNAVAILABLE; }
            catch (NotInvoked failure) { diagnostic(item, failure.getMessage()); return OperationGuard.Status.UNAVAILABLE; }
            catch (RuntimeException failure) { diagnostic(item, "INVALID_RESULT"); return OperationGuard.Status.UNAVAILABLE; }
        }
        return rejected ? OperationGuard.Status.REJECTED : OperationGuard.Status.APPROVED;
    }

    boolean current() {
        var before = List.copyOf(identities);
        return prepare() && before.equals(identities);
    }

    void after(OperationHook.Outcome outcome, String result) {
        long started = clock.getAsLong();
        for (var item : bindings) {
            var binding = item.binding();
            if (binding.phase() != OperationHook.Phase.AFTER_COMPLETION
                    || binding.completion() != OperationHook.Completion.ALL && !binding.completion().name().equals(outcome.name())) continue;
            if (!observers.contains(binding.id())) continue;
            if (afterExpired || clock.getAsLong() - started >= 10_000_000_000L || Thread.currentThread().isInterrupted()) {
                diagnostic(item, "TIMEOUT"); continue;
            }
            try { invoke(item, outcome, result, "", false, ""); }
            catch (CallbackFailed failure) { /* 真实调用终态已记录，业务结果保持不变。 */ }
            catch (NotInvoked failure) { diagnostic(item, failure.getMessage()); }
        }
    }

    private OperationHook.Decision invoke(HookRequirement item, OperationHook.Outcome outcome, String result,
            String attempt, boolean response, String proof) {
        if (item.binding().phase() == OperationHook.Phase.AFTER_COMPLETION && inputs != null && !inputs.containsKey(item.binding().id()))
            throw new NotInvoked("INPUT_UNAVAILABLE");
        var provider = providers.get(item.binding().hook());
        try {
            if (!item.definition().equals(provider.service().definition())) throw new NotInvoked("PROVIDER_CHANGED");
        } catch (RuntimeException unavailable) { throw new NotInvoked("PROVIDER_CHANGED"); }
        String safeInput = item.definition().inputSchema().isEmpty() ? "{}" : inputs == null ? input : inputs.getOrDefault(item.binding().id(), "{}");
        String safeResult = item.definition().resultSchema().isEmpty() || outcome != OperationHook.Outcome.SUCCEEDED ? "{}"
                : results == null ? result : results.get(item.binding().id());
        if (safeResult == null) { diagnostic(item, "RESULT_UNAVAILABLE"); return OperationHook.Decision.proceed(); }
        try {
            if (!item.definition().inputSchema().isEmpty()) RemoteValues.validateConfiguration(item.definition().inputSchema(), safeInput);
            if (!item.definition().resultSchema().isEmpty() && outcome == OperationHook.Outcome.SUCCEEDED)
                RemoteValues.validateConfiguration(item.definition().resultSchema(), safeResult);
        } catch (RuntimeException invalid) { throw new NotInvoked("DATA_INCOMPATIBLE"); }
        String invocation = UUID.randomUUID().toString();
        String callback = callbacks.get(item.binding().hook()) + "#invoke";
        String previous = org.slf4j.MDC.get("operationVerificationId");
        org.slf4j.MDC.put("operationVerificationId", correlation);
        long started = clock.getAsLong();
        String decision = "CALL_FAILED";
        String location = http == null ? "non-http" : http.owner() + " " + http.handler() + " " + http.method() + " " + http.route();
        log(false, "hook.log.started", correlation, invocation, provider.publication(), callback, item.binding().hook(), item.binding().id(),
                item.binding().phase(), endpoint, location, sources.getOrDefault(item.binding().id(), http == null ? "managed-operation" : "host-configuration"));
        try {
            var value = provider.service().invoke(new OperationHook.Invocation(item.operation(), endpoint, item.binding().phase(), outcome,
                    correlation, subject, safeInput, safeResult, item.binding().configuration(), attempt,
                    item.definition().revision(), response, proof, http));
            decision = value == null || value.verdict() == null ? "INVALID_RESULT" : value.verdict().name();
            return value;
        } catch (RuntimeException failure) {
            throw new CallbackFailed();
        } finally {
            log(decision.equals("CALL_FAILED"), "hook.log.completed", correlation, invocation, item.binding().hook(), item.binding().phase(),
                    outcome, decision, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(clock.getAsLong() - started));
            if (previous == null) org.slf4j.MDC.remove("operationVerificationId"); else org.slf4j.MDC.put("operationVerificationId", previous);
        }
    }

    void deadline(OperationHook.Phase phase) {
        if (phase == OperationHook.Phase.BEFORE_INVOKE) beforeExpired = true; else afterExpired = true;
        log(true, "hook.log.pending", correlation, phase);
    }

    private void diagnostic(HookRequirement item, String reason) {
        log(true, reason.equals("INVALID_RESULT") || reason.equals("COMPLETED_AFTER_DEADLINE") ? "hook.log.diagnostic" : "hook.log.skipped", correlation, item.provider(), item.packageId(), item.binding().hook(), item.binding().id(),
                item.binding().phase(), endpoint, reason);
    }

    static void skipped(HookRequirement item, String endpoint, String reason) {
        log(true, "hook.log.skipped", UUID.randomUUID().toString(), item.provider(), item.packageId(), item.binding().hook(), item.binding().id(),
                item.binding().phase(), endpoint, reason);
    }

    private static void log(boolean warning, String key, Object... values) {
        try {
            String message = top.sywyar.pixivdownload.i18n.MessageBundles.getForLog(key, values);
            var logger = LoggerFactory.getLogger(OperationHookCalls.class);
            if (warning) logger.warn(message); else logger.info(message);
        } catch (RuntimeException ignored) { /* 辅助日志失败不重放业务或改变调用结果。 */ }
    }

    private static final class WebValue {
        private static String string(String value) {
            if (value == null || value.length() > 16384) throw new IllegalArgumentException("OPERATION_HOOK_VALUE_LIMIT");
            return com.fasterxml.jackson.databind.node.TextNode.valueOf(value).toString();
        }
    }
}
