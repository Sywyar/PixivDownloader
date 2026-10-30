package acceptance.businessplugin;

import acceptance.business.Business;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.pf4j.*;
import org.springframework.context.annotation.*;
import org.springframework.web.bind.annotation.*;
import top.sywyar.pixivdownload.plugin.api.plugin.*;
import top.sywyar.pixivdownload.plugin.api.resource.*;
import top.sywyar.pixivdownload.plugin.api.schema.*;
import top.sywyar.pixivdownload.plugin.api.service.*;
import top.sywyar.pixivdownload.plugin.api.storage.*;
import top.sywyar.pixivdownload.plugin.api.storage.relational.*;
import top.sywyar.pixivdownload.plugin.api.task.*;
import top.sywyar.pixivdownload.plugin.api.web.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.*;

/** 各角色由独立 JAR / child context 装载，只通过独立契约协作。 */
public final class BusinessParticipant extends Plugin implements PixivPluginProvider {
    static final ServiceKey<Business.Store> STORE = new ServiceKey<>("independent-contract", Business.Store.class);
    static final ServiceKey<Business.Validator> VALIDATOR = new ServiceKey<>("independent-contract", Business.Validator.class);
    static final ServiceKey<Business.EventSink> SINK = new ServiceKey<>("independent-contract", Business.EventSink.class);
    static final ServiceKey<Business.Jobs> JOBS = new ServiceKey<>("independent-contract", Business.Jobs.class);
    private final String owner;
    public static final AtomicInteger events = new AtomicInteger(), activations = new AtomicInteger(), disposals = new AtomicInteger();
    public static volatile boolean sinkFailure;
    public static volatile CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    public BusinessParticipant(PluginWrapper wrapper) { super(wrapper); owner = wrapper.getPluginId(); }
    public PixivFeaturePlugin featurePlugin() {
        return new PixivFeaturePlugin() {
            public String id() { return owner; }
            public String displayName() { return "plugin.name"; }
            public String description() { return "plugin.summary"; }
            public PluginKind kind() { return PluginKind.FEATURE; }
            public List<String> credentialKeys() { return owner.equals("independent-collector") ? List.of(owner + ".token") : List.of(); }
            public List<ServiceKey<?>> serviceImports() {
                return switch(owner) {
                    case "independent-collector" -> List.of(STORE, VALIDATOR, SINK);
                    case "independent-display" -> List.of(STORE, JOBS);
                    default -> List.of();
                };
            }
            public List<ServiceExport<?>> serviceExports() {
                if(owner.equals("independent-storage")) return List.of(new ServiceExport<>(STORE, "store"));
                if(owner.startsWith("independent-validator")) return List.of(new ServiceExport<>(VALIDATOR, "validator"));
                if(owner.startsWith("independent-sink")) return List.of(new ServiceExport<>(SINK, "sink"));
                if(owner.equals("independent-jobs")) return List.of(new ServiceExport<>(JOBS, "jobs"));
                return List.of();
            }
            public List<WebRouteContribution> routes() {
                return owner.equals("independent-collector") ? List.of(WebRouteContribution.admin("/api/independent/collect"))
                    : owner.equals("independent-display") ? List.of(WebRouteContribution.admin("/api/independent/display")) : List.of();
            }
            public List<SchemaContribution> privateSchema() {
                return owner.equals("independent-storage") ? List.of(new SchemaContribution(List.of(new TableSpec("entries", List.of(
                    new ColumnSpec("id", "TEXT", true, null, 1), new ColumnSpec("text", "TEXT", true, null, 0)), List.of())), List.of(), List.of())) : List.of();
            }
        };
    }
    @Configuration(proxyBeanMethods=false)
    public static class Storage {
        @Bean Business.Store store(PluginDatabase database) {
            return new Business.Store() {
                public Business.Entry save(Business.Entry entry) {
                    try { return database.transaction(session -> {
                        session.insert("entries", Map.of("id", entry.id(), "text", entry.text()), DatabaseSession.Conflict.error());
                        return entry;
                    }); } catch(java.sql.SQLException error) { throw new IllegalStateException("STORAGE_FAILED", error); }
                }
                public List<Business.Entry> query() {
                    try { return database.read(session -> session.query(new Query("entries",
                        Map.of("id", column("entries", "id"), "text", column("entries", "text")), null,
                        List.of(), List.of(new Query.Order(column("entries", "id"), false, false)), 100, 0))
                        .stream().map(row -> new Business.Entry((String)row.get("id"), (String)row.get("text"))).toList());
                    } catch(java.sql.SQLException error) { throw new IllegalStateException("STORAGE_FAILED", error); }
                }
            };
        }
    }
    @Configuration(proxyBeanMethods=false)
    public static class Validation {
        @Bean Business.Validator validator() { return rule("reject-one"); }
    }
    @Configuration(proxyBeanMethods=false)
    public static class ValidationTwo {
        @Bean Business.Validator validator() { return rule("reject-two"); }
    }
    @Configuration(proxyBeanMethods=false)
    public static class ValidationThree {
        @Bean Business.Validator validator() { return rule("reject-three"); }
    }
    private static Business.Validator rule(String rule) { return entry -> new Business.Decision(!entry.text().contains(rule), rule); }
    @Configuration(proxyBeanMethods=false)
    public static class Sink {
        @Bean Business.EventSink sink() { return entry -> {
            events.incrementAndGet();
            if(sinkFailure) throw new IllegalStateException("SINK_FAILED");
        }; }
    }
    @Configuration(proxyBeanMethods=false)
    public static class Collector {
        @Bean ServiceBinding collection(PluginServices services) {
            return services.bindAll(List.of(VALIDATOR, SINK), scope -> {
                scope.findAll(VALIDATOR); scope.findAll(SINK);
                activations.incrementAndGet();
                scope.acquire(() -> () -> disposals.incrementAndGet());
            });
        }
        @Bean CollectController collect(PluginServices services) { return new CollectController(services); }
    }
    public record Command(String operation, String value) { }
    public record Result(String status, List<String> values, String id) { }
    public static class ServiceErrors {
        @ExceptionHandler(ServiceAccessException.class)
        public org.springframework.http.ResponseEntity<ApiErrorResponse> unavailable(ServiceAccessException failure) {
            return org.springframework.http.ResponseEntity.status(503).body(ApiErrorResponse.of(failure.reason().name(), "Service unavailable"));
        }
    }
    @RestController
    public static class CollectController extends ServiceErrors {
        private final PluginServices services;
        CollectController(PluginServices services) { this.services = services; }
        @PostMapping("/api/independent/collect")
        public Result collect(@RequestBody Command command) {
            var entry = new Business.Entry(UUID.randomUUID().toString(), command.value());
            for(var provider : services.findAll(VALIDATOR)) {
                var decision = provider.service().validate(entry);
                if(!decision.accepted()) return new Result("REJECTED", List.of(decision.code()), "");
            }
            var saved = services.require(STORE).save(entry);
            int failures = 0;
            for(var provider : services.findAll(SINK)) {
                try { provider.service().received(saved); }
                catch(ServiceAccessException failure) { failures++; }
            }
            return new Result("STORED", List.of(saved.text(), Integer.toString(failures)), saved.id());
        }
    }
    @Configuration(proxyBeanMethods=false)
    public static class Display {
        @Bean DisplayController display(PluginServices services) { return new DisplayController(services); }
    }
    @RestController
    public static class DisplayController extends ServiceErrors {
        private final PluginServices services;
        DisplayController(PluginServices services) { this.services = services; }
        @PostMapping("/api/independent/display")
        public Result display(@RequestBody Command command) {
            if(command.operation().equals("query")) return new Result("ROWS",
                services.require(STORE).query().stream().map(Business.Entry::text).toList(), "");
            var jobs = services.require(JOBS);
            Business.Job job = switch(command.operation()) {
                case "start" -> jobs.start(command.value());
                case "cancel" -> jobs.cancel(command.value());
                default -> jobs.query(command.value());
            };
            return new Result(job.state(), List.of(), job.id());
        }
    }
    @Configuration(proxyBeanMethods=false)
    public static class Jobs {
        @Bean JobService jobs(PluginRuntimeTaskRegistrar tasks, PluginResources resources) {
            var executor = Executors.newSingleThreadExecutor(r -> {
                var thread = new Thread(r, "independent-fixture-job"); thread.setDaemon(true); return thread;
            });
            resources.openScope().acquire(() -> (AutoCloseable)executor::shutdownNow);
            return new JobService(tasks, executor);
        }
    }
    public static final class JobService implements Business.Jobs {
        private final PluginRuntimeTaskRegistrar tasks;
        private final ExecutorService executor;
        private volatile Business.Job current = new Business.Job("", "ABSENT");
        private volatile boolean cancelled;
        private PluginRuntimeTask running;
        JobService(PluginRuntimeTaskRegistrar tasks, ExecutorService executor) { this.tasks = tasks; this.executor = executor; }
        public synchronized Business.Job start(String value) {
            if(current.state().equals("RUNNING")) throw new IllegalStateException("JOB_BUSY");
            String id = UUID.randomUUID().toString();
            cancelled = false;
            entered = new CountDownLatch(1); release = new CountDownLatch(1);
            current = new Business.Job(id, "RUNNING");
            running = tasks.registerOneShot(() -> {
                entered.countDown();
                try {
                    if(value.equals("hold")) {
                        boolean done = false;
                        while(!done) try { release.await(); done = true; } catch(InterruptedException ignored) { }
                    } else release.await();
                    current = new Business.Job(id, cancelled ? "CANCELLED" : "COMPLETED");
                } catch(InterruptedException cancelled) {
                    Thread.currentThread().interrupt();
                    current = new Business.Job(id, "CANCELLED");
                }
            });
            try { running.bindCancellation(executor.submit(running)); }
            catch(RejectedExecutionException failure) { running.discardUnsubmitted(); throw failure; }
            return current;
        }
        public Business.Job query(String id) {
            if(!current.id().equals(id)) throw new IllegalArgumentException("JOB_UNKNOWN");
            return current;
        }
        public synchronized Business.Job cancel(String id) {
            query(id); cancelled = true; running.cancel(); release.countDown(); return current;
        }
    }
}
