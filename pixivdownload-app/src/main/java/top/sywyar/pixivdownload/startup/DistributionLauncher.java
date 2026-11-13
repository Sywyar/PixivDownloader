package top.sywyar.pixivdownload.startup;

import top.sywyar.pixivdownload.common.Utf8ConsoleStreams;
import top.sywyar.pixivdownload.common.PlainFilePathGuard;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.cli.CliSetupCommand;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.jar.JarFile;

/** 解包发行与原生包入口，在创建应用 JVM 前选择兼容的启动缓存。 */
public final class DistributionLauncher {
    public static final String JVM_MARKER = "-Dpixivdownload.distribution.launcher=true";
    private DistributionLauncher() { }

    public static void main(String[] args) throws Exception {
        Utf8ConsoleStreams.install();
        Path jar = Path.of(DistributionLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        if (args.length == 3 && args[0].equals("prepare")) {
            prepare(jar, Path.of(args[1]).toAbsolutePath(), Path.of(args[2]).toAbsolutePath());
        } else if (args.length == 3 && args[0].equals("prepare-gui")) {
            var inputs = StartupCacheInputs.capture(RuntimeFiles.pluginsDirectory(), RuntimeFiles.resolveConfigYamlPath());
            StartupCacheTraining.train(jar, DesktopStartupCacheService.options(), inputs,
                    Path.of(args[1]).toAbsolutePath(), Path.of(args[2]).toAbsolutePath(),
                    phase -> System.out.println("AOT distribution: GUI training " + phase));
            Path cache = Path.of(args[1]).toAbsolutePath();
            Files.writeString(Path.of(cache + ".identity"),
                    StartupCacheTraining.bundledIdentity(jar, DesktopStartupCacheService.options(), inputs) + "\n" + hash(cache),
                    StandardCharsets.US_ASCII, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS);
        } else if (args.length > 0 && args[0].equals("run") || System.getProperty("pixivdownload.aot.bundled") != null) {
            String[] applicationArgs = args.length > 0 && args[0].equals("run")
                    ? Arrays.copyOfRange(args, 1, args.length) : args;
            System.setProperty("pixivdownload.distribution.launcher", "true");
            int exit = run(jar, applicationArgs);
            if (exit >= 0) System.exit(exit);
            Class.forName(mainClass(jar)).getMethod("main", String[].class).invoke(null, (Object) applicationArgs);
        } else {
            throw new IllegalArgumentException("Expected run <application arguments>, prepare or prepare-gui <cache> <training directory>");
        }
    }

    static String java() {
        return Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString();
    }

    private static boolean isWindows() { return System.getProperty("os.name").startsWith("Windows"); }

    private static String mainClass(Path jar) throws IOException {
        try (var archive = new JarFile(jar.toFile())) {
            return archive.getManifest().getMainAttributes().getValue("Main-Class");
        }
    }

    static List<String> command(Path jar, List<String> options, String... args) throws IOException {
        var result = new ArrayList<String>();
        result.add(java());
        result.addAll(options);
        result.addAll(List.of("-cp", jar.toString(), mainClass(jar)));
        result.addAll(List.of(args));
        return result;
    }

    private static int execute(List<String> command, Path directory, boolean isolated) throws Exception {
        var builder = new ProcessBuilder(command).inheritIO();
        if (directory != null) builder.directory(directory.toFile());
        if (isolated) builder.environment().keySet().removeIf(key ->
                !key.matches("(?i)PATH|SYSTEMROOT|WINDIR|COMSPEC|PATHEXT|TEMP|TMP|LANG|LC_ALL"));
        Process child = builder.start();
        Thread shutdown = new Thread(() -> {
            if (isolated) child.descendants().forEach(ProcessHandle::destroy);
            child.destroy();
            child.onExit().join();
        }, "distribution-child-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdown);
        try { return child.waitFor(); }
        finally {
            if (child.isAlive()) child.destroy();
            try { Runtime.getRuntime().removeShutdownHook(shutdown); }
            catch (IllegalStateException shuttingDown) { /* JVM 正在执行关闭钩子。 */ }
        }
    }

    private static void prepare(Path jar, Path cache, Path training) throws Exception {
        Files.createDirectory(training);
        List<String> options = List.of("-Dfile.encoding=UTF-8", "-Djava.awt.headless=true", "-Dpixivdownload.instance-dir=instance");
        if (execute(command(jar, options, "--setup", "--username=aot-training", "--password=" + UUID.randomUUID(),
                "--mode=solo", "--proxy-enabled=false"), training, true) != 0) throw new IOException("AOT setup failed");
        var recording = new ArrayList<>(options);
        Path configuration = training.resolve("application.aot.config");
        recording.addAll(List.of("-XX:AOTMode=record", "-XX:AOTConfiguration=" + configuration));
        recording.add("-Dspring.context.exit=onRefresh");
        if (execute(command(jar, recording, "--no-gui", "--server.port=0"), training, true) != 0
                || createCache(jar, options, configuration, cache, training, true) != 0
                || !Files.isRegularFile(cache, LinkOption.NOFOLLOW_LINKS)) throw new IOException("AOT preparation failed");
        System.out.println("AOT distribution: prepared cache");
    }

    private static int createCache(Path jar, List<String> options, Path configuration, Path cache,
                                   Path directory, boolean isolated) throws Exception {
        // 显式派生 JVM，避免 Windows 自动组装进程经窄字符环境变量损坏非 ASCII 路径。
        var assembling = new ArrayList<>(options);
        assembling.addAll(List.of("-XX:AOTMode=create", "-XX:AOTConfiguration=" + configuration, "-XX:AOTCache=" + cache));
        return execute(command(jar, assembling), directory, isolated);
    }

    private static int run(Path jar, String[] args) throws Exception {
        if (CliSetupCommand.containsCliCommand(args) || List.of(args).contains("--help") || List.of(args).contains("-h")) return -1;
        // 代理已属于当前 JVM；重复启动可能再次绑定调试端口。
        List<String> options = new ArrayList<>(ManagementFactory.getRuntimeMXBean().getInputArguments());
        if (!options.contains(JVM_MARKER)) options.add(JVM_MARKER);
        if (options.stream().anyMatch(option -> option.startsWith("-agent") || option.startsWith("-javaagent:")
                || option.startsWith("-Xrunjdwp:") || option.startsWith("-XX:AOT") || option.equals("-Xshare:off"))) {
            System.out.println("AOT distribution: explicit JVM cache or agent options; starting without managed cache");
            System.setProperty("pixivdownload.aot.disabled", "true");
            return -1;
        }
        if (!List.of(args).contains("--no-gui") && !java.awt.GraphicsEnvironment.isHeadless()) {
            return runDesktop(jar, args);
        }
        Path root;
        String key;
        FileChannel channel = null;
        FileLock lock;
        try {
            var vm = ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class);
            if (vm == null) return -1;
            vm.getVMOption("AOTMode");
            root = RuntimeFiles.aotCacheDirectory().toAbsolutePath();
            PlainFilePathGuard.requirePlainParent(root.resolve("prepare.lock"), true);
            key = identity(jar, options);
            channel = FileChannel.open(root.resolve("prepare.lock"), StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            lock = channel.tryLock();
        } catch (IOException | IllegalArgumentException error) {
            if (channel != null) channel.close();
            System.err.println("AOT distribution: cache unavailable (" + error.getClass().getSimpleName() + "); starting normally");
            return -1;
        }
        int result = -1;
        try (var owner = channel; lock) {
            if (lock == null) {
                System.out.println("AOT distribution: another process is preparing the cache; starting normally");
                return -1;
            }
            Path cache = root.resolve("application.aot"), digest = root.resolve("identity");
            if (validCache(cache, digest, key)) {
                var cached = new ArrayList<>(options);
                cached.add("-XX:AOTMode=auto");
                cached.add("-XX:AOTCache=" + cache);
                System.out.println("AOT distribution: using verified cache");
                result = execute(command(jar, cached, args), null, false);
                return result;
            }
            // 正常退出且输入未变才发布；中断的录制不得复用。
            Path pending = root.resolve("recording.aot");
            Path configuration = root.resolve("recording.aot.config");
            Files.deleteIfExists(pending);
            Files.deleteIfExists(configuration);
            var recording = new ArrayList<>(options);
            recording.addAll(List.of("-XX:AOTMode=record", "-XX:AOTConfiguration=" + configuration));
            System.out.println("AOT distribution: recording this run; cache assembly follows normal application exit");
            result = execute(command(jar, recording, args), null, false);
            try {
                if (result == 0 && Files.isRegularFile(configuration, LinkOption.NOFOLLOW_LINKS) && identity(jar, options).equals(key)
                        && createCache(jar, options, configuration, pending, null, false) == 0) {
                    var check = new ArrayList<>(List.of(java()));
                    check.addAll(options);
                    check.addAll(List.of("-XX:AOTMode=on", "-XX:AOTCache=" + pending, "-cp", jar.toString(), "-version"));
                    if (execute(check, null, false) == 0) {
                        Files.move(pending, cache, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                        Files.writeString(digest, key + "\n" + hash(cache), StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                                StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS);
                        System.out.println("AOT distribution: cache ready");
                    } else System.err.println("AOT distribution: JVM rejected recorded cache; cache not published");
                }
            } catch (IOException error) {
                System.err.println("AOT distribution: cache publication failed (" + error.getClass().getSimpleName() + ")");
            } finally {
                Files.deleteIfExists(pending);
                Files.deleteIfExists(configuration);
            }
            return result;
        } catch (IOException error) {
            System.err.println("AOT distribution: cache access failed (" + error.getClass().getSimpleName() + ")"
                    + (result < 0 ? "; starting normally" : "; preserving application exit status"));
            return result;
        }
    }

    private static int runDesktop(Path jar, String[] args) throws Exception {
        var options = new ArrayList<>(DesktopStartupCacheService.options());
        Path cache = null;
        try {
            var inputs = StartupCacheInputs.capture(RuntimeFiles.pluginsDirectory(), RuntimeFiles.resolveConfigYamlPath());
            String key = StartupCacheTraining.identity(jar, options, inputs);
            cache = new DesktopCacheStore(RuntimeFiles.aotCacheDirectory()).find(key)
                    .map(DesktopCacheStore.Entry::cache).orElse(null);
            if (cache == null) cache = StartupCacheTraining.bundledCache(jar, options, inputs);
        } catch (Exception unavailable) {
            System.err.println("AOT distribution: desktop cache unavailable (" + unavailable.getClass().getSimpleName() + ")");
        }
        if (cache == null) {
            System.out.println("AOT distribution: desktop cache miss; starting normally");
            return -1;
        }
        options.add("-XX:AOTMode=auto");
        options.add("-XX:AOTCache=" + cache.toAbsolutePath());
        System.out.println("AOT distribution: using desktop cache");
        return execute(command(jar, options, args), null, false);
    }

    static boolean validCache(Path cache, Path digest, String key) throws IOException {
        return Files.isRegularFile(cache, LinkOption.NOFOLLOW_LINKS)
                && Files.isRegularFile(digest, LinkOption.NOFOLLOW_LINKS) && Files.size(digest) == 129
                && Files.readString(digest, StandardCharsets.UTF_8).equals(key + "\n" + hash(cache));
    }

    static String identity(Path jar, List<String> options) throws Exception {
        return identity(jar, options, false);
    }

    static String identity(Path jar, List<String> options, boolean portable) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        var paths = new ArrayList<Path>();
        paths.add(jar.toRealPath());
        try (var libraries = Files.list(jar.getParent().resolve("lib"))) {
            paths.addAll(libraries.filter(path -> path.toString().endsWith(".jar")).sorted().toList());
        }
        Path home = Path.of(System.getProperty("java.home"));
        paths.addAll(List.of(home.resolve("release"), home.resolve("lib/modules"), Path.of(java()),
                home.resolve(isWindows() ? "bin/server/jvm.dll" : System.getProperty("os.name").equals("Mac OS X")
                        ? "lib/server/libjvm.dylib" : "lib/server/libjvm.so")));
        for (Path path : paths) {
            digest.update((portable ? path.getFileName().toString() : path.toAbsolutePath().toString())
                    .getBytes(StandardCharsets.UTF_8));
            digest.update(hash(path).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        for (String option : options) {
            digest.update((option + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static String hash(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[65536];
                for (int read; (read = input.read(buffer)) != -1;) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
