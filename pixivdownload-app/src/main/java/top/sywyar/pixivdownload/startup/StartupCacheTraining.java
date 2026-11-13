package top.sywyar.pixivdownload.startup;

import top.sywyar.pixivdownload.common.PlainFilePathGuard;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.plugin.api.database.migration.MigrationFiles;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopStartupCache.Phase;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.Consumer;

/** 同一训练流程供桌面工具和发行打包使用。 */
public final class StartupCacheTraining {
    public static final String TRAINING_PROPERTY = "pixivdownload.aot.training";
    public static final String READY_FILE = "startup-training.ready";
    private static final String OWNER = "PixivDownloader desktop AOT training 1";
    private StartupCacheTraining() {}

    static String identity(Path jar, List<String> options, StartupCacheInputs.Snapshot inputs) throws Exception {
        var digest = StartupCacheInputs.digest();
        digest.update(DistributionLauncher.identity(jar, options).getBytes(StandardCharsets.US_ASCII));
        digest.update(inputs.identity().getBytes(StandardCharsets.US_ASCII));
        return HexFormat.of().formatHex(digest.digest());
    }

    static String bundledIdentity(Path jar, List<String> options, StartupCacheInputs.Snapshot inputs) throws Exception {
        var digest = StartupCacheInputs.digest();
        // 路径与实例属性随安装位置变化；VM 选项仍参与身份，并由 JVM 再做兼容性校验。
        digest.update(DistributionLauncher.identity(jar, options.stream().filter(value -> !value.startsWith("-D")).toList(), true)
                .getBytes(StandardCharsets.US_ASCII));
        digest.update(inputs.identity().getBytes(StandardCharsets.US_ASCII));
        return HexFormat.of().formatHex(digest.digest());
    }

    static Path bundledCache(Path jar, List<String> options, StartupCacheInputs.Snapshot inputs) throws Exception {
        String value = System.getProperty("pixivdownload.aot.bundled");
        if (value == null) return null;
        Path cache = Path.of(value);
        if (!PlainFilePathGuard.isPlainRegularFile(cache)) return null;
        return DistributionLauncher.validCache(cache, Path.of(cache + ".identity"), bundledIdentity(jar, options, inputs))
                ? cache : null;
    }

