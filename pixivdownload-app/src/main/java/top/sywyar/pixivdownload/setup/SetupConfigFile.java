package top.sywyar.pixivdownload.setup;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** setup 配置的原子读写入口。 */
public final class SetupConfigFile {

    private static final int MAX_BYTES = 1024 * 1024;

    private SetupConfigFile() {
    }

    public static SetupConfig read(Path path, ObjectMapper mapper) throws IOException {
        return mapper.readValue(readBytes(path), SetupConfig.class);
    }

    public static void write(Path path, SetupConfig config, ObjectMapper mapper) throws IOException {
        write(path, config, mapper, true);
    }

    /**
     * 原子写入配置。会话原文迁移时传 {@code backupCurrent=false}，避免把旧 bearer token 复制进备份。
     */
    static void write(Path path,
                      SetupConfig config,
                      ObjectMapper mapper,
                      boolean backupCurrent) throws IOException {
        Path parent = path.toAbsolutePath().normalize().getParent();
        if (parent == null) {
            throw new IOException("setup config requires a parent directory: " + path);
        }
        Files.createDirectories(parent);
        byte[] next = mapper.writeValueAsBytes(config);
        requireBounded(next.length, path);

        if (backupCurrent && Files.isRegularFile(path)) {
            byte[] current = readBytes(path);
            mapper.readValue(current, SetupConfig.class);
            writeAtomically(backupPath(path), current);
        } else if (!backupCurrent) {
            Files.deleteIfExists(backupPath(path));
        }

        writeAtomically(path, next);
        read(path, mapper);
    }

    public static Path backupPath(Path path) {
        return path.resolveSibling(path.getFileName() + ".bak");
    }

    private static byte[] readBytes(Path path) throws IOException {
        requireBounded(Files.size(path), path);
        byte[] bytes = Files.readAllBytes(path);
        requireBounded(bytes.length, path);
        return bytes;
    }

    private static void requireBounded(long size, Path path) throws IOException {
        if (size > MAX_BYTES) {
            throw new IOException("setup config exceeds 1 MiB: " + path);
        }
    }

    private static void writeAtomically(Path target, byte[] bytes) throws IOException {
        Path parent = target.toAbsolutePath().normalize().getParent();
        Path temp = Files.createTempFile(parent, "." + target.getFileName() + ".", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temp,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(temp, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                throw new IOException("atomic setup config replacement is not supported: " + target, e);
            }
            forceDirectory(parent);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void forceDirectory(Path directory) {
        if (System.getProperty("os.name", "").toLowerCase().contains("windows")) {
            return;
        }
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // Some file systems do not expose directory fsync through the JDK.
        }
    }
}
