package top.sywyar.pixivdownload.plugin.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.plugin.api.security.*;
import top.sywyar.pixivdownload.plugin.api.operation.*;
import top.sywyar.pixivdownload.plugin.api.service.*;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.OperationVerificationCapabilityAdapter;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityInvocationRegistry;
import top.sywyar.pixivdownload.plugin.runtime.context.PluginOperationGuardFactory;
import top.sywyar.pixivdownload.plugin.registry.web.PageContributionRegistry;
import top.sywyar.pixivdownload.plugin.web.page.WebPublicationStore;
import top.sywyar.pixivdownload.plugin.web.remote.RemoteValues;

import java.io.IOException;
import static top.sywyar.pixivdownload.i18n.MessageBundles.getForLog;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.*;
import static top.sywyar.pixivdownload.plugin.api.security.OperationGuard.Status.*;

/** 挑战只保留请求摘要及精确 publication；受管动作与验证共用既有注册中心的租约。 */
@Component
public final class OperationVerificationGate implements PluginOperationGuardFactory {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(OperationVerificationGate.class);
    private static final long VERIFICATION_NANOS = java.time.Duration.ofSeconds(10).toNanos();
    private final ExternalCapabilityInvocationRegistry registry;
    private final Map<String, List<String>> requirements;
    private final Clock clock;
    private final java.util.function.LongSupplier elapsedTime;
    private final WebPublicationStore publications;
    private final PageContributionRegistry pages;
    private final OperationAdmissions admissions;
    private final Map<String, OperationVerification.Policy> configuredPlans = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Pending> pending = new LinkedHashMap<>();
    private OperationEndpoints endpoints;
    private top.sywyar.pixivdownload.plugin.web.page.HookPublications hookPublications;

    @Autowired
    public void endpoints(OperationEndpoints endpoints) { this.endpoints = endpoints; }
    @Autowired
    public void hooks(top.sywyar.pixivdownload.plugin.web.page.HookPublications hooks) { this.hookPublications = hooks; }

    @Autowired
    public OperationVerificationGate(ExternalCapabilityInvocationRegistry registry,
            List<OperationRequirement> configuredPolicies,
            WebPublicationStore publications, PageContributionRegistry pages, OperationAdmissions admissions) {
        this(registry, OperationRequirement.collect(configuredPolicies), Clock.systemUTC(),
                publications, pages, admissions);
    }

    public OperationVerificationGate(ExternalCapabilityInvocationRegistry registry,
            Map<String, List<String>> requirements, Clock clock, WebPublicationStore publications,
            PageContributionRegistry pages, OperationAdmissions admissions) {
        this(registry, requirements, clock, publications, pages, admissions, System::nanoTime);
    }

    OperationVerificationGate(ExternalCapabilityInvocationRegistry registry,
            Map<String, List<String>> requirements, Clock clock, WebPublicationStore publications,
            PageContributionRegistry pages, OperationAdmissions admissions, java.util.function.LongSupplier elapsedTime) {
        this.registry = Objects.requireNonNull(registry);
        this.clock = Objects.requireNonNull(clock);
        this.elapsedTime = Objects.requireNonNull(elapsedTime);
        this.publications = Objects.requireNonNull(publications);
        this.pages = Objects.requireNonNull(pages);
        this.admissions = Objects.requireNonNull(admissions);
        var copy = new LinkedHashMap<String, List<String>>();
        requirements.forEach((operation, policies) -> {
            if (policies.size() > 16 || policies.stream().distinct().count() != policies.size())
                throw new IllegalArgumentException("OPERATION_POLICY_CONFIGURATION_INVALID");
            policies.forEach(OperationVerificationCapabilityAdapter::key);
            copy.put(operation, List.copyOf(policies));
        });
        this.requirements = Map.copyOf(copy);
    }

    private record Identity(ServicePublication publication, OperationVerification.Policy policy, String generation) { }
    private record Pending(String owner, String operation, String digest, List<String> policies,
                           List<Identity> identities, List<OperationHookCalls.Identity> hooks, String publicationRevision, long expires) { }

    @Override public OperationGuard create(PluginServices services) {
        return new BoundGuard(registry.serviceOwner(services).pluginId(), services);
    }

    /** 核心业务使用独立的宿主入口；插件只能取得 child context 中绑定的访问器。 */
    public OperationGuard host() { return new BoundGuard("core", null); }

    private final class BoundGuard implements OperationGuard {
        private final String owner;
        private final PluginServices services;
        private final String identity;
        private BoundGuard(String owner, PluginServices services) {
            this.owner = owner;
            this.services = services;
            this.identity = services == null ? owner : registry.serviceOwner(services).toString();
        }

