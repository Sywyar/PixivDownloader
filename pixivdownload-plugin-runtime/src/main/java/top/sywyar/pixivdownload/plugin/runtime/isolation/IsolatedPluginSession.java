package top.sywyar.pixivdownload.plugin.runtime.isolation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;
import top.sywyar.pixivdownload.plugin.api.web.NavigationContribution;
import top.sywyar.pixivdownload.plugin.api.web.StaticResourceContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;
import top.sywyar.pixivdownload.plugin.runtime.artifact.PluginArtifactSnapshot;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginInstallation;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginInventory;
import top.sywyar.pixivdownload.plugin.runtime.lifecycle.PluginRuntimeOperationException;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginStatus;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

/** 宿主拥有的单 generation 隔离 worker、资源 loader 与纯值插件代理。 */
public final class IsolatedPluginSession {

    private static final Logger log = LoggerFactory.getLogger(IsolatedPluginSession.class);
    public static final String INITIALIZE_TIMEOUT_PROPERTY =
            "pixivdownload.plugin-worker.initialize-timeout-ms";
    public static final String COMMAND_TIMEOUT_PROPERTY =
            "pixivdownload.plugin-worker.command-timeout-ms";
    public static final String SHUTDOWN_TIMEOUT_PROPERTY =
            "pixivdownload.plugin-worker.shutdown-timeout-ms";
    public static final String RESTART_ATTEMPTS_PROPERTY =
            "pixivdownload.plugin-worker.restart-attempts";
    public static final String RESTART_INITIAL_DELAY_PROPERTY =
            "pixivdownload.plugin-worker.restart-initial-delay-ms";
    public static final String RESTART_MAX_DELAY_PROPERTY =
            "pixivdownload.plugin-worker.restart-max-delay-ms";
    public static final String STDERR_MAX_BYTES_PROPERTY =
            "pixivdownload.plugin-worker.stderr-max-bytes";
    private static final int STDERR_TAIL_BYTES = 16 * 1024;
    private static final String WORKER_MAIN = IsolatedPluginWorkerMain.class.getName();
    private static final String WORKER_RESOURCE =
            "top/sywyar/pixivdownload/plugin/runtime/isolated-plugin-worker.jar";
    private static final long MAX_WORKER_JAR_BYTES = 16L * 1024L * 1024L;

    private final PluginDescriptor descriptor;
    private final Path artifact;
    private final String verifiedSha256;
    private final PluginArtifactSnapshot artifactSnapshot;
    private final Consumer<WorkerExit> exitListener;
    private final Settings settings;

    private Process process;
    private DataInputStream input;
    private DataOutputStream output;
    private ThreadPoolExecutor ioExecutor;
    private IsolatedPluginProtocol.Snapshot admittedSnapshot;
    private ResourceOnlyClassLoader resourceClassLoader;
    private IsolatedFeaturePlugin featureProxy;
    private boolean closed;

    public IsolatedPluginSession(PluginDescriptor descriptor,
                                 Path artifact,
                                 String verifiedSha256,
                                 PluginArtifactSnapshot artifactSnapshot) {
        this(descriptor, artifact, verifiedSha256, artifactSnapshot, ignored -> {
        });
    }

    public IsolatedPluginSession(PluginDescriptor descriptor,
                                 Path artifact,
                                 String verifiedSha256,
                                 PluginArtifactSnapshot artifactSnapshot,
                                 Consumer<WorkerExit> exitListener) {
        this.descriptor = descriptor;
        this.artifact = artifact.toAbsolutePath().normalize();
        this.verifiedSha256 = verifiedSha256;
        this.artifactSnapshot = artifactSnapshot;
        this.exitListener = exitListener == null ? ignored -> {
        } : exitListener;
        this.settings = Settings.fromSystemProperties();
    }

    public synchronized PluginInventory initialize() {
        requireOpen();
        ensureWorker();
        if (resourceClassLoader == null) {
            try {
                resourceClassLoader = new ResourceOnlyClassLoader(
                        artifact.toUri().toURL(), PixivFeaturePlugin.class.getClassLoader());
            } catch (IOException failure) {
                terminateWorker(true);
                throw new PluginRuntimeOperationException(
                        "failed to open isolated plugin resource loader: " + descriptor.id(), failure);
            }
        }
        if (featureProxy == null) {
            featureProxy = new IsolatedFeaturePlugin(this, descriptor, admittedSnapshot);
        }
        PluginInstallation installation = new PluginInstallation(
                descriptor, PluginStatus.STARTED, resourceClassLoader, featureProxy);
        return new PluginInventory(List.of(installation), List.of(), List.of());
    }

