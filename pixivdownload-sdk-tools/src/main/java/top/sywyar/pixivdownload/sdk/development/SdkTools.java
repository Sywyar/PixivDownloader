package top.sywyar.pixivdownload.sdk.development;

import com.fasterxml.jackson.databind.JsonNode;
import top.sywyar.pixivdownload.common.Utf8ConsoleStreams;
import top.sywyar.pixivdownload.i18n.MessageBundles;

import java.io.IOException;
import java.net.InetAddress;
import java.net.BindException;
import java.net.ServerSocket;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Maven、Gradle、sbt 与 IDE 共用的 SDK 本地入口。构建工具只传本轮产物路径。 */
public final class SdkTools {
    private static final long MAX_INSTALL_LOG_BYTES = 8L * 1024 * 1024;
    private static final Duration INSTALL_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(45);
    private static final String INSTALL_RESULT = "PIXIV_SDK_INSTALL_RESULT=";

    private SdkTools() {
    }

    public static void main(String[] args) {
        Utf8ConsoleStreams.install();
        // IDE 可能直接终止 Maven / Gradle；工具仍负责关闭自己启动的完整宿主。
        ProcessHandle.current().parent().ifPresent(parent -> parent.onExit().thenRun(() -> System.exit(1)));
        try {
            System.exit(execute(args));
        } catch (Exception failure) {
            System.err.println(MessageBundles.get("sdk.error", failure.getMessage()));
            System.exit(1);
        }
    }

