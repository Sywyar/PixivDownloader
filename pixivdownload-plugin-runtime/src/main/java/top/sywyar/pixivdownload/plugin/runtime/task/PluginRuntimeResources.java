package top.sywyar.pixivdownload.plugin.runtime.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.sywyar.pixivdownload.plugin.api.resource.PluginResourceException;
import top.sywyar.pixivdownload.plugin.api.resource.PluginResourceScope;
import top.sywyar.pixivdownload.plugin.api.resource.PluginResources;
import top.sywyar.pixivdownload.plugin.api.task.PluginRuntimeTask;
import top.sywyar.pixivdownload.plugin.api.task.PluginRuntimeTaskRejectedException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import static top.sywyar.pixivdownload.plugin.api.resource.PluginResourceException.Reason.*;
import static top.sywyar.pixivdownload.plugin.runtime.lifecycle.PluginLifecycleFailures.isFatal;
import static top.sywyar.pixivdownload.plugin.runtime.lifecycle.PluginLifecycleFailures.mergeFailure;

/** 与任务 generation 共用撤回和 drain 的资源所有者；不另建执行器或生命周期注册中心。 */
final class PluginRuntimeResources implements PluginResources {
    private static final Logger log = LoggerFactory.getLogger(PluginRuntimeResources.class);
    private final String owner;
    private final long generation;
    private final Function<Runnable, PluginRuntimeTask> tasks;
    private final Set<Scope> scopes = new LinkedHashSet<>();
    private boolean accepting = true;

    PluginRuntimeResources(String owner, long generation, Function<Runnable, PluginRuntimeTask> tasks) {
        this.owner = owner;
        this.generation = generation;
        this.tasks = tasks;
    }

    @Override
    public synchronized PluginResourceScope openScope() {
        if (!accepting) throw new PluginResourceException(CLOSED);
        Scope scope = new Scope();
        scopes.add(scope);
        return scope;
    }

    synchronized void quiesce() {
        accepting = false;
        scopes.forEach(Scope::seal);
    }

    synchronized boolean isReleased() {
        return scopes.isEmpty();
    }

    void close() {
        List<Scope> snapshot;
        synchronized (this) {
            quiesce();
            snapshot = new ArrayList<>(scopes);
        }
        Throwable failure = null;
        for (int i = snapshot.size() - 1; i >= 0; i--) {
            try {
                snapshot.get(i).close();
            } catch (Throwable closeFailure) {
                failure = mergeFailure(failure, closeFailure);
            }
        }
        rethrowCloseFailure(failure);
    }

    private final class Scope implements PluginResourceScope {
        private final List<AutoCloseable> resources = new ArrayList<>();
        private final List<Guard> guards = new ArrayList<>();
        private boolean sealed;
        private boolean closing;
        private int running;

        @Override
        public <T extends AutoCloseable> T acquire(Supplier<T> factory) {
            Objects.requireNonNull(factory, "factory");
            AtomicReference<T> result = new AtomicReference<>();
            invoke(() -> {
                T resource = Objects.requireNonNull(factory.get(), "resource");
                synchronized (PluginRuntimeResources.this) {
                    resources.add(resource);
                }
                result.set(resource);
            }, false);
            T resource = result.get();
            if (resource == null) throw new PluginResourceException(CLOSED);
            return resource;
        }

        @Override
        public Runnable guard(Runnable callback) {
            Objects.requireNonNull(callback, "callback");
            synchronized (PluginRuntimeResources.this) {
                requireOpen();
                Guard guard = new Guard(callback);
                guards.add(guard);
                return guard;
            }
        }

        private void requireOpen() {
            if (!accepting || sealed) throw new PluginResourceException(CLOSED);
        }

        private void seal() {
            sealed = true;
            guards.forEach(guard -> guard.callback = null);
            guards.clear();
        }

        private void invoke(Runnable action, boolean optional) {
            PluginRuntimeTask task;
            try {
                task = tasks.apply(() -> {
                    synchronized (PluginRuntimeResources.this) {
                        if (!accepting || sealed) {
                            if (optional) return;
                            throw new PluginResourceException(CLOSED);
                        }
                        running++;
                    }
                    try {
                        action.run();
                    } finally {
                        synchronized (PluginRuntimeResources.this) {
                            running--;
                        }
                    }
                });
            } catch (PluginRuntimeTaskRejectedException rejected) {
                if (optional) return;
                throw new PluginResourceException(CLOSED);
            }
            task.run();
        }

        @Override
        public void close() {
            List<AutoCloseable> snapshot;
            synchronized (PluginRuntimeResources.this) {
                seal();
                if (running != 0 || closing) throw new PluginResourceException(BUSY);
                closing = true;
                snapshot = new ArrayList<>(resources);
            }
            Throwable failure = null;
            try {
                for (int i = snapshot.size() - 1; i >= 0; i--) {
                    AutoCloseable resource = snapshot.get(i);
                    try {
                        resource.close();
                        synchronized (PluginRuntimeResources.this) {
                            resources.remove(i);
                        }
                    } catch (Throwable closeFailure) {
                        log.warn("Plugin resource close failed (owner={}, generation={}, failureType={})",
                                owner, generation, closeFailure.getClass().getName());
                        failure = mergeFailure(failure, closeFailure);
                    }
                }
            } finally {
                synchronized (PluginRuntimeResources.this) {
                    closing = false;
                    if (resources.isEmpty()) scopes.remove(this);
                }
            }
            rethrowCloseFailure(failure);
        }

        private final class Guard implements Runnable {
            private Runnable callback;

            private Guard(Runnable callback) {
                this.callback = callback;
            }

            @Override
            public void run() {
                invoke(() -> {
                    Runnable current;
                    synchronized (PluginRuntimeResources.this) {
                        current = callback;
                    }
                    if (current != null) current.run();
                }, true);
            }
        }
    }

    private static void rethrowCloseFailure(Throwable failure) {
        if (isFatal(failure)) throw (Error) failure;
        if (failure != null) throw new PluginResourceException(
                failure instanceof PluginResourceException resourceFailure ? resourceFailure.reason() : CLOSE_FAILED);
    }
}