    public synchronized void startPackage() {
        requireOpen();
        ensureWorker();
        command(IsolatedPluginProtocol.START_PACKAGE, settings.commandTimeout());
    }

    synchronized void startFeature() {
        requireOpen();
        requireLiveWorker();
        command(IsolatedPluginProtocol.START_FEATURE, settings.commandTimeout());
    }

    synchronized void stopFeature() {
        if (!closed && isWorkerAlive()) {
            command(IsolatedPluginProtocol.STOP_FEATURE, settings.commandTimeout());
        }
    }

    public synchronized boolean stopAndTerminate() {
        if (!isWorkerAlive()) {
            terminateWorker(true);
            return true;
        }
        try {
            command(IsolatedPluginProtocol.STOP_FEATURE, settings.commandTimeout());
            command(IsolatedPluginProtocol.STOP_PACKAGE, settings.commandTimeout());
            command(IsolatedPluginProtocol.SHUTDOWN, settings.commandTimeout());
        } catch (RuntimeException failure) {
            log.warn("Isolated plugin worker {} did not stop cooperatively: {}",
                    descriptor.id(), failure.getMessage());
        }
        return terminateWorker(true);
    }

    public synchronized boolean close() {
        if (closed) {
            return !isWorkerAlive();
        }
        boolean terminated = stopAndTerminate();
        if (!terminated) {
            return false;
        }
        if (resourceClassLoader != null) {
            try {
                resourceClassLoader.close();
                resourceClassLoader = null;
            } catch (IOException failure) {
                terminated = false;
                log.warn("Failed to close isolated plugin resource loader {}: {}",
                        descriptor.id(), failure.toString());
            }
        }
        if (terminated) {
            artifactSnapshot.close();
        }
        closed = terminated;
        return terminated;
    }

    public synchronized boolean isWorkerAlive() {
        return process != null && process.isAlive();
    }

    public synchronized long workerPid() {
        return isWorkerAlive() ? process.pid() : 0L;
    }

    public int restartAttempts() {
        return settings.restartAttempts();
    }

    public Duration restartDelay(int attempt) {
        return settings.restartDelay(attempt);
    }

