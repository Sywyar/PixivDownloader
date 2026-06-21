package top.sywyar.pixivdownload.gui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.i18n.MessageBundles;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

final class SingleInstanceManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SingleInstanceManager.class);

    private static final String LOCK_FILE = "single-instance.lock";
    private static final String ACTIVATION_FILE = "single-instance.txt";
    private static final int NOTIFY_RETRIES = 20;
    private static final long NOTIFY_RETRY_DELAY_MS = 150L;

    private final Path activationFile;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final ServerSocket activationServer;
    private final String activationToken;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private volatile Runnable activationHandler = () -> {};

    private SingleInstanceManager(Path activationFile,
                                  FileChannel lockChannel,
                                  FileLock lock,
                                  ServerSocket activationServer,
                                  String activationToken) {
        this.activationFile = activationFile;
        this.lockChannel = lockChannel;
        this.lock = lock;
        this.activationServer = activationServer;
        this.activationToken = activationToken;
    }

    static SingleInstanceManager acquire() throws IOException {
        Path instanceDir = RuntimeFiles.singleInstanceDirectory();
        Files.createDirectories(instanceDir);

        Path lockFile = instanceDir.resolve(LOCK_FILE);
        FileChannel channel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);

        FileLock fileLock;
        try {
            fileLock = channel.tryLock();
        } catch (OverlappingFileLockException e) {
            closeQuietly(channel);
            return null;
        }

        if (fileLock == null) {
            closeQuietly(channel);
            return null;
        }

        Path activationFile = instanceDir.resolve(ACTIVATION_FILE);
        try {
            ServerSocket activationServer = new ServerSocket();
            activationServer.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            String activationToken = UUID.randomUUID().toString();
            writeActivationFile(activationFile, activationServer.getLocalPort(), activationToken);

            SingleInstanceManager manager = new SingleInstanceManager(
                    activationFile, channel, fileLock, activationServer, activationToken);
            manager.startActivationListener();
            return manager;
        } catch (IOException e) {
            closeQuietly(fileLock);
            closeQuietly(channel);
            throw e;
        }
    }

    static boolean signalExistingInstance() {
        Path activationFile = RuntimeFiles.singleInstanceDirectory().resolve(ACTIVATION_FILE);
        for (int attempt = 0; attempt < NOTIFY_RETRIES; attempt++) {
            ActivationTarget target = readActivationTarget(activationFile);
            if (target != null && signal(target)) {
                return true;
            }
            sleepBeforeRetry(attempt);
        }
        return false;
    }

    void setActivationHandler(Runnable activationHandler) {
        this.activationHandler = Objects.requireNonNullElse(activationHandler, () -> {});
    }

    private void startActivationListener() {
        Thread listenerThread = new Thread(this::runActivationLoop, "single-instance-activation");
        listenerThread.setDaemon(true);
        listenerThread.start();
    }

    private void runActivationLoop() {
        while (!closed.get()) {
            try (Socket socket = activationServer.accept()) {
                handleActivation(socket);
            } catch (SocketException e) {
                if (!closed.get()) {
                    log.warn(logMessage("gui.single-instance.log.activation-socket.closed", e.getMessage()));
                }
                return;
            } catch (IOException e) {
                if (!closed.get()) {
                    log.warn(logMessage("gui.single-instance.log.activation-request.failed", e.getMessage()));
                }
            }
        }
    }

    private void handleActivation(Socket socket) throws IOException {
        socket.setSoTimeout(1000);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            String token = reader.readLine();
            if (!activationToken.equals(token)) {
                writer.write("ERR");
                writer.newLine();
                writer.flush();
                return;
            }

            activationHandler.run();
            writer.write("OK");
            writer.newLine();
            writer.flush();
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        try {
            Files.deleteIfExists(activationFile);
        } catch (IOException e) {
            log.debug(logMessage("gui.single-instance.log.activation-file.delete-failed",
                    activationFile, e.getMessage()));
        }
        closeQuietly(activationServer);
        closeQuietly(lock);
        closeQuietly(lockChannel);
    }

    private static void writeActivationFile(Path activationFile, int port, String token) throws IOException {
        List<String> lines = List.of(
                Integer.toString(port),
                token
        );
        Path tempFile = activationFile.resolveSibling(activationFile.getFileName() + ".tmp");
        Files.write(tempFile, lines, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        try {
            Files.move(tempFile, activationFile,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tempFile, activationFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static ActivationTarget readActivationTarget(Path activationFile) {
        if (!Files.isRegularFile(activationFile)) {
            return null;
        }
        try {
            List<String> lines = Files.readAllLines(activationFile, StandardCharsets.UTF_8);
            if (lines.size() < 2) {
                return null;
            }
            int port = Integer.parseInt(lines.get(0).trim());
            String token = lines.get(1).trim();
            if (token.isEmpty()) {
                return null;
            }
            return new ActivationTarget(port, token);
        } catch (Exception e) {
            log.debug(logMessage("gui.single-instance.log.activation-target.read-failed",
                    activationFile, e.getMessage()));
            return null;
        }
    }

    private static boolean signal(ActivationTarget target) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), target.port()), 500);
            socket.setSoTimeout(1000);
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                writer.write(target.token());
                writer.newLine();
                writer.flush();
                return "OK".equals(reader.readLine());
            }
        } catch (IOException e) {
            log.debug(logMessage("gui.single-instance.log.signal-existing.failed",
                    target.port(), e.getMessage()));
            return false;
        }
    }

    private static void sleepBeforeRetry(int attempt) {
        if (attempt >= NOTIFY_RETRIES - 1) {
            return;
        }
        try {
            Thread.sleep(NOTIFY_RETRY_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }

    private record ActivationTarget(int port, String token) {
    }

    private static String logMessage(String code, Object... args) {
        return MessageBundles.get(code, args);
    }
}
