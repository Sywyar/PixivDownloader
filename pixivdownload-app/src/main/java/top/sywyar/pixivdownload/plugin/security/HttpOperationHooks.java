package top.sywyar.pixivdownload.plugin.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.async.*;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import top.sywyar.pixivdownload.plugin.api.operation.*;
import top.sywyar.pixivdownload.plugin.api.security.OperationGuard;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityInvocationRegistry;
import top.sywyar.pixivdownload.plugin.web.page.*;

/** MVC 选择真实 handler 后统一执行；异步重派发复用同一请求身份。 */
@Component
public final class HttpOperationHooks implements AsyncHandlerInterceptor, ServletRequestListener {
    private static final String ATTRIBUTE = HttpOperationHooks.class.getName();
    private final Executor executor;
    private final OperationEndpoints endpoints;
    private final WebPublicationStore publications;
    private final ExternalCapabilityInvocationRegistry registry;
    private final OperationAdmissions admissions;
    private final HookChallenges challenges;
    private final HookPublications contracts;
    private final Map<String, List<String>> required;
    private final Map<String, WebPublicationStore.HookRequirement> configured = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.Semaphore active = new java.util.concurrent.Semaphore(64);

    public HttpOperationHooks(OperationEndpoints endpoints, WebPublicationStore publications,
            ExternalCapabilityInvocationRegistry registry, OperationAdmissions admissions, HookChallenges challenges, HookPublications contracts,
            List<OperationRequirement> requirements, @org.springframework.beans.factory.annotation.Qualifier("operationHookExecutor") Executor executor) {
        this.executor = executor;
        this.endpoints = endpoints; this.publications = publications; this.registry = registry; this.admissions = admissions;
        this.challenges = challenges;
        this.contracts = contracts;
        this.required = OperationRequirement.collect(requirements);
    }