    private void ensureWorker() {
        if (isWorkerAlive()) {
            return;
        }
        artifactSnapshot.verifyLoadPath(artifact);
        Path workerDirectory;
        try {
            workerDirectory = artifactSnapshot.createWorkerDirectory();
            ProcessBuilder builder = new ProcessBuilder(workerCommand(workerDirectory));
            builder.directory(workerDirectory.toFile());
            configureEnvironment(builder, workerDirectory);
            Path workerLog = prepareWorkerLog(workerDirectory.getParent());
            OutputStream workerLogOutput = Files.newOutputStream(
                    workerLog, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                process = builder.start();
            } catch (IOException | RuntimeException failure) {
                closeQuietly(workerLogOutput);
                throw failure;
            }
            Process startedProcess = process;
            BoundedStderrCapture stderrCapture = new BoundedStderrCapture(STDERR_TAIL_BYTES);
            Thread stderrPump = startStderrPump(
                    startedProcess, workerLogOutput, stderrCapture, settings.stderrMaximumBytes());
            input = new DataInputStream(process.getInputStream());
            output = new DataOutputStream(process.getOutputStream());
            ioExecutor = new ThreadPoolExecutor(
                    1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1),
                    runnable -> {
                        Thread thread = new Thread(runnable,
                                "plugin-worker-ipc-" + descriptor.id());
                        thread.setDaemon(true);
                        return thread;
                    }, new ThreadPoolExecutor.AbortPolicy());
            startedProcess.onExit().thenAccept(exited ->
                    handleProcessExit(exited, workerLog, stderrCapture, stderrPump));
            byte[] response = exchange(IsolatedPluginProtocol.message(
                    IsolatedPluginProtocol.INITIALIZE, payload -> {
                        IsolatedPluginProtocol.writeString(payload, descriptor.id());
                        IsolatedPluginProtocol.writeString(payload, descriptor.version());
                        IsolatedPluginProtocol.writeString(payload, verifiedSha256);
                        IsolatedPluginProtocol.writeString(payload, artifact.toString());
                    }), settings.initializeTimeout());
            IsolatedPluginProtocol.Snapshot observed;
            try (DataInputStream payload = IsolatedPluginProtocol.requireSuccess(response)) {
                observed = IsolatedPluginProtocol.Snapshot.readFrom(payload);
            }
            if (admittedSnapshot != null && !admittedSnapshot.equals(observed)) {
                throw new IOException("isolated plugin contribution snapshot changed across worker restart");
            }
            admittedSnapshot = observed;
        } catch (IOException | RuntimeException failure) {
            terminateWorker(true);
            if (failure instanceof PluginRuntimeOperationException operationFailure) {
                throw operationFailure;
            }
            throw new PluginRuntimeOperationException(
                    "failed to initialize isolated plugin worker: " + descriptor.id(), failure);
        }
    }

    private void command(byte command, Duration timeout) {
        try {
            byte[] response = exchange(IsolatedPluginProtocol.message(command, null), timeout);
            try (DataInputStream payload = IsolatedPluginProtocol.requireSuccess(response)) {
                if (payload.available() != 0) {
                    throw new IOException("isolated plugin worker acknowledgement contains trailing bytes");
                }
            }
        } catch (IOException failure) {
            terminateWorker(false);
            throw new PluginRuntimeOperationException(
                    "isolated plugin worker command failed: " + descriptor.id(), failure);
        }
    }

    private byte[] exchange(byte[] request, Duration timeout) throws IOException {
        requireLiveWorker();
        Future<byte[]> exchange = ioExecutor.submit(() -> {
            IsolatedPluginProtocol.writeFrame(output, request);
            return IsolatedPluginProtocol.readFrame(input);
        });
        try {
            return exchange.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException failure) {
            exchange.cancel(true);
            throw new IOException("isolated plugin worker exceeded the absolute command timeout", failure);
        } catch (InterruptedException failure) {
            exchange.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while waiting for isolated plugin worker", failure);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof IOException ioFailure) {
                throw ioFailure;
            }
            throw new IOException("isolated plugin worker IPC failed", cause);
        }
    }

    private boolean terminateWorker(boolean expected) {
        Process previous = process;
        if (expected) {
            process = null;
        }
        closeQuietly(output);
        closeQuietly(input);
        output = null;
        input = null;
        boolean terminated = terminateProcessTree(previous, settings.shutdownTimeout());
        ThreadPoolExecutor previousExecutor = ioExecutor;
        ioExecutor = null;
        if (previousExecutor != null) {
            previousExecutor.shutdownNow();
        }
        return terminated;
    }

    static boolean terminateProcessTree(Process process, Duration timeout) {
        if (process == null) {
            return true;
        }
        LinkedHashSet<ProcessHandle> handles = new LinkedHashSet<>();
        process.descendants().forEach(handles::add);
        handles.add(process.toHandle());
        destroy(handles, false);
        waitForExit(handles, timeout);
        process.descendants().forEach(handles::add);
        destroy(handles, true);
        waitForExit(handles, timeout);
        return handles.stream().noneMatch(ProcessHandle::isAlive);
    }

    private static void destroy(Set<ProcessHandle> handles, boolean forcibly) {
        for (ProcessHandle handle : handles) {
            if (!handle.isAlive()) {
                continue;
            }
            if (forcibly) {
                handle.destroyForcibly();
            } else {
                handle.destroy();
            }
        }
    }

    private static void waitForExit(Set<ProcessHandle> handles, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (handles.stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // 进程终止负责最终释放 pipe。
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new PluginRuntimeOperationException(
                    "isolated plugin session is already closed: " + descriptor.id());
        }
    }

    private void requireLiveWorker() {
        if (!isWorkerAlive() || input == null || output == null || ioExecutor == null) {
            throw new PluginRuntimeOperationException(
                    "isolated plugin worker is not running: " + descriptor.id());
        }
    }

    private void handleProcessExit(Process exited,
                                   Path workerLog,
                                   BoundedStderrCapture stderrCapture,
                                   Thread stderrPump) {
        joinQuietly(stderrPump, settings.shutdownTimeout());
        WorkerExit event;
        synchronized (this) {
            if (process != exited) {
                return;
            }
            process = null;
            closeQuietly(output);
            closeQuietly(input);
            output = null;
            input = null;
            ThreadPoolExecutor previousExecutor = ioExecutor;
            ioExecutor = null;
            if (previousExecutor != null) {
                previousExecutor.shutdownNow();
            }
            String tail = stderrCapture.tail();
            String reason = "isolated plugin worker exited with code " + exited.exitValue();
            if (!tail.isBlank()) {
                reason += System.lineSeparator() + tail;
            }
            event = new WorkerExit(exited.exitValue(), reason, workerLog,
                    stderrCapture.truncated());
        }
        try {
            exitListener.accept(event);
        } catch (RuntimeException failure) {
            log.warn("Isolated plugin worker exit listener failed for {}", descriptor.id(), failure);
        }
    }

    private static void joinQuietly(Thread thread, Duration timeout) {
        if (thread == null) {
            return;
        }
        try {
            thread.join(timeout.toMillis());
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        }
    }

    private static Path prepareWorkerLog(Path workspace) throws IOException {
        Path current = workspace.resolve("worker-stderr.log");
        Path previous = workspace.resolve("worker-stderr.previous.log");
        requireSafeLogEntry(current);
        requireSafeLogEntry(previous);
        Files.deleteIfExists(previous);
        if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.move(current, previous, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(current, previous);
            }
        }
        return current;
    }

    private static void requireSafeLogEntry(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new IOException("isolated plugin worker log is not a plain regular file: " + path);
        }
    }

    private Thread startStderrPump(Process startedProcess,
                                   OutputStream workerLog,
                                   BoundedStderrCapture capture,
                                   long maximumBytes) {
        Thread thread = new Thread(() -> {
            OutputStream sink = workerLog;
            long written = 0L;
            try (InputStream stderr = startedProcess.getErrorStream()) {
                byte[] buffer = new byte[8192];
                for (int read; (read = stderr.read(buffer)) >= 0; ) {
                    capture.append(buffer, read);
                    if (sink == null || written >= maximumBytes) {
                        capture.markTruncated();
                        continue;
                    }
                    int allowed = (int) Math.min(read, maximumBytes - written);
                    try {
                        sink.write(buffer, 0, allowed);
                        sink.flush();
                        written += allowed;
                        if (allowed < read) {
                            capture.markTruncated();
                        }
                    } catch (IOException failure) {
                        log.warn("Failed to write isolated plugin worker stderr log {}: {}",
                                descriptor.id(), failure.toString());
                        closeQuietly(sink);
                        sink = null;
                    }
                }
            } catch (IOException failure) {
                if (startedProcess.isAlive()) {
                    log.warn("Failed to read isolated plugin worker stderr {}: {}",
                            descriptor.id(), failure.toString());
                }
            } finally {
                closeQuietly(sink);
            }
        }, "plugin-worker-stderr-" + descriptor.id());
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static List<String> workerCommand(Path workerDirectory) throws IOException {
        Path java = Path.of(System.getProperty("java.home"), "bin",
                isWindows() ? "java.exe" : "java").toAbsolutePath().normalize();
        if (!Files.isRegularFile(java)) {
            throw new IOException("worker Java executable is unavailable: " + java);
        }
        Path executableWorker = materializeEmbeddedWorker(workerDirectory);
        List<String> command = new ArrayList<>();
        command.add(java.toString());
        command.add("-Xmx128m");
        command.add("-XX:MaxMetaspaceSize=128m");
        command.add("-XX:MaxDirectMemorySize=64m");
        command.add("-XX:ActiveProcessorCount=2");
        command.add("-XX:+ExitOnOutOfMemoryError");
        command.add("-Dfile.encoding=UTF-8");
        command.add("-Djava.awt.headless=true");
        command.add("-Duser.home=" + workerDirectory);
        command.add("-Djava.io.tmpdir=" + workerDirectory);
        if (executableWorker != null) {
            command.add("-jar");
            command.add(executableWorker.toString());
        } else {
            command.add("-cp");
            command.add(workerClassPath());
            command.add(WORKER_MAIN);
        }
        return List.copyOf(command);
    }

    private static Path materializeEmbeddedWorker(Path workerDirectory) throws IOException {
        ClassLoader loader = IsolatedPluginSession.class.getClassLoader();
        InputStream resource = loader.getResourceAsStream(WORKER_RESOURCE);
        if (resource == null) {
            return null;
        }
        Path target = workerDirectory.resolve("isolated-plugin-worker.jar");
        try (resource;
             OutputStream output = Files.newOutputStream(
                     target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[8192];
            long total = 0L;
            for (int read; (read = resource.read(buffer)) >= 0; ) {
                total += read;
                if (total > MAX_WORKER_JAR_BYTES) {
                    throw new IOException("embedded isolated plugin worker exceeds the byte limit");
                }
                output.write(buffer, 0, read);
            }
        }
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(target)) {
            throw new IOException("embedded isolated plugin worker is not a plain regular file");
        }
        return target;
    }

    private static String workerClassPath() throws IOException {
        Set<String> entries = new LinkedHashSet<>();
        addCodeSource(entries, IsolatedPluginWorkerMain.class);
        addCodeSource(entries, PixivFeaturePlugin.class);
        addCodeSource(entries, org.pf4j.Plugin.class);
        addCodeSource(entries, org.slf4j.LoggerFactory.class);
        try {
            addCodeSource(entries, Class.forName(
                    "com.github.zafarkhaja.semver.Version", false,
                    org.pf4j.Plugin.class.getClassLoader()));
        } catch (ClassNotFoundException failure) {
            throw new IOException("PF4J semantic version dependency is unavailable", failure);
        }
        if (entries.isEmpty()) {
            throw new IOException("worker classpath is unavailable");
        }
        return String.join(java.io.File.pathSeparator, entries);
    }

    private static void addCodeSource(Set<String> entries, Class<?> anchor) throws IOException {
        if (anchor.getProtectionDomain() == null
                || anchor.getProtectionDomain().getCodeSource() == null
                || anchor.getProtectionDomain().getCodeSource().getLocation() == null) {
            throw new IOException("worker classpath code source is unavailable: " + anchor.getName());
        }
        URL location = anchor.getProtectionDomain().getCodeSource().getLocation();
        if (!"file".equalsIgnoreCase(location.getProtocol())) {
            throw new IOException("worker classpath code source is not a file: " + anchor.getName());
        }
        try {
            Path path = Path.of(location.toURI()).toAbsolutePath().normalize();
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("worker classpath code source does not exist: " + anchor.getName());
            }
            entries.add(path.toString());
        } catch (java.net.URISyntaxException failure) {
            throw new IOException("worker classpath code source URL is invalid: " + anchor.getName(), failure);
        }
    }

    static void configureEnvironment(ProcessBuilder builder, Path workerDirectory) {
        Map<String, String> environment = builder.environment();
        environment.put("TEMP", workerDirectory.toString());
        environment.put("TMP", workerDirectory.toString());
        environment.put("TMPDIR", workerDirectory.toString());
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    public record WorkerExit(int exitCode, String reason, Path logPath, boolean logTruncated) {
    }

    record Settings(Duration initializeTimeout,
                    Duration commandTimeout,
                    Duration shutdownTimeout,
                    int restartAttempts,
                    Duration restartInitialDelay,
                    Duration restartMaximumDelay,
                    long stderrMaximumBytes) {

        static Settings fromSystemProperties() {
            Duration initialDelay = durationProperty(RESTART_INITIAL_DELAY_PROPERTY, 500L, 1L, 300_000L);
            Duration maximumDelay = durationProperty(RESTART_MAX_DELAY_PROPERTY, 10_000L, 1L, 300_000L);
            if (maximumDelay.compareTo(initialDelay) < 0) {
                throw new IllegalArgumentException(RESTART_MAX_DELAY_PROPERTY
                        + " must be greater than or equal to " + RESTART_INITIAL_DELAY_PROPERTY);
            }
            return new Settings(
                    durationProperty(INITIALIZE_TIMEOUT_PROPERTY, 10_000L, 1L, 300_000L),
                    durationProperty(COMMAND_TIMEOUT_PROPERTY, 5_000L, 1L, 300_000L),
                    durationProperty(SHUTDOWN_TIMEOUT_PROPERTY, 2_000L, 1L, 300_000L),
                    integerProperty(RESTART_ATTEMPTS_PROPERTY, 3, 0, 20),
                    initialDelay,
                    maximumDelay,
                    longProperty(STDERR_MAX_BYTES_PROPERTY, 1024L * 1024L, 1024L, 16L * 1024L * 1024L));
        }

        Duration restartDelay(int attempt) {
            if (attempt <= 0) {
                throw new IllegalArgumentException("restart attempt must be positive");
            }
            long multiplier = 1L << Math.min(attempt - 1, 30);
            long delay;
            try {
                delay = Math.multiplyExact(restartInitialDelay.toMillis(), multiplier);
            } catch (ArithmeticException ignored) {
                delay = restartMaximumDelay.toMillis();
            }
            return Duration.ofMillis(Math.min(delay, restartMaximumDelay.toMillis()));
        }

        private static Duration durationProperty(
                String name, long fallback, long minimum, long maximum) {
            return Duration.ofMillis(longProperty(name, fallback, minimum, maximum));
        }

        private static int integerProperty(String name, int fallback, int minimum, int maximum) {
            return Math.toIntExact(longProperty(name, fallback, minimum, maximum));
        }

        private static long longProperty(
                String name, long fallback, long minimum, long maximum) {
            String configured = System.getProperty(name);
            if (configured == null || configured.isBlank()) {
                return fallback;
            }
            try {
                long parsed = Long.parseLong(configured.trim());
                if (parsed < minimum || parsed > maximum) {
                    throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
                }
                return parsed;
            } catch (NumberFormatException failure) {
                throw new IllegalArgumentException(name + " must be an integer", failure);
            }
        }
    }

    private static final class BoundedStderrCapture {

        private final byte[] tail;
        private int start;
        private int size;
        private boolean truncated;

        private BoundedStderrCapture(int maximumBytes) {
            this.tail = new byte[maximumBytes];
        }

        private synchronized void append(byte[] bytes, int length) {
            for (int index = 0; index < length; index++) {
                if (size == tail.length) {
                    start = (start + 1) % tail.length;
                    truncated = true;
                } else {
                    size++;
                }
                tail[(start + size - 1) % tail.length] = bytes[index];
            }
        }

        private synchronized void markTruncated() {
            truncated = true;
        }

        private synchronized boolean truncated() {
            return truncated;
        }

        private synchronized String tail() {
            byte[] bytes = new byte[size];
            for (int index = 0; index < size; index++) {
                bytes[index] = tail[(start + index) % tail.length];
            }
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8).trim();
        }
    }

    private static final class ResourceOnlyClassLoader extends URLClassLoader {

        private ResourceOnlyClassLoader(URL artifact, ClassLoader parent) {
            super(new URL[]{artifact}, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            return getParent().loadClass(name);
        }

        @Override
        public URL getResource(String name) {
            return findResource(name);
        }

        @Override
        public java.util.Enumeration<URL> getResources(String name) throws IOException {
            return findResources(name);
        }
    }

    private static final class IsolatedFeaturePlugin implements PixivFeaturePlugin {

        private final IsolatedPluginSession session;
        private final PluginDescriptor descriptor;
        private final IsolatedPluginProtocol.Snapshot snapshot;

        private IsolatedFeaturePlugin(IsolatedPluginSession session,
                                      PluginDescriptor descriptor,
                                      IsolatedPluginProtocol.Snapshot snapshot) {
            this.session = session;
            this.descriptor = descriptor;
            this.snapshot = snapshot;
        }

        @Override
        public String id() {
            return descriptor.id();
        }

        @Override
        public String displayName() {
            return descriptor.displayName();
        }

        @Override
        public String description() {
            return descriptor.description();
        }

        @Override
        public String displayNamespace() {
            return descriptor.displayNamespace();
        }

        @Override
        public String iconKey() {
            return descriptor.iconKey() == null ? DEFAULT_ICON_KEY : descriptor.iconKey();
        }

        @Override
        public String colorToken() {
            return descriptor.colorToken() == null ? DEFAULT_COLOR_TOKEN : descriptor.colorToken();
        }

        @Override
        public PluginKind kind() {
            return descriptor.kind();
        }

        @Override
        public void start() {
            session.startFeature();
        }

        @Override
        public void stop() {
            session.stopFeature();
        }

        @Override
        public List<WebRouteContribution> routes() {
            return snapshot.routes();
        }

        @Override
        public List<StaticResourceContribution> staticResources() {
            return snapshot.staticResources();
        }

        @Override
        public List<I18nContribution> i18n() {
            return snapshot.i18n();
        }

        @Override
        public List<NavigationContribution> navigation() {
            return snapshot.navigation();
        }
    }
}