        @Override public <T> Result<T> execute(String operation, String subject, String context,
                String token, Map<String, String> input, Action<T> action) throws IOException {
            return run(operation, subject, context, token, input, Objects.requireNonNull(action));
        }

        @Override public Result<Void> prepare(String operation, String subject, String context) throws IOException {
            return run(operation, subject, context, "", Map.of(), null);
        }

        @Override public OperationTargets.Resolution resolveTargets(OperationTargets.Reference reference) {
            if (endpoints == null) return new OperationTargets.Resolution(OperationTargets.Status.UNAVAILABLE, "", List.of(), List.of());
            try {
                return registry.withServices(services, OperationHook.IMPORT, List.of(), List.of(), ignored -> endpoints.resolve(owner, reference));
            } catch (ServiceAccessException unavailable) {
                return new OperationTargets.Resolution(unavailable.reason() == ServiceAccessException.Reason.UNDECLARED
                        ? OperationTargets.Status.FORBIDDEN : OperationTargets.Status.UNAVAILABLE, "", List.of(), List.of());
            } catch (IOException unavailable) {
                return new OperationTargets.Resolution(OperationTargets.Status.UNAVAILABLE, "", List.of(), List.of());
            }
        }

        private <T> Result<T> run(String operation, String subject, String context,
                String token, Map<String, String> input, Action<T> action) throws IOException {
            if (operation == null || operation.length() > 256 || !operation.startsWith(owner + ":")
                    || !operation.matches("[a-z0-9-]+:[a-zA-Z0-9._-]+")
                    || subject == null || subject.length() > 1024 || context == null || context.length() > 16384
                    || context.getBytes(StandardCharsets.UTF_8).length > 16384)
                return result(INVALID);
            long started = elapsedTime.getAsLong();
            String correlation = UUID.randomUUID().toString();
            String previousCorrelation = org.slf4j.MDC.get("operationVerificationId");
            org.slf4j.MDC.put("operationVerificationId", correlation);
            LOG.info(getForLog("operation.log.started", correlation, operation, action == null ? "PREPARE" : "SUBMIT"));
            String outcome = "FAILED";
            try {
                OperationAdmissions.Lease lease;
                try { lease = admissions.enter(operation); }
                catch (IllegalStateException closed) { outcome = "UNAVAILABLE"; return unavailable(correlation, "ADMISSION_CLOSED"); }
                try (lease) {
                    WebPublicationStore.Snapshot snapshot;
                    try { snapshot = publications.snapshot(); }
                    catch (IllegalStateException unavailable) { outcome = "UNAVAILABLE"; return unavailable(correlation, "PUBLICATION_UNAVAILABLE"); }
                    var bindings = snapshot.hooks(operation, "");
                    boolean[] invoked = {false};
                    var completed = new java.util.concurrent.atomic.AtomicReference<OperationHook.Outcome>();
                    Result<T> result;
                    try {
                        result = OperationHookCalls.acquire(registry, services, bindings, operation, "",
                            digest(subject, ""), context, correlation, elapsedTime, hooks -> {
                        try {
                            if (!hooks.prepare()) return result(UNAVAILABLE);
                            if (hookPublications != null && bindings.stream().anyMatch(binding ->
                                    binding.binding().phase() == OperationHook.Phase.BEFORE_INVOKE && !hookPublications.current(binding)))
                                return result(STALE);
                            Action<T> observed = action == null ? null : () -> {
                                invoked[0] = true;
                                T value = action.run();
                                completed.set(OperationHook.Outcome.SUCCEEDED);
                                return value;
                            };
                            return admitted(operation, subject, context, token, input, observed, started, correlation, snapshot, hooks);
                        } finally {
                            if (action != null) {
                                var value = completed.get();
                                hooks.after(value != null ? value : invoked[0] ? OperationHook.Outcome.FAILED : OperationHook.Outcome.BLOCKED, "{}");
                            }
                        }
                    });
                    } catch (ServiceAccessException unavailable) {
                        if (invoked[0]) throw unavailable;
                        outcome = "UNAVAILABLE";
                        return unavailable(correlation, unavailable.reason().name());
                    }
                    outcome = result.status().name();
                    return result;
                }
            } finally {
                LOG.info(getForLog("operation.log.finished", correlation, operation, outcome,
                        java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(elapsedTime.getAsLong() - started)));
                if (previousCorrelation == null) org.slf4j.MDC.remove("operationVerificationId");
                else org.slf4j.MDC.put("operationVerificationId", previousCorrelation);
            }
        }