    static int execute(String[] args) throws Exception {
        if (args.length < 2 || !List.of("prepare", "run", "debug", "stop").contains(args[0])) {
            System.out.println(MessageBundles.get("sdk.usage"));
            return 2;
        }
        Path project = Path.of(args[1]).toRealPath();
        Path dev = project.resolve(".dev");
        SdkRuntimeArchive.requireDirectory(dev);
        if (args[0].equals("stop")) {
            if (args.length != 2) throw new IllegalArgumentException("SDK_ARGUMENTS");
            stop(project);
            return 0;
        }
        SdkRuntimeLock lock = SdkRuntimeLock.read(project);
        lock.requireCurrentPlatform();
        Path cache = Path.of(System.getProperty("pixivdownload.sdk.cache-dir",
                Path.of(System.getProperty("user.home"), ".cache", "pixivdownloader-sdk").toString()));
        if (args[0].equals("prepare")) {
            if (args.length != 2) throw new IllegalArgumentException("SDK_ARGUMENTS");
            SdkRuntimeArchive.cached(lock, cache);
            System.out.println(MessageBundles.get("sdk.prepared", lock.sdkVersion()));
            return 0;
        }
        if (args.length < 3) throw new IllegalArgumentException("SDK_ARTIFACT_ARGUMENT");
        boolean debug = args[0].equals("debug");
        boolean noGui = false;
        boolean debugConnect = false;
        int debugPort = 5005;
        for (String option : Arrays.copyOfRange(args, 3, args.length)) {
            if (option.equals("--no-gui")) noGui = true;
            else if (debug && option.equals("--debug-connect")) debugConnect = true;
            else if (debug && option.matches("--debug-port=[0-9]{1,5}")) {
                debugPort = Integer.parseInt(option.substring("--debug-port=".length()));
                if (debugPort < 1 || debugPort > 65535) throw new IllegalArgumentException("SDK_DEBUG_PORT");
            } else throw new IllegalArgumentException("SDK_ARGUMENTS");
        }
        Path artifact = Path.of(args[2]).toRealPath();
        if (!artifact.startsWith(project) || artifact.startsWith(dev)
                || !Files.isRegularFile(artifact, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("SDK_ARTIFACT_LOCATION");
        }
        // 只在取得项目锁后准备本次运行；另一个项目不使用此锁或此项目的可写目录。
        try (FileChannel channel = FileChannel.open(dev.resolve("project.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
             var lease = channel.tryLock()) {
            if (lease == null) throw new IOException("SDK_PROJECT_BUSY");
            stopPrevious(project);
            String digest = SdkRuntimeArchive.sha256(artifact, SdkRuntimeArchive.MAX_ARCHIVE_BYTES);
            // Windows CreateProcess 的工作目录仍有长度限制，为正式 worker 的私有目录保留空间。
            Path run = dev.resolve("runs").resolve(UUID.randomUUID().toString().replace("-", "").substring(0, 16));
            SdkRuntimeArchive.prepare(lock, SdkRuntimeArchive.cached(lock, cache), run);
            Files.writeString(run.resolve(".sdk-run"), lock.runtime().archive().sha256(), StandardCharsets.UTF_8);
            Path state = dev.resolve("state").resolve(lock.runtime().archive().sha256());
            for (String kind : List.of("config", "state", "data", "instance")) {
                SdkRuntimeArchive.requireDirectory(state.resolve(kind));
            }
            cleanOldRuns(project, run, state, lock);
            List<String> install = javaCommand(run, state);
            install.addAll(List.of("-Dloader.main=top.sywyar.pixivdownload.plugin.sdk.SdkPluginInstaller",
                    "-cp", run.resolve(lock.runtime().host().file()).toString(),
                    "org.springframework.boot.loader.launch.PropertiesLauncher",
                    project.toString(), run.toString(), artifact.toString(), digest));
            JsonNode receipt = install(install, run);
            if (receipt.path("schemaVersion").asInt() != 1
                    || !receipt.path("sdkVersion").asText().equals(lock.sdkVersion())
                    || !receipt.path("artifactSha256").asText().equals(digest)
                    || !receipt.path("pluginId").asText().matches("[a-z0-9][a-z0-9._-]{0,127}")
                    || !List.of("host-process-full-trust", "declarative-process")
                            .contains(receipt.path("executionMode").asText())) {
                throw new IOException("SDK_INSTALL_RECEIPT");
            }
            String mode = receipt.path("executionMode").asText();
            List<String> command = javaCommand(run, state);
            if (debug) {
                if (debugConnect) waitForDebugger(debugPort);
                else {
                    try (ServerSocket available = new ServerSocket(debugPort, 1, InetAddress.getByName("127.0.0.1"))) {
                        if (available.getLocalPort() != debugPort) throw new IOException("SDK_DEBUG_PORT");
                    }
                }
                if (mode.equals("host-process-full-trust")) {
                    command.add("-agentlib:jdwp=transport=dt_socket,server=" + (debugConnect ? "n" : "y")
                            + ",suspend=y,quiet=y,address=127.0.0.1:"
                            + debugPort + ",timeout=30000");
                } else {
                    command.add("-Dpixivdownload.sdk.debug.plugin-id=" + receipt.path("pluginId").asText());
                    command.add("-Dpixivdownload.sdk.debug.artifact-sha256=" + digest);
                    command.add("-Dpixivdownload.sdk.debug.port=" + debugPort);
                    command.add("-Dpixivdownload.sdk.debug.connect=" + debugConnect);
                }
            }
            var owner = ProcessHandle.current();
            int serverPort = freePort();
            command.addAll(List.of("-Dloader.main=top.sywyar.pixivdownload.plugin.sdk.SdkHostLauncher",
                    "-cp", run.resolve(lock.runtime().host().file()).toString(),
                    "org.springframework.boot.loader.launch.PropertiesLauncher",
                    Long.toString(owner.pid()), owner.info().startInstant().orElseThrow().toString(), run.toString(),
                    "--server.address=127.0.0.1", "--server.port=" + serverPort,
                    "--update.enabled=false", "--plugin-catalog.enabled=false",
                    "--download.root-folder=" + dev.resolve("downloads")));
            if (noGui) command.add("--no-gui");
            var process = new AtomicReference<Process>();
            Thread shutdown = new Thread(() -> stopOwnedProcess(run, process.get()), "sdk-stop-host");
            Runtime.getRuntime().addShutdownHook(shutdown);
            try {
                Process host = processBuilder(command, run).inheritIO().start();
                process.set(host);
                Path session = Files.createTempFile(dev, "current-run-", ".json");
                try {
                    SdkRuntimeLock.JSON.writeValue(session.toFile(), Map.of(
                            "run", run.getFileName().toString(), "pid", host.pid(),
                            "port", serverPort,
                            "started", host.info().startInstant().orElseThrow().toString()));
                    Files.move(session, dev.resolve("current-run.json"), StandardCopyOption.ATOMIC_MOVE);
                } finally {
                    Files.deleteIfExists(session);
                }
                System.out.println(MessageBundles.get(debug ? "sdk.debug" : "sdk.running",
                        receipt.path("pluginId").asText(), mode, Integer.toString(debugPort)));
                System.out.println("PIXIV_SDK_HOST_STARTED");
                while (!host.waitFor(100, TimeUnit.MILLISECONDS)) {
                    if (Files.exists(run.resolve("stop.request"), LinkOption.NOFOLLOW_LINKS)) {
                        stopOwnedProcess(run, host);
                    }
                }
                return host.exitValue();
            } finally {
                stopOwnedProcess(run, process.get());
                Runtime.getRuntime().removeShutdownHook(shutdown);
            }
        }
    }

    static void stop(Path project) throws IOException {
        Path current = project.resolve(".dev/current-run.json");
        if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) return;
        var metadata = SdkRuntimeLock.readJson(current);
        Path run = runPath(project, metadata);
        Files.writeString(run.resolve("stop.request"), "", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS);
    }

    private static void stopPrevious(Path project) throws Exception {
        Path current = project.resolve(".dev/current-run.json");
        if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) return;
        var metadata = SdkRuntimeLock.readJson(current);
        Path run = runPath(project, metadata);
        var host = ProcessHandle.of(metadata.path("pid").longValue())
                .filter(process -> process.info().startInstant().map(Object::toString).orElse("")
                        .equals(metadata.path("started").asText()));
        if (host.isPresent()) {
            stop(project);
            host.get().onExit().get(STOP_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        }
        Files.delete(current);
    }

    private static Path runPath(Path project, JsonNode metadata) throws IOException {
        String id = metadata.path("run").asText();
        if (!id.matches("[0-9a-f]{16}")) {
            throw new IOException("SDK_SESSION_ID");
        }
        Path run = project.resolve(".dev/runs").resolve(id);
        if (!run.toRealPath().equals(run) || !Files.isRegularFile(run.resolve(".sdk-run"), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("SDK_SESSION_ROOT");
        }
        return run;
    }

    private static void cleanOldRuns(Path project, Path activeRun, Path state, SdkRuntimeLock lock) throws Exception {
        Path dev = project.resolve(".dev");
        Path runs = dev.resolve("runs");
        SdkRuntimeArchive.requireDirectory(runs);
        try (var children = Files.list(runs)) {
            var previous = children.filter(path -> !path.equals(activeRun)).limit(17).toList();
            if (previous.size() > 16) throw new IOException("SDK_RUN_CAPACITY");
            for (Path run : previous) {
                if (!run.getFileName().toString().matches("[0-9a-f]{16}")
                        || !Files.isDirectory(run, LinkOption.NOFOLLOW_LINKS)
                        || !run.toRealPath().equals(run)
                        || !Files.isRegularFile(run.resolve(".sdk-run"), LinkOption.NOFOLLOW_LINKS)) continue;
                // 使用本次刚核验的宿主执行清理，不执行可能已被开发插件改写的旧宿主。
                List<String> cleanup = javaCommand(activeRun, state);
                cleanup.addAll(List.of("-Dloader.main=top.sywyar.pixivdownload.plugin.sdk.SdkPluginInstaller",
                        "-cp", activeRun.resolve(lock.runtime().host().file()).toString(),
                        "org.springframework.boot.loader.launch.PropertiesLauncher",
                        "cleanup", project.toString(), run.toString()));
                if (!install(cleanup, activeRun).path("cleanup").asBoolean()) throw new IOException("SDK_CLEANUP_RECEIPT");
                // 只清理持项目锁时确认的自有运行副本；不跟随链接，也不删除持久化 state。
                try (var entries = Files.walk(run)) {
                    var owned = entries.limit(SdkRuntimeArchive.MAX_ENTRIES + 8193L).toList();
                    if (owned.size() > SdkRuntimeArchive.MAX_ENTRIES + 8192) throw new IOException("SDK_CLEANUP_LIMIT");
                    for (Path entry : owned.stream().sorted(java.util.Comparator.reverseOrder()).toList()) {
                        Files.delete(entry);
                    }
                }
            }
        }
    }

    private static JsonNode install(List<String> command, Path run) throws Exception {
        Path log = run.resolve("install.log");
        Process process = processBuilder(command, run).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        long deadline = System.nanoTime() + INSTALL_TIMEOUT.toNanos();
        try {
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                if (System.nanoTime() > deadline || Files.size(log) > MAX_INSTALL_LOG_BYTES) {
                    throw new IOException("SDK_INSTALL_LIMIT");
                }
            }
            if (Files.size(log) > MAX_INSTALL_LOG_BYTES) throw new IOException("SDK_INSTALL_LIMIT");
            if (process.exitValue() != 0) throw new IOException("SDK_INSTALL_FAILED: " + log);
            List<String> receipts;
            try (var lines = Files.lines(log, StandardCharsets.UTF_8)) {
                receipts = lines.filter(line -> line.startsWith(INSTALL_RESULT)).toList();
            }
            if (receipts.size() != 1) throw new IOException("SDK_INSTALL_RECEIPT");
            return SdkRuntimeLock.JSON.readTree(receipts.get(0).substring(INSTALL_RESULT.length()));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private static List<String> javaCommand(Path run, Path state) {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", executable)
                .toString(), "-Dfile.encoding=UTF-8", "-Dpixivdownload.plugins-dir=" + run.resolve("plugins")));
        for (String kind : List.of("config", "state", "data", "instance")) {
            command.add("-Dpixivdownload." + kind + "-dir=" + state.resolve(kind));
        }
        return command;
    }

    private static ProcessBuilder processBuilder(List<String> command, Path run) {
        var builder = new ProcessBuilder(command).directory(run.toFile());
        builder.environment().keySet().removeIf(key -> key.startsWith("LOADER_")
                || List.of("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS").contains(key));
        return builder;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    private static void waitForDebugger(int port) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            // 不连接 JDWP socket 做探测，避免占用 IDE 正在等待的唯一调试会话。
            try (ServerSocket probe = new ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))) {
                if (probe.getLocalPort() != port) throw new IOException("SDK_DEBUG_PORT");
            } catch (BindException listening) {
                return;
            }
            Thread.sleep(100);
        }
        throw new IOException("SDK_DEBUGGER_NOT_LISTENING");
    }

    private static void stopOwnedProcess(Path run, Process host) {
        if (host == null || !host.isAlive()) return;
        try {
            Files.writeString(run.resolve("stop.request"), "", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS);
            if (host.waitFor(STOP_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) return;
        } catch (IOException | InterruptedException failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
        }
        var children = host.descendants().toList();
        host.destroyForcibly();
        children.forEach(ProcessHandle::destroyForcibly);
    }
}