    @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        if (!(handler instanceof HandlerMethod method)) return true;
        if (request.getAttribute(ATTRIBUTE) instanceof Execution previous) return previous.allowed;
        var endpoint = endpoints.matched(request, method);
        if (endpoint == null) {
            if (!HookRequestFilter.isPreparation(request)) return true;
            reject(response, "TARGET_INVALID", 400, Map.of()); return false;
        }
        WebPublicationStore.Snapshot snapshot;
        try { snapshot = publications.snapshot(); }
        catch (IllegalStateException unavailable) { reject(response, "UNAVAILABLE", 503, Map.of()); return false; }
        var preparation = (HookRequestFilter.Preparation) request.getAttribute(HookRequestFilter.PREPARATION);
        if (preparation != null && preparation.resolveOnly()) {
            response.setContentType("application/json"); response.setCharacterEncoding("UTF-8");
            response.setHeader("Cache-Control", "no-store");
            response.getWriter().write(HookRequestFilter.JSON.writeValueAsString(new TargetMatch("HOOK_RESOLVED", false, endpoint.id())));
            return false;
        }
        var bindings = snapshot.hooks(endpoint.operation(), endpoint.id());
        var selectedBindings = bindings;
        String route = String.valueOf(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE));
        // 只比较 MVC 实际选中的声明；旧保护失效时拒绝关联的新 handler，不把旧回调套给它。
        var displaced = snapshot.hooks().filter(binding -> !selectedBindings.contains(binding)
                && binding.binding().phase() == OperationHook.Phase.BEFORE_INVOKE
                && endpoints.anchoredAt(binding, endpoint, route, request))
                .filter(binding -> !contracts.current(binding)).findFirst();
        if (displaced.isPresent()) {
            OperationHookCalls.skipped(displaced.get(), endpoint.id(), "TARGET_STALE");
            reject(response, "TARGET_STALE", 409, Map.of()); return false;
        }
        var available = new ArrayList<WebPublicationStore.HookRequirement>();
        for (var binding : bindings) {
            if (contracts.current(binding)) available.add(binding);
            else if (binding.binding().phase() == OperationHook.Phase.BEFORE_INVOKE) {
                reject(response, "TARGET_STALE", 409, Map.of()); return false;
            } else OperationHookCalls.skipped(binding, endpoint.id(), "TARGET_STALE");
        }
        for (var legacy : snapshot.requirements(endpoint.operation())) {
            if (available.stream().anyMatch(item -> item.binding().phase() == OperationHook.Phase.BEFORE_INVOKE && item.binding().hook().equals(legacy.policy()))) continue;
            var binding = new OperationBinding("published-" + legacy.policy(), legacy.policy(), endpoint.operation(), "",
                    OperationHook.Phase.BEFORE_INVOKE, 0, OperationHook.Completion.ALL, "{}");
            var plan = legacy.plan();
            var definition = new OperationHook.Definition(plan.revision(), Set.of(OperationHook.Phase.BEFORE_INVOKE),
                    "{\"type\":\"object\",\"properties\":{},\"required\":[],\"additionalProperties\":false}", plan.contextSchema(), "", plan.protocol(), plan.resources());
            available.add(new WebPublicationStore.HookRequirement(binding, endpoint.operation(), "", "", legacy.provider(), legacy.packageId(), definition, ""));
        }
        bindings = List.copyOf(available);
        var requiredPolicies = required.getOrDefault(endpoint.operation(), List.of());
        var operations = new HashSet<String>();
        operations.add(endpoint.id()); operations.add(endpoint.operation());
        bindings.forEach(binding -> operations.add(binding.operation()));
        snapshot.configuration().settings().groups().forEach((group, members) -> { if (members.contains(endpoint.id())) operations.add(group); });
        if (bindings.isEmpty() && requiredPolicies.isEmpty()) {
            if (preparation != null) { reply(response, "APPROVED", 200, "", snapshot.identity(), Map.of()); return false; }
            OperationAdmissions.Lease admission;
            try { admission = admissions.enter(operations); }
            catch (IllegalStateException unavailable) { reject(response, "UNAVAILABLE", 503, Map.of()); return false; }
            // 尚无 Hook 的请求也参与首次发布排空，但不占用回调执行预算。
            try {
                if (!snapshot.identity().equals(publications.snapshot().identity())) throw new IllegalStateException("HOOK_STALE");
            } catch (RuntimeException stale) { admission.close(); reject(response, "STALE", 409, Map.of()); return false; }
            var execution = new Execution(admission, null, null, null, response, List.of(), executor);
            request.setAttribute(ATTRIBUTE, execution);
            return allow(request, execution);
        }
        if (!active.tryAcquire()) { reject(response, "UNAVAILABLE", 503, Map.of()); return false; }
        OperationAdmissions.Lease admission;
        try { admission = admissions.enter(operations); }
        catch (IllegalStateException unavailable) { active.release(); reject(response, "UNAVAILABLE", 503, Map.of()); return false; }
        var sources = new TreeMap<String, String>();
        for (var binding : bindings) {
            var pages = snapshot.pages().entrySet().stream().filter(page -> page.getValue().hooks().contains(binding))
                    .map(Map.Entry::getKey).sorted().toList();
            String source = (snapshot.configuration().requirements().contains(binding) ? "configuration;" : "") + "pages=" + String.join(",", pages);
            sources.put(binding.binding().id(), source.length() <= 1024 ? source : source.substring(0, 1024));
        }
        var http = new OperationHook.Http(endpoint.owner(), endpoint.handler(), request.getMethod(), route, endpoint.shape(), 0, 0, "");
        String subject;
        HookChallenges.Target target;
        Map<String, String> inputs;
        top.sywyar.pixivdownload.plugin.lifecycle.request.PluginRequestLease targetLease = null;
        try {
            inputs = HookFields.inputs(request, bindings);
            subject = challenges.subject(request, response);
            var proofInputs = new TreeMap<String, String>();
            for (var binding : bindings) if (binding.binding().phase() == OperationHook.Phase.BEFORE_INVOKE)
                proofInputs.put(binding.binding().id(), inputs.get(binding.binding().id()));
            target = challenges.target(request, endpoint, snapshot.identity(), subject, HookRequestFilter.JSON.writeValueAsString(proofInputs));
            targetLease = endpoints.retain(endpoint);

        } catch (RuntimeException | IOException failure) {
            if (targetLease != null) targetLease.close();
            admission.close(); active.release(); reject(response, "UNAVAILABLE", 503, Map.of()); return false;
        }
        var execution = new Execution(admission, targetLease, active, http, response, bindings, executor);
        var retainedBindings = bindings;
        request.setAttribute(ATTRIBUTE, execution);
        try {
            if (!execution.await(() -> {
                execution.hooks = OperationHookCalls.open(registry, retainedBindings, endpoint, subject, "{}", UUID.randomUUID().toString(), http, sources, requiredPolicies, configured);
                execution.hooks.inputs(inputs);
                return execution.hooks.prepare();
            })) {
                reject(response, "UNAVAILABLE", 503, Map.of()); execution.complete(OperationHook.Outcome.BLOCKED, "UNAVAILABLE"); return false;
            }
            var hooks = execution.hooks;
            if (preparation != null && preparation.token() != null && !preparation.token().isEmpty()) {
                boolean staged = challenges.stage(preparation.token(), target.digest(), hooks.identities(), preparation.proofs());
                if (staged && preparation.nativeSubmit()) challenges.nativeTicket(request, response, preparation.token(), target);
                reply(response, staged ? "READY" : "STALE", staged ? 200 : 409, staged ? preparation.token() : "", snapshot.identity(), Map.of());
                execution.complete(OperationHook.Outcome.BLOCKED, staged ? "PROOF_STAGED" : "STALE"); return false;
            }
            var supplied = preparation == null ? challenges.consume(request, response, target, hooks.identities()) : new HookChallenges.Ticket("", null);
            String ticket = supplied.token();
            var previous = supplied.proof();
            if (!ticket.isEmpty() && previous == null) {
                reject(response, "STALE", 409, Map.of()); execution.complete(OperationHook.Outcome.BLOCKED, "STALE"); return false;
            }
            String attempt = ticket.isEmpty() ? UUID.randomUUID().toString() : ticket;
            var offered = new LinkedHashMap<String, String>();
            var status = execution.await(() -> hooks.before(attempt, previous != null, previous == null ? Map.of() : previous.proofs(), offered));
            if (status != OperationGuard.Status.APPROVED || !offered.isEmpty()) {
                boolean challenged = status == OperationGuard.Status.APPROVED && !offered.isEmpty();
                if (challenged && !challenges.retain(attempt, target.digest(), hooks.identities())) {
                    status = OperationGuard.Status.UNAVAILABLE; challenged = false;
                }
                reply(response, challenged ? "CHALLENGE" : status.name(), status == OperationGuard.Status.REJECTED ? 403 : challenged ? 409 : 503,
                        challenged ? attempt : "", snapshot.identity(), challenged ? offered : Map.of());
                execution.complete(OperationHook.Outcome.BLOCKED, status.name());
                return false;
            }
            if (!snapshot.identity().equals(publications.snapshot().identity()) || !execution.await(hooks::current)) {
                reject(response, "STALE", 409, Map.of()); execution.complete(OperationHook.Outcome.BLOCKED, "STALE"); return false;
            }
            if (preparation != null) {
                reply(response, "APPROVED", 200, "", snapshot.identity(), Map.of());
                execution.complete(OperationHook.Outcome.BLOCKED, "PREPARED"); return false;
            }
            return allow(request, execution);
        } catch (IOException failure) {
            execution.complete(OperationHook.Outcome.BLOCKED, "HTTP_CANCELLED"); throw failure;
        } catch (RuntimeException failure) {
            reject(response, "UNAVAILABLE", 503, Map.of());
            execution.complete(OperationHook.Outcome.BLOCKED, execution.expired ? "HOOK_TIMEOUT" : "HOOK_FAILURE"); return false;
        }
    }

    private boolean allow(HttpServletRequest request, Execution execution) {
        execution.allowed = true;
        var async = WebAsyncUtils.getAsyncManager(request);
        async.registerCallableInterceptor(ATTRIBUTE, new CallableProcessingInterceptor() {
            @Override public <T> void preProcess(NativeWebRequest request, Callable<T> task) { execution.workerStarted(); }
            @Override public <T> void postProcess(NativeWebRequest request, Callable<T> task, Object result) { execution.workerFinished(); }
            @Override public <T> Object handleTimeout(NativeWebRequest request, Callable<T> task) {
                execution.mark(OperationHook.Outcome.TIMED_OUT, "HTTP_TIMEOUT"); return RESULT_NONE;
            }
            @Override public <T> Object handleError(NativeWebRequest request, Callable<T> task, Throwable error) {
                execution.mark(OperationHook.Outcome.CANCELLED, "HTTP_CANCELLED"); return RESULT_NONE;
            }
            @Override public <T> void afterCompletion(NativeWebRequest request, Callable<T> task) { execution.httpCompleted(); }
        });
        async.registerDeferredResultInterceptor(ATTRIBUTE, new DeferredResultProcessingInterceptor() {
            @Override public <T> boolean handleTimeout(NativeWebRequest request, DeferredResult<T> result) {
                execution.mark(OperationHook.Outcome.TIMED_OUT, "HTTP_TIMEOUT"); return true;
            }
            @Override public <T> boolean handleError(NativeWebRequest request, DeferredResult<T> result, Throwable error) {
                execution.mark(OperationHook.Outcome.CANCELLED, "HTTP_CANCELLED"); return true;
            }
            @Override public <T> void afterCompletion(NativeWebRequest request, DeferredResult<T> result) { execution.httpCompleted(); }
        });
        return true;
    }

    @Override public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception failure) {
        if (request.getAttribute(ATTRIBUTE) instanceof Execution execution) {
            if (failure != null) execution.mark(OperationHook.Outcome.FAILED, "HTTP_EXCEPTION");
        }
    }

    @Override public void requestDestroyed(ServletRequestEvent event) {
        if (event.getServletRequest().getAttribute(ATTRIBUTE) instanceof Execution execution) execution.httpCompleted();
    }

    /** 只交出已通过前置检查的当前宿主请求租约，不接受客户端声明的排空豁免。 */
    public static OperationAdmissions.Lease publicationAdmission() {
        var attributes = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        return attributes instanceof org.springframework.web.context.request.ServletRequestAttributes current
                ? publicationAdmission(current.getRequest()) : null;
    }

    public static OperationAdmissions.Lease publicationAdmission(HttpServletRequest request) {
        if (!(request.getAttribute(ATTRIBUTE) instanceof Execution execution)) return null;
        synchronized (execution) { return execution.allowed && !execution.completed ? execution.admission : null; }
    }

    @Override public void afterConcurrentHandlingStarted(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(request.getAttribute(ATTRIBUTE) instanceof Execution execution)) return;
        try { request.getAsyncContext().addListener(execution); }
        catch (IllegalStateException completed) { execution.httpCompleted(); }
    }

    static void reject(HttpServletResponse response, String code, int status, Map<String, String> challenges) throws IOException {
        reply(response, code, status, "", "", challenges);
    }
    private record TargetMatch(String code, boolean invoked, String endpoint) { }
    private static void reply(HttpServletResponse response, String code, int status, String token, String revision,
            Map<String, String> challenges) throws IOException {
        response.setStatus(status); response.setContentType("application/json"); response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store"); response.setHeader("X-Pixiv-Hook", code);
        String message = status < 400 ? "" : top.sywyar.pixivdownload.i18n.MessageBundles.get(
                org.springframework.context.i18n.LocaleContextHolder.getLocale(), "hooks."
                        + (code.equals("CHALLENGE") ? "required" : code.equals("STALE") || code.equals("TARGET_STALE") ? "stale" : code.equals("REJECTED") ? "rejected" : "unavailable"));
        response.getWriter().write(HookRequestFilter.JSON.writeValueAsString(new HookChallenges.Response("HOOK_" + code, message, false, token, revision, challenges)));
    }

    private static final class Execution implements AsyncListener {
        private volatile OperationHookCalls hooks;
        private final Executor executor;
        private volatile boolean expired;
        private OperationAdmissions.Lease admission;
        private top.sywyar.pixivdownload.plugin.lifecycle.request.PluginRequestLease targetLease;
        private final java.util.concurrent.Semaphore active;
        private final OperationHook.Http target;
        private final List<WebPublicationStore.HookRequirement> bindings;
        private Map<String, String> results = Map.of();
        private HttpServletResponse response;
        private int status;
        private final long started = System.nanoTime();
        private OperationHook.Outcome outcome;
        private String reason = "HTTP_COMPLETED";
        private boolean allowed, completed, finished, workerActive;
        Execution(OperationAdmissions.Lease admission,
                top.sywyar.pixivdownload.plugin.lifecycle.request.PluginRequestLease targetLease,
                java.util.concurrent.Semaphore active, OperationHook.Http target, HttpServletResponse response,
                List<WebPublicationStore.HookRequirement> bindings, Executor executor) {
            this.executor = executor; this.admission = admission; this.targetLease = targetLease; this.active = active;
            this.target = target; this.response = response;
            this.bindings = bindings;
            if (bindings.stream().flatMap(binding -> binding.binding().fields().stream()).anyMatch(field -> field.source() == OperationBinding.Source.RESULT)) {
                var capture = org.springframework.web.util.WebUtils.getNativeResponse(response, HookResponse.class);
                if (capture != null) capture.capture();
            }
        }
        <T> T await(Callable<T> call) {
            var result = new CompletableFuture<T>();
            workerStarted();
            try {
                executor.execute(() -> {
                    T value = null; Throwable failure = null;
                    try {
                        if (expired) throw new IllegalStateException("HOOK_TIMEOUT");
                        value = call.call();
                    } catch (Throwable error) { failure = error; }
                    finally { workerFinished(); }
                    if (failure == null) result.complete(value); else result.completeExceptionally(failure);
                });
            } catch (RuntimeException rejected) { workerFinished(); throw rejected; }
            try { return result.get(Math.max(1, TimeUnit.SECONDS.toNanos(10) - (System.nanoTime() - started)), TimeUnit.NANOSECONDS); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); expire(); throw new IllegalStateException("HOOK_CANCELLED");
            } catch (TimeoutException timeout) { expire(); throw new IllegalStateException("HOOK_TIMEOUT"); }
            catch (ExecutionException failure) {
                if (failure.getCause() instanceof Error fatal) throw fatal;
                if (failure.getCause() instanceof RuntimeException error) throw error;
                throw new IllegalStateException("HOOK_FAILURE");
            }
        }
        private void expire() {
            expired = true;
            var current = hooks;
            if (current != null) current.deadline(OperationHook.Phase.BEFORE_INVOKE);
        }
        synchronized void workerStarted() {
            // 排队任务若在 HTTP 结束后才开始，拒绝进入原 Callable，不能复活已关闭的代际。
            if (finished) throw new IllegalStateException("HOOK_REQUEST_COMPLETED");
            workerActive = true;
        }
        void workerFinished() { synchronized (this) { workerActive = false; } finish(true); }
        synchronized void mark(OperationHook.Outcome value, String reason) { if (outcome == null) { outcome = value; this.reason = reason; } }
        void complete(OperationHook.Outcome value, String reason) { mark(value, reason); httpCompleted(); }
        void httpCompleted() {
            synchronized (this) {
                if (!completed) {
                    status = response.getStatus();
                    var capture = org.springframework.web.util.WebUtils.getNativeResponse(response, HookResponse.class);
                    if (capture != null) results = HookFields.results(bindings, capture.captured(), response.getContentType());
                    response = null;
                    completed = true;
                }
            }
            finish(false);
        }
        private void finish(boolean onWorker) {
            synchronized (this) {
                if (finished || !completed || workerActive) return;
                finished = true;
                if (outcome == null) outcome = !allowed ? OperationHook.Outcome.BLOCKED
                        : status >= 400 ? OperationHook.Outcome.FAILED : OperationHook.Outcome.SUCCEEDED;
            }
            if (hooks == null) { release(); return; }
            var observed = new CompletableFuture<Void>();
            try { executor.execute(() -> {
                try { observe(); observed.complete(null); }
                catch (Throwable failure) { observed.completeExceptionally(failure); if (failure instanceof Error fatal) throw fatal; }
            }); }
            catch (RuntimeException rejected) {
                bindings.stream().filter(binding -> binding.binding().phase() == OperationHook.Phase.AFTER_COMPLETION)
                        .forEach(binding -> OperationHookCalls.skipped(binding, "", "EXECUTOR_UNAVAILABLE"));
                release(); return;
            }
            if (onWorker) {
                observed.orTimeout(10, TimeUnit.SECONDS).whenComplete((ignored, failure) -> {
                    if (failure instanceof TimeoutException) {
                        var current = hooks; if (current != null) current.deadline(OperationHook.Phase.AFTER_COMPLETION);
                    }
                });
                return;
            }
            try { observed.get(10, TimeUnit.SECONDS); }
            catch (TimeoutException timeout) { var current = hooks; if (current != null) current.deadline(OperationHook.Phase.AFTER_COMPLETION); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch (ExecutionException failure) { if (failure.getCause() instanceof Error fatal) throw fatal; }
        }
        private void observe() {
            try {
                if (hooks == null) return;
                hooks.http(new OperationHook.Http(target.owner(), target.handler(), target.method(), target.route(), target.shape(),
                        status, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), reason));
                hooks.results(results); hooks.after(outcome, "{}");
            } finally { release(); }
        }
        private void release() {
            results = Map.of();
            try { if (hooks != null) hooks.close(); }
            finally {
                try { if (targetLease != null) targetLease.close(); }
                finally {
                    try { admission.close(); }
                    finally { if (active != null) active.release(); hooks = null; admission = null; targetLease = null; }
                }
            }
        }
        @Override public void onComplete(AsyncEvent event) { httpCompleted(); }
        @Override public void onTimeout(AsyncEvent event) { mark(OperationHook.Outcome.TIMED_OUT, "HTTP_TIMEOUT"); }
        @Override public void onError(AsyncEvent event) { mark(OperationHook.Outcome.CANCELLED, "HTTP_CANCELLED"); }
        @Override public void onStartAsync(AsyncEvent event) { event.getAsyncContext().addListener(this); }
    }
}