        private <T> Result<T> admitted(String operation, String subject, String context,
                String token, Map<String, String> input, Action<T> action, long started, String correlation,
                WebPublicationStore.Snapshot snapshot, OperationHookCalls hooks) throws IOException {
            var published = snapshot.requirements(operation);
            var declared = pages.pages().stream().flatMap(page -> page.definition().targets().stream())
                    .filter(target -> target.operation().equals(operation)).toList();
            if (declared.isEmpty()) return unavailable(correlation, "TARGET_UNAVAILABLE");
            if (!declared.isEmpty() && declared.stream().map(target -> target.contextSchema()).distinct().count() != 1)
                return unavailable(correlation, "TARGET_AMBIGUOUS");
            String schema = declared.get(0).contextSchema();
            if (published.stream().anyMatch(expected -> !expected.contextSchema().equals(schema)))
                return unavailable(correlation, "CONTEXT_SCHEMA_CHANGED");
            try { RemoteValues.validateConfiguration(schema, context); }
            catch (IllegalArgumentException invalid) { return result(INVALID); }
            List<String> verificationPolicies = java.util.stream.Stream.concat(requirements.getOrDefault(operation, List.of()).stream(),
                    published.stream().map(WebPublicationStore.Requirement::policy)).distinct().filter(policy ->
                            snapshot.hooks(operation, "").stream().noneMatch(hook ->
                                    hook.binding().phase() == OperationHook.Phase.BEFORE_INVOKE && hook.binding().hook().equals(policy)))
                    .sorted().toList();
            var hookBindings = snapshot.hooks(operation, "");
            List<String> policies = java.util.stream.Stream.concat(verificationPolicies.stream(), hookBindings.stream()
                    .filter(item -> item.binding().phase() == OperationHook.Phase.BEFORE_INVOKE).map(item -> item.binding().hook())).distinct().sorted().toList();
            if (policies.size() > 16) return unavailable(correlation, "POLICY_LIMIT");
            Map<String, String> proofs = validateProofs(input, policies);
            if (proofs == null) return result(INVALID);
            boolean response = token != null && !token.isBlank();
            if (token != null && token.length() > 128 || !response && !proofs.isEmpty()) return result(STALE);
            String digest = digest(subject, context);
            Pending previous = response ? consume(token) : null;
            if (response && (previous == null || !previous.owner().equals(identity)
                    || !previous.operation().equals(operation) || !previous.digest().equals(digest)
                    || !previous.publicationRevision().equals(snapshot.identity())
                    || !previous.policies().equals(policies) || !previous.hooks().equals(hooks.identities()))) return result(STALE);
            String attempt = response ? token : UUID.randomUUID().toString();
            List<ServiceKey<OperationVerification>> keys = verificationPolicies.stream().map(OperationVerificationCapabilityAdapter::key).toList();
            boolean[] actionStarted = {false};
            try {
                return registry.withServices(services, OperationVerification.IMPORT, keys, List.of(), providers -> {
                    List<Identity> identities = new ArrayList<>();
                    for (var provider : providers) {
                        if (expired(started, correlation)) return result(UNAVAILABLE);
                        if (!verificationPolicies.get(identities.size()).equals(provider.service().policyId()))
                            return unavailable(correlation, "POLICY_ID_MISMATCH");
                        OperationVerification.Policy policy = OperationPolicies.validate(provider.service().policy());
                        String generation = provider.service().generation();
                        if (!validIdentity(generation)) return unavailable(correlation, "GENERATION_INVALID");
                        identities.add(new Identity(provider.publication(), policy, generation));
                    }
                    if (previous != null && !previous.identities().equals(identities)) return result(STALE);
                    for (int i = 0; i < providers.size(); i++) {
                        String policy = verificationPolicies.get(i);
                        Identity actual = identities.get(i);
                        for (var expected : published) if (expected.policy().equals(policy)
                                && (!expected.provider().equals(actual.publication().pluginId())
                                || !expected.packageId().equals(actual.publication().packageId())
                                || !expected.plan().equals(actual.policy())))
                            return unavailable(correlation, "PUBLISHED_POLICY_CHANGED policy=" + policy
                                    + " provider=" + actual.publication().pluginId());
                        if (requirements.getOrDefault(operation, List.of()).contains(policy)) {
                            if (published.stream().anyMatch(expected -> expected.policy().equals(policy))) {
                                configuredPlans.put(policy, actual.policy());
                                continue;
                            }
                            var configured = configuredPlans.putIfAbsent(policy, actual.policy());
                            if (configured != null && !configured.equals(actual.policy()))
                                return unavailable(correlation, "CONFIGURED_POLICY_CHANGED policy=" + policy);
                        }
                    }
                    Map<String, String> challenges = new LinkedHashMap<>();
                    var allHooks = hooks.withVerifications(providers);
                    if (!allHooks.prepare()) return result(UNAVAILABLE);
                    Status hookStatus = allHooks.before(attempt, response, proofs, challenges);
                    if (hookStatus != APPROVED) return result(hookStatus);
                    // 配置或凭据在验证期间变化也不能继续执行业务。
                    for (int i = 0; i < providers.size(); i++) {
                        if (!verificationPolicies.get(i).equals(providers.get(i).service().policyId())
                                || !identities.get(i).policy().equals(providers.get(i).service().policy())
                                || !identities.get(i).generation().equals(providers.get(i).service().generation()))
                            return result(STALE);
                    }
                    if (expired(started, correlation)) return result(UNAVAILABLE);
                    if (!allHooks.current()) return result(STALE);
                    if (challenges.values().stream().mapToInt(value -> value.getBytes(StandardCharsets.UTF_8).length).sum() > 65536)
                        return result(UNAVAILABLE);
                    if (challenges.isEmpty()) {
                        if (action == null) return new Result<>(APPROVED, null, "", snapshot.identity(), Map.of());
                        actionStarted[0] = true;
                        LOG.info(getForLog("operation.log.action", correlation, operation));
                        return new Result<>(APPROVED, action.run(), "", snapshot.identity(), Map.of());
                    }
                    if (response) return result(REJECTED);
                    if (!retain(attempt, new Pending(identity, operation, digest, policies, List.copyOf(identities), hooks.identities(),
                            snapshot.identity(), clock.millis() + 300_000))) return unavailable(correlation, "PENDING_CAPACITY");
                    return new Result<>(CHALLENGE, null, attempt, snapshot.identity(), challenges);
                });
            } catch (ServiceAccessException unavailable) {
                // 业务已开始后的异常属于业务 owner，不能伪装成可重试的验证失败。
                if (actionStarted[0]) throw unavailable;
                return unavailable(correlation, unavailable.reason().name() + " policies=" + policies);
            } catch (IllegalArgumentException invalidPolicy) {
                if (actionStarted[0]) throw invalidPolicy;
                return unavailable(correlation, "INVALID_POLICY");
            }
        }
    }

