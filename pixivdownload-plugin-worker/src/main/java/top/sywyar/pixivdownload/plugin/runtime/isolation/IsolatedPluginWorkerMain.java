package top.sywyar.pixivdownload.plugin.runtime.isolation;

import org.pf4j.DefaultPluginManager;
import org.pf4j.Plugin;
import org.pf4j.PluginManager;
import org.pf4j.PluginState;
import org.pf4j.PluginWrapper;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivPluginProvider;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** 仅由宿主启动的外置插件隔离 worker 入口。 */
public final class IsolatedPluginWorkerMain {

    private PluginManager manager;
    private String packageId;
    private PixivFeaturePlugin featurePlugin;
    private boolean featureStarted;

    private IsolatedPluginWorkerMain() {
    }

    public static void main(String[] args) {
        try (InputStream protocolInput = new FileInputStream(FileDescriptor.in);
             OutputStream protocolOutput = new FileOutputStream(FileDescriptor.out)) {
            installUtf8ConsoleStreams();
            System.setIn(InputStream.nullInputStream());
            new IsolatedPluginWorkerMain().run(protocolInput, protocolOutput);
        } catch (Throwable failure) {
            failure.printStackTrace(System.err);
        }
    }

    private static void installUtf8ConsoleStreams() {
        System.setOut(new PrintStream(OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(
                new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
    }

    private void run(InputStream rawInput, OutputStream rawOutput) throws IOException {
        try (DataInputStream input = new DataInputStream(rawInput);
             DataOutputStream output = new DataOutputStream(rawOutput)) {
            boolean running = true;
            while (running) {
                byte[] request = IsolatedPluginProtocol.readFrame(input);
                try (DataInputStream payload = new DataInputStream(new ByteArrayInputStream(request))) {
                    int command = payload.readUnsignedByte();
                    byte[] response;
                    try {
                        response = switch (command) {
                            case IsolatedPluginProtocol.INITIALIZE -> initialize(payload);
                            case IsolatedPluginProtocol.START_PACKAGE -> startPackage();
                            case IsolatedPluginProtocol.START_FEATURE -> startFeature();
                            case IsolatedPluginProtocol.STOP_FEATURE -> stopFeature();
                            case IsolatedPluginProtocol.STOP_PACKAGE -> stopPackage();
                            case IsolatedPluginProtocol.SHUTDOWN -> {
                                shutdown();
                                running = false;
                                yield IsolatedPluginProtocol.message(IsolatedPluginProtocol.SUCCESS, null);
                            }
                            default -> throw new IOException("unknown isolated plugin command: " + command);
                        };
                    } catch (Exception failure) {
                        response = IsolatedPluginProtocol.failure(failure);
                    }
                    IsolatedPluginProtocol.writeFrame(output, response);
                }
            }
        } finally {
            shutdownQuietly();
        }
    }

    private byte[] initialize(DataInputStream input) throws IOException {
        if (manager != null) {
            throw new IOException("isolated plugin worker is already initialized");
        }
        String expectedId = IsolatedPluginProtocol.readString(input);
        String expectedVersion = IsolatedPluginProtocol.readString(input);
        String expectedSha256 = IsolatedPluginProtocol.readString(input);
        Path artifact = Path.of(IsolatedPluginProtocol.readString(input)).toAbsolutePath().normalize();
        if (input.available() != 0) {
            throw new IOException("isolated plugin initialization contains trailing bytes");
        }
        requirePlainArtifact(artifact);
        if (!expectedSha256.equals(sha256Hex(artifact))) {
            throw new IOException("isolated plugin artifact digest changed before worker admission");
        }

        DefaultPluginManager opened = new DefaultPluginManager(artifact.getParent());
        String loadedId = opened.loadPlugin(artifact);
        if (!expectedId.equals(loadedId)) {
            throw new IOException("isolated plugin id does not match the verified descriptor");
        }
        PluginWrapper wrapper = opened.getPlugin(loadedId);
        if (wrapper == null || !Objects.equals(expectedVersion, wrapper.getDescriptor().getVersion())) {
            throw new IOException("isolated plugin version does not match the verified descriptor");
        }
        Plugin plugin = wrapper.getPlugin();
        if (!(plugin instanceof PixivPluginProvider provider)) {
            throw new IOException("isolated plugin main class does not implement PixivPluginProvider");
        }
        PixivFeaturePlugin feature = provider.featurePlugin();
        if (feature == null) {
            throw new IOException("isolated plugin featurePlugin() returned null");
        }
        IsolatedPluginProtocol.Snapshot snapshot = IsolatedPluginProtocol.Snapshot.capture(feature);
        manager = opened;
        packageId = loadedId;
        featurePlugin = feature;
        return IsolatedPluginProtocol.message(IsolatedPluginProtocol.SUCCESS, snapshot::writeTo);
    }

    private byte[] startPackage() throws IOException {
        requireInitialized();
        PluginState state = manager.startPlugin(packageId);
        if (state != PluginState.STARTED) {
            throw new IOException("isolated PF4J package did not start: " + state);
        }
        return IsolatedPluginProtocol.message(IsolatedPluginProtocol.SUCCESS, null);
    }

    private byte[] startFeature() throws IOException {
        requireInitialized();
        if (!featureStarted) {
            featurePlugin.start();
            featureStarted = true;
        }
        return IsolatedPluginProtocol.message(IsolatedPluginProtocol.SUCCESS, null);
    }

    private byte[] stopFeature() throws IOException {
        requireInitialized();
        if (featureStarted) {
            featurePlugin.stop();
            featureStarted = false;
        }
        return IsolatedPluginProtocol.message(IsolatedPluginProtocol.SUCCESS, null);
    }

    private byte[] stopPackage() throws IOException {
        requireInitialized();
        if (featureStarted) {
            featurePlugin.stop();
            featureStarted = false;
        }
        PluginState state = manager.stopPlugin(packageId);
        if (state == PluginState.STARTED) {
            throw new IOException("isolated PF4J package remained started");
        }
        return IsolatedPluginProtocol.message(IsolatedPluginProtocol.SUCCESS, null);
    }

    private void shutdown() {
        if (manager == null) {
            return;
        }
        if (featureStarted) {
            featurePlugin.stop();
            featureStarted = false;
        }
        manager.stopPlugin(packageId);
        if (!manager.unloadPlugin(packageId) || manager.getPlugin(packageId) != null) {
            throw new IllegalStateException("isolated PF4J package did not unload");
        }
        manager = null;
        packageId = null;
        featurePlugin = null;
    }

    private void shutdownQuietly() {
        try {
            shutdown();
        } catch (Throwable ignored) {
            // 宿主会在绝对超时内终止整个 worker，进程退出才是最终清退证明。
        }
    }

    private void requireInitialized() throws IOException {
        if (manager == null || packageId == null || featurePlugin == null) {
            throw new IOException("isolated plugin worker is not initialized");
        }
    }

    private static void requirePlainArtifact(Path artifact) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                artifact, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther()) {
            throw new IOException("isolated plugin artifact is not a plain regular file");
        }
    }

    private static String sha256Hex(Path artifact) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(artifact)) {
                byte[] buffer = new byte[8192];
                for (int read; (read = input.read(buffer)) >= 0; ) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }
}
