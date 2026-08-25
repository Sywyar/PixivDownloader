package top.sywyar.pixivdownload.plugin.runtime.install.recovery;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/** 插件恢复清单的有界 UTF-8 读取与原子持久化 owner。 */
public final class PluginRecoveryManifestStore {

    private static final String FILE_NAME = "transaction.properties";
    private static final long MAX_BYTES = 512L * 1024L;

    private PluginRecoveryManifestStore() {
    }

    public static Path manifestPath(Path transaction) {
        return Objects.requireNonNull(transaction, "transaction").resolve(FILE_NAME);
    }

    public static Path temporaryPath(Path transaction) {
        return Objects.requireNonNull(transaction, "transaction").resolve(FILE_NAME + ".tmp");
    }

    public static boolean exceedsMaximumSize(long bytes) {
        return bytes > MAX_BYTES;
    }

    public static ReadResult read(Path manifest) throws IOException {
        BasicFileAttributes attributes = readAttributesIfPresent(manifest).orElse(null);
        if (attributes == null || attributes.isSymbolicLink() || attributes.isOther()
                || !attributes.isRegularFile()) {
            throw new IOException("plugin transaction manifest is not a plain regular file");
        }
        if (exceedsMaximumSize(attributes.size())) {
            throw new IOException("plugin transaction manifest exceeds the supported size");
        }
        Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        ByteArrayOutputStream collected = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long byteCount = 0L;
        try (SeekableByteChannel channel = Files.newByteChannel(manifest, options);
             InputStream input = Channels.newInputStream(channel)) {
            int read;
            while ((read = input.read(buffer, 0, (int) Math.min(
                    buffer.length, MAX_BYTES + 1L - byteCount))) != -1) {
                collected.write(buffer, 0, read);
                byteCount += read;
                if (exceedsMaximumSize(byteCount)) {
                    throw new ReadException(
                            "plugin transaction manifest grew beyond the supported size while reading",
                            byteCount,
                            null);
                }
            }
        } catch (ReadException e) {
            throw e;
        } catch (IOException e) {
            throw new ReadException("failed to read plugin transaction manifest", byteCount, e);
        }
        byte[] bytes = collected.toByteArray();
        String content;
        try {
            content = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new ReadException("plugin transaction manifest is not valid UTF-8", bytes.length, e);
        }
        Properties properties = new RejectingProperties();
        try (Reader reader = new StringReader(content)) {
            properties.load(reader);
        } catch (IOException | IllegalArgumentException e) {
            throw new ReadException("plugin transaction manifest properties are invalid", bytes.length, e);
        }
        return new ReadResult(properties, bytes.length);
    }

    public static void persist(Path transaction, Properties properties, String comment) throws IOException {
        byte[] serialized = serialize(properties, comment);
        BasicFileAttributes transactionAttributes = readAttributesIfPresent(transaction).orElse(null);
        if (transactionAttributes == null || transactionAttributes.isSymbolicLink()
                || transactionAttributes.isOther() || !transactionAttributes.isDirectory()) {
            throw new IOException("plugin transaction directory is not a plain directory");
        }
        Path manifest = manifestPath(transaction);
        Path temporary = temporaryPath(transaction);
        BasicFileAttributes temporaryAttributes = readAttributesIfPresent(temporary).orElse(null);
        if (temporaryAttributes != null) {
            if (temporaryAttributes.isSymbolicLink() || temporaryAttributes.isOther()
                    || !temporaryAttributes.isRegularFile()) {
                throw new IOException("plugin transaction manifest temporary path is unsafe");
            }
            Files.delete(temporary);
        }
        BasicFileAttributes manifestAttributes = readAttributesIfPresent(manifest).orElse(null);
        if (manifestAttributes != null && (manifestAttributes.isSymbolicLink() || manifestAttributes.isOther()
                || !manifestAttributes.isRegularFile())) {
            throw new IOException("plugin transaction manifest path is unsafe");
        }
        Set<OpenOption> options = Set.of(
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
        try (SeekableByteChannel channel = Files.newByteChannel(temporary, options)) {
            ByteBuffer buffer = ByteBuffer.wrap(serialized);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            if (channel instanceof FileChannel fileChannel) {
                fileChannel.force(true);
            }
        }
        try {
            Files.move(temporary, manifest, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.deleteIfExists(temporary);
            throw new IOException("filesystem does not support atomic transaction manifest persistence", e);
        }
    }

    private static byte[] serialize(Properties properties, String comment) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        OutputStream bounded = new OutputStream() {
            private int count;

            @Override
            public void write(int value) throws IOException {
                requireCapacity(1);
                bytes.write(value);
                count++;
            }

            @Override
            public void write(byte[] source, int offset, int length) throws IOException {
                Objects.checkFromIndexSize(offset, length, source.length);
                requireCapacity(length);
                bytes.write(source, offset, length);
                count += length;
            }

            private void requireCapacity(int increment) throws IOException {
                if (increment < 0 || count > MAX_BYTES - increment) {
                    throw new IOException("generated transaction manifest exceeds the supported size");
                }
            }
        };
        try (Writer writer = new OutputStreamWriter(bounded, StandardCharsets.UTF_8)) {
            properties.store(writer, comment);
        }
        return bytes.toByteArray();
    }

    private static Optional<BasicFileAttributes> readAttributesIfPresent(Path path) throws IOException {
        try {
            return Optional.of(Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        }
    }

    public record ReadResult(Properties properties, long byteCount) {

        public ReadResult {
            properties = Objects.requireNonNull(properties, "properties");
            if (byteCount < 0L || exceedsMaximumSize(byteCount)) {
                throw new IllegalArgumentException("manifest byte count is outside the supported range");
            }
        }
    }

    public static final class ReadException extends IOException {

        private final long byteCount;

        private ReadException(String message, long byteCount, Throwable cause) {
            super(message, cause);
            this.byteCount = byteCount;
        }

        public long byteCount() {
            return byteCount;
        }
    }

    private static final class RejectingProperties extends Properties {

        @Override
        public synchronized Object put(Object key, Object value) {
            if (containsKey(key)) {
                throw new IllegalArgumentException("duplicate transaction manifest property: " + key);
            }
            return super.put(key, value);
        }
    }
}
