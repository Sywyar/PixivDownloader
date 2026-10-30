package top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime;

import top.sywyar.pixivdownload.plugin.api.resource.PluginResourceScope;
import top.sywyar.pixivdownload.plugin.api.service.*;
import top.sywyar.pixivdownload.plugin.runtime.task.PluginRuntimeTaskRegistry;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** 只协调依赖资源，不执行业务重试；与服务 publication 共用一个锁和租约。 */
final class ExternalServiceBindings {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ExternalServiceBindings.class);
    private final ExternalCapabilityInvocationRegistry registry;
    private final Object lock;
    private final Set<Binding> bindings = new LinkedHashSet<>();
    private final ThreadLocal<Boolean> callbackThread = new ThreadLocal<>();
    private PluginRuntimeTaskRegistry tasks;
    private Executor executor;
    private long nextBindingId;
    private long relationshipRevision;

    ExternalServiceBindings(ExternalCapabilityInvocationRegistry registry, Object lock) {
        this.registry = registry;
        this.lock = lock;
    }

    void configure(PluginRuntimeTaskRegistry tasks, Executor executor) {
        synchronized (lock) {
            this.tasks = Objects.requireNonNull(tasks);
            this.executor = Objects.requireNonNull(executor);
        }
    }

    boolean onCallbackThread() { return callbackThread.get() != null; }

    ServiceBinding bind(ExternalCapabilityOwner owner, List<ServiceKey<?>> keys, boolean multiple,
            Consumer<ServiceBindingScope> activate) {
        Objects.requireNonNull(activate, "activate");
        keys = ServiceDeclarations.copy(keys);
        if (keys.isEmpty() || new HashSet<>(keys).size() != keys.size()) {
            throw new IllegalArgumentException("SERVICE_BINDING_DEPENDENCIES");
        }
        synchronized (lock) {
            if (executor == null) throw failure(ServiceAccessException.Reason.UNSUPPORTED);
            registry.validateBindingImports(owner, keys);
            if (bindings.stream().filter(b -> b.owner.equals(owner)).count() >= 128) {
                throw failure(ServiceAccessException.Reason.CAPACITY);
            }
            Binding binding = new Binding(owner, keys, multiple, activate);
            bindings.add(binding);
            changed();
            return binding;
        }
    }

    /** 调用者可持 publication 锁；这里只撤回内存入口，绝不执行插件回调。 */
    void changed() {
        synchronized (lock) {
            relationshipRevision++;
            for (Binding binding : List.copyOf(bindings)) {
                if (registry.bindingOwnerGone(binding.owner)) binding.closed = true;
                if (binding.closed && binding.round == null) { binding.detach(); continue; }
                if (binding.round != null && !binding.current(binding.round)) binding.round.seal();
                if (binding.state == ServiceBinding.State.CLOSE_FAILED) binding.retry = true;
                binding.schedule();
            }
        }
    }

    boolean pending(String pluginId) {
        synchronized (lock) {
            return bindings.stream().anyMatch(b -> b.round != null && b.round.owners.contains(pluginId));
        }
    }

    List<ServiceBindingDiagnostic> diagnostics(ExternalCapabilityOwner owner) {
        synchronized (lock) {
            return bindings.stream().filter(binding -> binding.owner.equals(owner))
                    .map(Binding::diagnostic).toList();
        }
    }

    long relationshipRevision() { synchronized (lock) { return relationshipRevision; } }

    Set<String> consumersOf(String provider) {
        synchronized (lock) {
            Set<String> result = new HashSet<>();
            for (Binding binding : bindings)
                if (binding.round != null && binding.round.owners.contains(provider)) result.add(binding.owner.pluginId());
            return result;
        }
    }

    private final class Binding implements ServiceBinding {
        private final ExternalCapabilityOwner owner;
        private final long id;
        private List<ServiceKey<?>> keys;
        private final boolean multiple;
        private Consumer<ServiceBindingScope> activate;
        private State state = State.WAITING;
        private boolean closed;
        private boolean retry;
        private Map<ServiceKey<?>, List<ExternalCapabilityHandle>> failedSelection;
        private Round round;
        private boolean queued;
        private boolean dirty;
        private String code = "CONSUMER_NOT_ACTIVE";
        private ServiceDiagnosticProjection.Capture evidence = ServiceDiagnosticProjection.Capture.EMPTY;

        private void reason(String value) {
            if (!value.equals(code)) relationshipRevision++;
            if (!value.equals(code) && (value.endsWith("FAILED") || value.equals("SCHEDULING_REJECTED"))) {
                log.warn("PLUGIN_SERVICE_BINDING_FAILURE consumer={} generation={} publication={} binding={} code={}",
                        owner.pluginId(), owner.pluginGeneration(), owner.publicationId(), id, value);
            }
            code = value;
        }

        private void detach() {
            state = State.CLOSED;
            reason("CLOSED");
            keys = List.of();
            activate = null;
            failedSelection = null;
            bindings.remove(this);
            lock.notifyAll();
        }

        private void schedule() {
            dirty = true;
            if (queued || executor == null || state == State.CLOSED) return;
            queued = true;
            try { executor.execute(this::run); }
            catch (RuntimeException rejected) {
                queued = false;
                reason("SCHEDULING_REJECTED");
            }
        }

        private void run() {
            try {
                while (true) {
                    synchronized (lock) {
                        if (!dirty) return;
                        dirty = false;
                    }
                    reconcile();
                }
            } finally {
                synchronized (lock) {
                    queued = false;
                    if (dirty) schedule();
                }
            }
        }

        Binding(ExternalCapabilityOwner owner, List<ServiceKey<?>> keys, boolean multiple,
                Consumer<ServiceBindingScope> activate) {
            this.owner = owner;
            this.id = Math.incrementExact(nextBindingId);
            nextBindingId = id;
            this.keys = keys;
            this.multiple = multiple;
            this.activate = activate;
        }

        @Override public State state() { synchronized (lock) { return state; } }
        @Override public ServiceBindingDiagnostic diagnostic() {
            synchronized (lock) {
                return new ServiceBindingDiagnostic(id, ExternalCapabilityInvocationRegistry.publication(owner),
                        state, code, evidence.dependencies(), evidence.truncated());
            }
        }
        @Override public void retry() { synchronized (lock) { retry = true; schedule(); } }
        @Override public void close() {
            synchronized (lock) {
                closed = true;
                retry = true;
                if (round != null) round.seal();
                else { detach(); return; }
                schedule();
            }
        }

        private boolean current(Round candidate) {
            if (closed || !candidate.accepting) return false;
            try { return candidate.selection.equals(registry.selectBinding(owner, keys, multiple)); }
            catch (ServiceAccessException unavailable) { return false; }
        }

        private void reconcile() {
            Round previous;
            boolean explicitRetry;
            synchronized (lock) {
                explicitRetry = retry;
                retry = false;
                previous = round;
                if (previous != null) {
                    if (current(previous)) {
                        state = State.ACTIVE;
                        reason("ACTIVE");
                        return;
                    }
                    previous.seal();
                    if (previous.running != 0) return;
                    if (state == State.CLOSE_FAILED && !explicitRetry) return;
                    state = State.DRAINING;
                }
            }
            if (previous != null) {
                Boolean before = callbackThread.get();
                callbackThread.set(true);
                try {
                    previous.resources.close();
                    synchronized (lock) { previous.clear(); round = null; lock.notifyAll(); }
                } catch (Throwable closeFailure) {
                    synchronized (lock) { state = State.CLOSE_FAILED; reason("CLOSE_FAILED"); }
                    ExternalCapabilityInvocationRegistry.rethrowFatal(closeFailure);
                    return;
                } finally {
                    if (before == null) callbackThread.remove(); else callbackThread.set(before);
                }
            }
            Round next;
            Consumer<ServiceBindingScope> callback;
            synchronized (lock) {
                if (closed || registry.bindingOwnerGone(owner)) {
                    detach();
                    return;
                }
                Map<ServiceKey<?>, List<ExternalCapabilityHandle>> selection;
                try { selection = registry.selectBinding(owner, keys, multiple); }
                catch (ServiceAccessException unavailable) {
                    state = unavailable.reason() == ServiceAccessException.Reason.AMBIGUOUS
                            ? State.AMBIGUOUS : State.WAITING;
                    reason(unavailable.reason() == ServiceAccessException.Reason.NOT_ACTIVE
                            ? "CONSUMER_NOT_ACTIVE" : unavailable.reason().name());
                    evidence = registry.describeServices(keys);
                    failedSelection = null;
                    return;
                }
                if (!explicitRetry && selection.equals(failedSelection)) {
                    state = State.FAILED;
                    reason("ACTIVATION_FAILED");
                    return;
                }
                evidence = ServiceDiagnosticProjection.capture(keys, key -> selection.get(key).stream()
                        .map(handle -> ExternalCapabilityInvocationRegistry.publication(handle.owner())).toList());
                PluginResourceScope resources;
                try { resources = tasks.resourcesForPlugin(owner.pluginId()).openScope(); }
                catch (RuntimeException unavailable) { state = State.WAITING; reason("RESOURCE_UNAVAILABLE"); return; }
                next = new Round(this, selection, resources,
                        registry.holdBinding(owner, selection.values().stream().flatMap(List::stream).toList()));
                round = next;
                state = State.ACTIVATING;
                reason("ACTIVATING");
                callback = activate;
            }
            boolean succeeded = false;
            try {
                boolean[] entered = {false};
                next.guard(() -> { callback.accept(next); entered[0] = true; }).run();
                succeeded = entered[0];
            } catch (Throwable activationFailure) {
                synchronized (lock) { reason("ACTIVATION_FAILED"); }
                ExternalCapabilityInvocationRegistry.rethrowFatal(activationFailure);
            } finally {
                synchronized (lock) {
                    if (!succeeded) { failedSelection = next.selection; next.seal(); }
                    if (current(next)) { state = State.ACTIVE; reason("ACTIVE"); }
                    else { next.seal(); schedule(); }
                }
            }
        }
    }

    private final class Round implements ServiceBindingScope {
        private final Binding binding;
        private Map<ServiceKey<?>, List<ExternalCapabilityHandle>> selection;
        private final Set<String> owners;
        private PluginResourceScope resources;
        private Runnable release;
        private final List<Guard> guards = new ArrayList<>();
        private boolean accepting = true;
        private int running;

        Round(Binding binding, Map<ServiceKey<?>, List<ExternalCapabilityHandle>> selection,
                PluginResourceScope resources, Runnable release) {
            this.binding = binding;
            this.selection = selection;
            this.resources = resources;
            this.release = release;
            Set<String> ids = new HashSet<>();
            ids.add(binding.owner.pluginId());
            selection.values().forEach(members -> members.forEach(h -> ids.add(h.owner().pluginId())));
            owners = Set.copyOf(ids);
        }

        private void seal() {
            accepting = false;
            guards.forEach(g -> { g.callback = null; g.tracked = null; });
            guards.clear();
            if (binding.state != ServiceBinding.State.CLOSE_FAILED) {
                binding.state = ServiceBinding.State.DRAINING;
                if (!binding.code.endsWith("FAILED")) binding.reason("DRAINING");
            }
        }

        private void clear() {
            seal();
            release.run();
            resources = null;
            selection = Map.of();
            release = null;
        }

        private <T> T execute(Supplier<T> action, boolean optional) {
            synchronized (lock) {
                if (!binding.current(this)) {
                    seal();
                    binding.schedule();
                    if (optional) return null;
                    throw failure(ServiceAccessException.Reason.UNAVAILABLE);
                }
                running++;
            }
            Boolean before = callbackThread.get();
            callbackThread.set(true);
            try { return action.get(); }
            finally {
                if (before == null) callbackThread.remove(); else callbackThread.set(before);
                synchronized (lock) { running--; if (!accepting) binding.schedule(); }
            }
        }

        @Override public <T> T require(ServiceKey<T> key) {
            var members = findAll(key);
            if (members.isEmpty()) throw failure(ServiceAccessException.Reason.UNAVAILABLE);
            if (members.size() > 1) throw failure(ServiceAccessException.Reason.AMBIGUOUS);
            return members.get(0).service();
        }

        @Override public <T> List<ServiceProvider<T>> findAll(ServiceKey<T> key) {
            synchronized (lock) {
                if (!binding.current(this)) throw failure(ServiceAccessException.Reason.UNAVAILABLE);
                var handles = selection.get(key);
                if (handles == null) throw failure(ServiceAccessException.Reason.UNDECLARED);
                return handles.stream().map(handle -> new ServiceProvider<>(
                        ExternalCapabilityInvocationRegistry.publication(handle.owner()), proxy(key, handle))).toList();
            }
        }

        private <T> T proxy(ServiceKey<T> key, ExternalCapabilityHandle handle) {
            T proxy = registry.bindingProxy(binding.owner, key, handle);
            return key.type().cast(Proxy.newProxyInstance(key.type().getClassLoader(), new Class<?>[]{key.type()},
                        (self, method, args) -> {
                            if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                                case "equals" -> self == args[0];
                                case "hashCode" -> System.identityHashCode(self);
                                default -> "ServiceBindingProxy";
                            };
                            return execute(() -> {
                                try { return method.invoke(proxy, args); }
                                catch (InvocationTargetException e) {
                                    ExternalCapabilityInvocationRegistry.rethrowFatal(e.getCause());
                                    if (e.getCause() instanceof ServiceAccessException f) throw f;
                                    throw failure(ServiceAccessException.Reason.INVOCATION_FAILED);
                                } catch (ReflectiveOperationException e) {
                                    throw failure(ServiceAccessException.Reason.INVOCATION_FAILED);
                                }
                            }, false);
                        }));
        }

        @Override public <T extends AutoCloseable> T acquire(Supplier<T> factory) {
            return execute(() -> resources.acquire(factory), false);
        }

        @Override public Runnable guard(Runnable callback) {
            Objects.requireNonNull(callback);
            synchronized (lock) {
                if (!binding.current(this)) throw failure(ServiceAccessException.Reason.UNAVAILABLE);
                Guard guard = new Guard(callback);
                guard.tracked = resources.guard(() -> {
                    Runnable current;
                    synchronized (lock) { current = guard.callback; }
                    if (current != null) current.run();
                });
                guards.add(guard);
                return guard;
            }
        }

        private final class Guard implements Runnable {
            private Runnable callback;
            private Runnable tracked;
            Guard(Runnable callback) { this.callback = callback; }
            @Override public void run() {
                Runnable current;
                synchronized (lock) { current = tracked; }
                // 外层任务包装器退出后才归还本轮计数，避免提前关闭遇到资源 BUSY。
                if (current != null) execute(() -> { current.run(); return null; }, true);
            }
        }
    }

    private static ServiceAccessException failure(ServiceAccessException.Reason reason) {
        return new ServiceAccessException(reason);
    }
}
