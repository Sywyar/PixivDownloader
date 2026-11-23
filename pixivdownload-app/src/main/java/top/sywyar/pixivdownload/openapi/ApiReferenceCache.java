package top.sywyar.pixivdownload.openapi;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.sywyar.pixivdownload.core.download.DownloadLifecycleRegistry.SubmissionType;
import top.sywyar.pixivdownload.plugin.registry.route.RouteAccessRegistry.RegisteredRoute;

/** One renderer per application. A reader arriving during warmup waits only for its own language. */
final class ApiReferenceCache implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(ApiReferenceCache.class);
    private static final Duration RECHECK = Duration.ofSeconds(5);

    record Inputs(long mappings, List<RegisteredRoute> routes, Map<String, SubmissionType> submissions,
                  long schedules, ApiReferenceSecurity.InstanceAccess access) {
        boolean matches(Inputs other) {
            return other != null && mappings == other.mappings && routes == other.routes
                    && schedules == other.schedules && access.equals(other.access) && submissions.equals(other.submissions);
        }
    }

    @FunctionalInterface
    interface Renderer {
        ApiReferenceDocument render(Locale locale, ApiReferenceSecurity.InstanceAccess access) throws Exception;
    }

    private final List<Locale> locales;
    private final Supplier<Inputs> inputs;
    private final Renderer renderer;
    private final Map<Locale, CompletableFuture<ApiReferenceDocument>> results = new HashMap<>();
    private Inputs current;
    private Thread worker;
    private volatile boolean closed;

    ApiReferenceCache(List<Locale> locales, Supplier<Inputs> inputs, Renderer renderer) {
        this.locales = locales;
        this.inputs = inputs;
        this.renderer = renderer;
    }

    synchronized void start() {
        if (!closed && worker == null) {
            worker = Thread.ofVirtual().name("openapi-catalog").inheritInheritableThreadLocals(false).unstarted(this::run);
            worker.setContextClassLoader(ApiReferenceCache.class.getClassLoader());
            worker.start();
        }
    }

    synchronized ApiReferenceDocument read(Locale locale) throws InterruptedException {
        boolean firstCheck = true;
        while (!closed) {
            if (worker != null && inputs.get().matches(current)) {
                var result = results.get(locale);
                if (result != null) return result.join();
            } else if (firstCheck) {
                notifyAll();
            }
            firstCheck = false;
            wait();
        }
        throw new IllegalStateException("OpenAPI catalog renderer has stopped");
    }

    private void run() {
        try {
            while (!closed) {
                try {
                    Inputs observed = inputs.get();
                    prepare(observed);
                    synchronized (this) {
                        notifyAll();
                        if (!closed && observed.matches(inputs.get())) wait(RECHECK.toMillis());
                    }
                } catch (RuntimeException failure) {
                    LOG.warn("Could not refresh OpenAPI catalog inputs", failure);
                    synchronized (this) {
                        notifyAll();
                        if (!closed) wait(RECHECK.toMillis());
                    }
                }
            }
        } catch (InterruptedException stopping) {
            Thread.currentThread().interrupt();
        } finally {
            synchronized (this) {
                closed = true;
                results.clear();
                current = null;
                notifyAll();
            }
        }
    }

    private void prepare(Inputs observed) {
        synchronized (this) {
            if (!observed.matches(current)) {
                current = observed;
                results.clear();
                LOG.info("Preparing OpenAPI catalog for {} locales", locales.size());
            }
        }
        long started = System.nanoTime();
        int prepared = 0;
        for (Locale locale : locales) {
            if (closed) return;
            synchronized (this) {
                var previous = results.get(locale);
                if (previous != null && !previous.isCompletedExceptionally()) continue;
            }
            if (!observed.matches(inputs.get())) return;
            CompletableFuture<ApiReferenceDocument> result;
            try {
                result = CompletableFuture.completedFuture(renderer.render(locale, observed.access()));
                prepared++;
            } catch (Exception error) {
                if (closed) return;
                LOG.warn("OpenAPI catalog generation failed for {}", locale, error);
                result = CompletableFuture.failedFuture(error);
            }
            synchronized (this) {
                if (!observed.matches(inputs.get())) return;
                results.put(locale, result);
                notifyAll();
            }
        }
        if (prepared > 0) LOG.info("Prepared {} OpenAPI locales in {} ms", prepared,
                Duration.ofNanos(System.nanoTime() - started).toMillis());
    }

    @Override public void close() throws InterruptedException {
        Thread active;
        synchronized (this) {
            closed = true;
            active = worker;
            notifyAll();
        }
        if (active != null) {
            active.interrupt();
            active.join();
        }
    }
}