    private static <T> OperationGuard.Result<T> unavailable(String correlation, String reason) {
        LOG.warn(getForLog("operation.log.unavailable", correlation, reason));
        return result(UNAVAILABLE);
    }

    private boolean expired(long started, String correlation) {
        // 不取消正在执行的 provider；其实际退出前继续持有租约，超时结果绝不触发业务动作。
        boolean expired = elapsedTime.getAsLong() - started >= VERIFICATION_NANOS || Thread.currentThread().isInterrupted();
        if (expired) LOG.info(getForLog("operation.log.timeout", correlation));
        return expired;
    }

    private static boolean validIdentity(String value) {
        return value != null && !value.isBlank() && value.length() <= 128
                && value.matches("[a-zA-Z0-9:._-]+");
    }

    private static String digest(String subject, String context) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] subjectBytes = subject.getBytes(StandardCharsets.UTF_8);
            digest.update(java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(subjectBytes.length).array());
            digest.update(subjectBytes);
            return HexFormat.of().formatHex(digest.digest(context.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
    }

    private static Map<String, String> validateProofs(Map<String, String> input, List<String> policies) {
        if (input == null || input.size() > 16) return null;
        int bytes = 0;
        Map<String, String> safe = new LinkedHashMap<>();
        for (var entry : input.entrySet()) {
            String value = entry.getValue();
            if (!policies.contains(entry.getKey()) || value == null || value.length() > 16384) return null;
            int size = value.getBytes(StandardCharsets.UTF_8).length;
            bytes += size;
            if (size > 16384 || bytes > 65536) return null;
            safe.put(entry.getKey(), value);
        }
        return Map.copyOf(safe);
    }

    private static <T> OperationGuard.Result<T> result(OperationGuard.Status status) {
        return new OperationGuard.Result<>(status, null, "", "", Map.of());
    }

    private synchronized Pending consume(String token) { expire(); return pending.remove(token); }

    private synchronized boolean retain(String token, Pending value) {
        expire();
        if (pending.size() >= 256) return false;
        pending.put(token, value);
        return true;
    }

    private void expire() { pending.values().removeIf(item -> item.expires() <= clock.millis()); }

    @jakarta.annotation.PreDestroy
    void close() { synchronized (this) { pending.clear(); } }
}
