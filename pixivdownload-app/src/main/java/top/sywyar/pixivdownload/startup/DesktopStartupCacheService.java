package top.sywyar.pixivdownload.startup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopStartupCache;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** 进程拥有的单任务训练器；界面只观察快照，候选验证完成后供下次启动使用。 */
public final class DesktopStartupCacheService implements DesktopStartupCache, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(DesktopStartupCacheService.class);
    private final Path jar, plugins, config, trustState, root;
    private final List<String> options;
    private final BiConsumer<Snapshot, Long> history;
    private final Trainer trainer;
    private volatile Snapshot snapshot = new Snapshot(null, State.IDLE, Phase.FINISHED, Failure.NONE, 0);
    private Thread worker;
    private boolean closed;

    @FunctionalInterface
    interface Trainer {
        void train(Path jar, List<String> options, StartupCacheInputs.Snapshot inputs,
                   Path cache, Path directory, Consumer<Phase> progress) throws Exception;
    }

    private DesktopStartupCacheService(Path jar, BiConsumer<Snapshot, Long> history) {
        this(jar, options(), RuntimeFiles.pluginsDirectory(), RuntimeFiles.resolveConfigYamlPath(),
                RuntimeFiles.resolvePluginCatalogTrustStatePath(), RuntimeFiles.aotCacheDirectory(), history, StartupCacheTraining::train);
    }

    DesktopStartupCacheService(Path jar, List<String> options, Path plugins, Path config, Path trustState, Path root,
                               BiConsumer<Snapshot, Long> history, Trainer trainer) {
        this.jar = jar; this.options = List.copyOf(options); this.plugins = plugins;
        this.config = config; this.trustState = trustState; this.root = root.toAbsolutePath().normalize();
        this.history = history; this.trainer = trainer;
    }

    /** 只有解包发行的 HotSpot AOT 入口开放此能力；源码调试不生成发行缓存。 */
    public static Optional<DesktopStartupCacheService> create(BiConsumer<Snapshot, Long> history) {
        if (Boolean.getBoolean(StartupCacheTraining.TRAINING_PROPERTY)
                || Boolean.getBoolean("pixivdownload.aot.disabled")
                || top.sywyar.pixivdownload.plugin.runtime.artifact.PluginDevelopmentArtifacts.enabled()
                || !Boolean.getBoolean("pixivdownload.distribution.launcher")
                || unsupportedOptions(ManagementFactory.getRuntimeMXBean().getInputArguments())) return Optional.empty();
        try {
            var vm = ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class);
            if (vm == null) return Optional.empty();
            vm.getVMOption("AOTMode");
            Path jar = Path.of(DistributionLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (!Files.isRegularFile(jar) || !Files.isDirectory(jar.getParent().resolve("lib"))) return Optional.empty();
            var service = new DesktopStartupCacheService(jar.toRealPath(), history);
            Runtime.getRuntime().addShutdownHook(new Thread(service::close, "startup-cache-shutdown"));
            return Optional.of(service);
        } catch (Exception unavailable) {
            log.warn("Startup cache unavailable: {}", unavailable.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    static boolean unsupportedOptions(List<String> options) {
        return options.stream().anyMatch(value -> value.startsWith("-agent") || value.startsWith("-javaagent:")
                || value.startsWith("-Xrunjdwp:") || value.equals("-Xshare:off"));
    }

    static List<String> options() {
        var values = new LinkedHashSet<>(ManagementFactory.getRuntimeMXBean().getInputArguments());
        values.removeIf(value -> value.startsWith("-XX:AOT"));
        values.add(DistributionLauncher.JVM_MARKER);
        return List.copyOf(values);
    }

    @Override public Snapshot snapshot() { return snapshot; }
    @Override public UUID start() { return start(false); }

    /** GUI 与后端就绪后检查一次；相同输入的失败或取消不在每次启动时自动重试。 */
    public void startAutomatically() { start(true); }

    private synchronized UUID start(boolean automatic) {
        if (worker != null || closed) return snapshot.executionId();
        UUID id = UUID.randomUUID();
        snapshot = new Snapshot(id, State.RUNNING, Phase.PREPARING, Failure.NONE, 0);
        worker = Thread.ofPlatform().daemon().name("startup-cache-training").unstarted(() -> execute(id, automatic));
        worker.start();
        log.info("Startup cache accepted: execution={}, automatic={}", id, automatic);
        return id;
    }

    @Override public synchronized boolean cancel(UUID id) {
        if (snapshot.state() != State.RUNNING || !Objects.equals(id, snapshot.executionId())) return false;
        snapshot = new Snapshot(id, State.STOPPING, snapshot.phase(), Failure.NONE, snapshot.pluginCount());
        worker.interrupt();
        return true;
    }

    private void execute(UUID id, boolean automatic) {
        long started = System.currentTimeMillis();
        State state = State.FAILED;
        Failure failure = Failure.NONE;
        boolean record = true, published = false;
        var store = new DesktopCacheStore(root);
        Path candidate = store.candidate(id);
        try (var lease = store.acquire()) {
            if (lease == null) {
                failure = Failure.BUSY;
            } else {
                var inputs = StartupCacheInputs.capture(plugins, config, trustState);
                String key = StartupCacheTraining.identity(jar, options, inputs);
                if (automatic && (store.find(key).isPresent() || StartupCacheTraining.bundledCache(jar, options, inputs) != null)) {
                    state = State.READY;
                    record = false;
                } else if (automatic && attempted(key)) {
                    state = State.IDLE;
                    record = false;
                    log.info("Startup cache automatic retry deferred: execution={}, reason=previous-attempt", id);
                } else {
                    rememberAttempt(key);
                    phase(id, Phase.PREPARING, inputs.artifacts().size());
                    trainer.train(jar, options, inputs, candidate, root.resolve("desktop-training"),
                            step -> phase(id, step, inputs.artifacts().size()));
                    String current = StartupCacheTraining.identity(jar, options, StartupCacheInputs.capture(plugins, config, trustState));
                    if (!key.equals(current)) throw new StartupCacheInputs.InputsChanged();
                    synchronized (this) {
                        StartupCacheInputs.checkInterrupted();
                        store.publish(id, key);
                        published = true;
                        state = State.SUCCEEDED;
                        snapshot = new Snapshot(id, state, Phase.FINISHED, Failure.NONE, inputs.artifacts().size());
                        try { Files.deleteIfExists(root.resolve("desktop.attempt")); }
                        catch (IOException cleanup) { log.warn("Startup cache retry marker cleanup deferred: execution={}", id); }
                    }
                }
            }
        } catch (InterruptedException | InterruptedIOException cancelled) {
            state = State.CANCELLED;
        } catch (StartupCacheInputs.InputsChanged changed) {
            failure = Failure.INPUT_CHANGED;
            log.warn("Startup cache rejected: execution={}, reason=input-changed", id);
        } catch (Exception error) {
            failure = error instanceof AccessDeniedException ? Failure.ACCESS_DENIED
                    : error instanceof StartupCacheTraining.TrainingFailure ? Failure.TRAINING_FAILED : Failure.IO_ERROR;
            // YAML 等外部输入异常可能携带配置原文，不输出异常正文或配置。
            log.warn("Startup cache failed: execution={}, phase={}, reason={}, type={}",
                    id, snapshot.phase(), failure, error.getClass().getSimpleName());
            if (error instanceof StartupCacheTraining.TrainingFailure process)
                log.warn("Startup cache child failed: execution={}, phase={}, exitCode={}", id, process.phase, process.exitCode);
        } finally {
            Thread.interrupted();
            if (!published) try {
                if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                    top.sywyar.pixivdownload.common.PlainFilePathGuard.requirePlainParent(candidate, false);
                    Files.deleteIfExists(candidate);
                }
            }
            catch (IOException cleanup) { log.warn("Startup cache candidate cleanup deferred: execution={}", id); }
            Snapshot terminal;
            synchronized (this) {
                if (snapshot.state() == State.STOPPING && !published) state = State.CANCELLED;
                terminal = new Snapshot(id, state, Phase.FINISHED, failure, snapshot.pluginCount());
                snapshot = terminal;
                worker = null;
            }
            log.info("Startup cache finished: execution={}, state={}, failure={}, elapsedMs={}",
                    id, state, failure, System.currentTimeMillis() - started);
            if (record) history.accept(terminal, started);
        }
    }

    private synchronized void phase(UUID id, Phase phase, int plugins) {
        snapshot = new Snapshot(id, snapshot.state(), phase, Failure.NONE, plugins);
        log.info("Startup cache progress: execution={}, phase={}, plugins={}", id, phase, plugins);
    }

    private boolean attempted(String key) throws IOException {
        Path path = root.resolve("desktop.attempt");
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return false;
        top.sywyar.pixivdownload.common.PlainFilePathGuard.requirePlainRegularFile(path);
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            return new String(input.readNBytes(65), StandardCharsets.US_ASCII).equals(key);
        }
    }

    private void rememberAttempt(String key) throws IOException {
        Path temporary = root.resolve("desktop.attempt.tmp");
        Files.deleteIfExists(temporary);
        Files.writeString(temporary, key, StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW);
        Files.move(temporary, root.resolve("desktop.attempt"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    @Override public void close() {
        Thread active;
        synchronized (this) {
            closed = true;
            active = worker;
            if (active != null) cancel(snapshot.executionId());
        }
        if (active != null && active != Thread.currentThread()) {
            try { active.join(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
    }
}