    static void train(Path jar, List<String> options, StartupCacheInputs.Snapshot inputs,
                      Path cache, Path directory, Consumer<Phase> progress) throws Exception {
        PlainFilePathGuard.requirePlainParent(directory.resolve("owner"), true);
        if (Files.exists(directory.resolve("owner"), LinkOption.NOFOLLOW_LINKS)) clean(directory);
        else try (var entries = Files.list(directory)) {
            if (entries.findAny().isPresent()) throw new IOException("Training directory is not owned");
        }
        Files.createDirectories(directory);
        MigrationFiles.makePrivate(directory);
        Files.writeString(directory.resolve("owner"), OWNER, StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW);
        try {
            StartupCacheInputs.copy(inputs, directory.resolve("plugins"), directory.resolve("config/config.yaml"),
                    directory.resolve("state").resolve(RuntimeFiles.resolvePluginCatalogTrustStatePath().getFileName()));
            var isolated = new ArrayList<>(options.stream().filter(option -> !option.startsWith("-D")).toList());
            isolated.addAll(List.of("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
                    "-D" + TRAINING_PROPERTY + "=true",
                    "-Dpixivdownload.config-dir=config", "-Dpixivdownload.state-dir=state",
                    "-Dpixivdownload.data-dir=data", "-Dpixivdownload.plugins-dir=plugins",
                    "-Dpixivdownload.instance-dir=instance"));
            execute(DistributionLauncher.command(jar, isolated, "--setup", "--username=aot-training",
                    "--password=" + UUID.randomUUID(), "--mode=solo", "--proxy-enabled=false"), directory, Phase.PREPARING);
            Files.createDirectories(directory.resolve("state/gui"));
            Files.writeString(directory.resolve("state/gui/wizard-finished"), "1", StandardCharsets.UTF_8);
            Path configuration = directory.resolve("application.aot.config");
            var recording = new ArrayList<>(isolated);
            recording.addAll(List.of("-XX:AOTMode=record", "-XX:AOTConfiguration=" + configuration));
            progress.accept(Phase.RECORDING);
            StartupCacheInputs.verifyCurrent(inputs);
            int port;
            try (var socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
                port = socket.getLocalPort();
            }
            execute(DistributionLauncher.command(jar, recording, "--server.port=" + port,
                    "--server.address=127.0.0.1"), directory, Phase.RECORDING);
            if (!Files.isRegularFile(directory.resolve(READY_FILE), LinkOption.NOFOLLOW_LINKS))
                throw new TrainingFailure(Phase.RECORDING, -1);
            StartupCacheInputs.checkInterrupted();
            progress.accept(Phase.ASSEMBLING);
            StartupCacheInputs.verifyCurrent(inputs);
            var assembling = new ArrayList<>(isolated);
            assembling.addAll(List.of("-XX:AOTMode=create", "-XX:AOTConfiguration=" + configuration, "-XX:AOTCache=" + cache));
            execute(DistributionLauncher.command(jar, assembling), directory, Phase.ASSEMBLING);
            StartupCacheInputs.checkInterrupted();
            progress.accept(Phase.VERIFYING);
            var check = new ArrayList<>(List.of(DistributionLauncher.java()));
            check.addAll(options);
            check.addAll(List.of("-XX:AOTMode=on", "-XX:AOTCache=" + cache, "-cp", jar.toString(), "-version"));
            execute(check, directory, Phase.VERIFYING);
            StartupCacheInputs.verifyCurrent(inputs);
            PlainFilePathGuard.requirePlainRegularFile(cache);
        } catch (Exception | Error failure) {
            try { clean(directory); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        clean(directory);
    }

    static void execute(List<String> command, Path directory, Phase phase) throws Exception {
        StartupCacheInputs.checkInterrupted();
        var builder = new ProcessBuilder(command).directory(directory.toFile())
                .redirectErrorStream(true);
        builder.environment().keySet().removeIf(key ->
                !key.matches("(?i)PATH|SYSTEMROOT|WINDIR|COMSPEC|PATHEXT|TEMP|TMP|LANG|LC_ALL"
                        + "|DISPLAY|XAUTHORITY|WAYLAND_DISPLAY|XDG_RUNTIME_DIR"));
        Process child = builder.start();
        // 只保留末尾诊断，插件标准输出不能使训练日志无限增长。
        byte[] tail = new byte[65536];
        long[] length = {0};
        Thread output = Thread.ofPlatform().daemon().name("startup-training-output").start(() -> {
            try (var input = child.getInputStream()) {
                byte[] buffer = new byte[8192];
                for (int read; (read = input.read(buffer)) != -1;) {
                    for (int index = 0; index < read; index++) tail[(int) (length[0]++ % tail.length)] = buffer[index];
                }
            } catch (IOException closed) { /* 子进程结束或取消关闭管道。 */ }
        });
        Thread shutdown = new Thread(() -> terminate(child), "startup-training-child-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdown);
        try {
            int code = child.waitFor();
            output.join();
            if (code != 0) {
                if (phase != Phase.PREPARING) {
                    Path diagnostic = directory.getParent().resolve("desktop-training.log");
                    PlainFilePathGuard.requirePlainParent(diagnostic, false);
                    if (Files.exists(diagnostic, LinkOption.NOFOLLOW_LINKS)) PlainFilePathGuard.requirePlainRegularFile(diagnostic);
                    int bytes = (int) Math.min(length[0], tail.length);
                    byte[] ordered = new byte[bytes];
                    for (int index = 0; index < bytes; index++)
                        ordered[index] = tail[(int) ((length[0] - bytes + index) % tail.length)];
                    Files.write(diagnostic, ordered, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS);
                    MigrationFiles.makePrivate(diagnostic);
                }
                throw new TrainingFailure(phase, code);
            }
        } finally {
            terminate(child);
            output.join();
            try { Runtime.getRuntime().removeShutdownHook(shutdown); }
            catch (IllegalStateException exiting) { /* 进程关闭钩子已接管子进程。 */ }
        }
    }

    private static void terminate(Process child) {
        if (!child.isAlive()) return;
        var descendants = child.descendants().toList();
        descendants.reversed().forEach(ProcessHandle::destroyForcibly);
        child.destroyForcibly();
        child.onExit().join();
        descendants.forEach(handle -> handle.onExit().join());
    }

    static void clean(Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        PlainFilePathGuard.requirePlainParent(directory.resolve("owner"), false);
        Path marker = directory.resolve("owner");
        PlainFilePathGuard.requirePlainRegularFile(marker);
        try (var input = Files.newInputStream(marker, LinkOption.NOFOLLOW_LINKS)) {
            if (!new String(input.readNBytes(OWNER.length() + 1), StandardCharsets.US_ASCII).equals(OWNER))
                throw new IOException("Training directory is not owned");
        }
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes) throws IOException {
                if (attributes.isOther() || attributes.isSymbolicLink()) throw new IOException("Unsafe training directory");
                MigrationFiles.makePrivate(path);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException {
                if (!attributes.isSymbolicLink() && !attributes.isOther()) {
                    MigrationFiles.makePrivate(path);
                    var dos = Files.getFileAttributeView(path, java.nio.file.attribute.DosFileAttributeView.class,
                            LinkOption.NOFOLLOW_LINKS);
                    if (dos != null) dos.setReadOnly(false);
                }
                Files.delete(path);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path path, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(path);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static final class TrainingFailure extends IOException {
        final Phase phase;
        final int exitCode;
        TrainingFailure(Phase phase, int code) {
            super("Startup cache step " + phase + " failed (exit " + code + ")");
            this.phase = phase; this.exitCode = code;
        }
    }
}
