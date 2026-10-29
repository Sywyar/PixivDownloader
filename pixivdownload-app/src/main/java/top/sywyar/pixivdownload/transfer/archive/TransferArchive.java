package top.sywyar.pixivdownload.transfer.archive;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import top.sywyar.pixivdownload.common.PlainFilePathGuard;
import top.sywyar.pixivdownload.plugin.api.database.migration.MigrationFiles;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** 只交换逻辑条目；归档中的名称从不直接用作本机目标路径。 */
public final class TransferArchive {
    public static final long MAX_CONTENT_BYTES = 64L * 1024 * 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final String MANIFEST = "manifest.json";
    private final Limits limits;

    public TransferArchive() { this(new Limits(MAX_CONTENT_BYTES, 20_000, 8 * 1024 * 1024)); }

    public TransferArchive(Limits limits) { this.limits = Objects.requireNonNull(limits); }

    public enum Category { CONFIG, STATE, DATABASE, RESOURCE, CREDENTIALS, INVENTORY }

    public record Limits(long maxBytes, int maxEntries, int maxManifestBytes) {
        public Limits {
            if (maxBytes < 1 || maxBytes > Long.MAX_VALUE - 5L * 1024 * 1024 * 1024
                    || maxEntries < 1 || maxEntries > 20_000
                    || maxManifestBytes < 1 || maxManifestBytes > 8 * 1024 * 1024)
                throw new IllegalArgumentException("transfer.archive-limit");
        }
    }

    public record Descriptor(String owner, Category category, String path, int schemaVersion) {
        public Descriptor {
            if (owner == null || !owner.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
                    || category == null || schemaVersion < 1) throw new IllegalArgumentException("transfer.archive-invalid");
            requireLogicalPath(owner);
            requireLogicalPath(path);
        }
    }

    @FunctionalInterface public interface Content { InputStream open() throws IOException; }
    public record Source(Descriptor descriptor, Content content) {
        public Source { Objects.requireNonNull(descriptor); Objects.requireNonNull(content); }
    }
    public record Item(Descriptor descriptor, long size, String sha256) {
        public Item {
            Objects.requireNonNull(descriptor);
            if (size < 0 || sha256 == null || !sha256.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("transfer.archive-invalid");
        }
    }
    public record Manifest(int formatVersion, String applicationVersion, boolean encrypted, List<Item> items) {
        public Manifest {
            if (formatVersion != 1 || applicationVersion == null || applicationVersion.length() > 128)
                throw new IllegalArgumentException("transfer.archive-invalid");
            items = List.copyOf(items);
        }
    }
    public record Preview(Manifest manifest, String archiveSha256) {
        public Preview { Objects.requireNonNull(manifest); Objects.requireNonNull(archiveSha256); }
        public List<Item> items() { return manifest.items(); }
        public boolean encrypted() { return manifest.encrypted(); }
    }

    /** 目标必须尚不存在；只有写完并回读核验后才发布文件。 */
    public Preview write(Path destination, char[] password, String applicationVersion, List<Source> sources) throws IOException {
        if (sources.size() > limits.maxEntries()) throw new IOException("transfer.archive-limit");
        boolean encrypted = password != null && password.length > 0;
        validateDescriptors(sources.stream().map(Source::descriptor).toList(), encrypted);
        Path target = destination.toAbsolutePath().normalize();
        PlainFilePathGuard.requirePlainParent(target, false);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new FileAlreadyExistsException(target.toString());
        Path temporary = Files.createTempFile(target.getParent(), ".transfer-", ".part");
        try {
            MigrationFiles.makePrivate(temporary);
            try (OutputStream file = Files.newOutputStream(temporary, LinkOption.NOFOLLOW_LINKS);
                 OutputStream protectedStream = ArchiveProtection.encrypt(file, password);
                 ZipOutputStream zip = new ZipOutputStream(protectedStream, StandardCharsets.UTF_8)) {
                List<Item> items = new ArrayList<>();
                long total = 0;
                for (Source source : sources) {
                    checkInterrupted();
                    zip.putNextEntry(new ZipEntry("data/" + items.size()));
                    MessageDigest digest = digest();
                    long size;
                    try (InputStream input = source.content().open()) {
                        size = copy(input, zip, digest, limits.maxBytes() - total);
                    }
                    zip.closeEntry();
                    total += size;
                    items.add(new Item(source.descriptor(), size, HexFormat.of().formatHex(digest.digest())));
                }
                Manifest manifest = new Manifest(1, applicationVersion, encrypted, items);
                byte[] metadata = JSON.writeValueAsBytes(manifest);
                if (metadata.length > limits.maxManifestBytes()) throw new IOException("transfer.archive-limit");
                zip.putNextEntry(new ZipEntry(MANIFEST));
                zip.write(metadata);
                zip.closeEntry();
            }
            Preview verified = inspect(temporary, password);
            try (var channel = java.nio.channels.FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            PlainFilePathGuard.requirePlainParent(target, false);
            checkInterrupted();
            // 不使用可能覆盖既有文件的 ATOMIC_MOVE，目标冲突交给 no-clobber move 拒绝。
            Files.move(temporary, target);
            return verified;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** 完整消费正文及认证尾段，仅返回元数据，不创建文件或连接数据库。 */
    public Preview inspect(Path archive, char[] password) throws IOException { return read(archive, password, null, null, null); }

    /** 有界读取预览选定的小型配置条目，数据库和资源仍完整流式验证，不驻留内存。 */
    public SelectedContents readSelected(Path archive, char[] password, Preview expected, Set<Integer> indices) throws IOException {
        long total = 0;
        for (int index : indices) {
            if (index < 0 || index >= expected.items().size()) throw new IOException("transfer.archive-invalid");
            long size = expected.items().get(index).size();
            if (size > 8 * 1024 * 1024 - total) throw new IOException("transfer.archive-limit");
            total += size;
        }
        SelectedContents contents = new SelectedContents(indices);
        try {
            Preview actual = read(archive, password, null, null, contents);
            if (!actual.equals(expected)) throw new IOException("transfer.archive-changed");
            return contents;
        } catch (IOException | RuntimeException failed) {
            contents.close();
            throw failed;
        }
    }

    public static final class SelectedContents implements AutoCloseable {
        private final Set<Integer> indices;
        private final Map<Integer, byte[]> values = new LinkedHashMap<>();
        private long remaining = 8 * 1024 * 1024;
        private SelectedContents(Set<Integer> indices) { this.indices = Set.copyOf(indices); }
        public byte[] get(int index) { return Objects.requireNonNull(values.get(index)); }
        @Override public void close() { values.values().forEach(value -> Arrays.fill(value, (byte) 0)); values.clear(); }
    }

    /** 暂存目录只能由本次操作新建；调用方在核验完成前不得把暂存内容应用到运行目录。 */
    public Preview extract(Path archive, char[] password, Path staging, Preview expected) throws IOException {
        Objects.requireNonNull(expected);
        Path directory = staging.toAbsolutePath().normalize();
        PlainFilePathGuard.requirePlainParent(directory, false);
        Files.createDirectory(directory);
        List<Path> created = new ArrayList<>();
        boolean complete = false;
        try {
            MigrationFiles.makePrivate(directory);
            Preview result = read(archive, password, directory, created, null);
            if (!result.equals(expected)) throw new IOException("transfer.archive-changed");
            complete = true;
            return result;
        } finally {
            if (!complete) {
                for (Path file : created) {
                    PlainFilePathGuard.requirePlainRegularFile(file);
                    Files.delete(file);
                }
                PlainFilePathGuard.requirePlainParent(directory.resolve("unused"), false);
                Files.delete(directory);
            }
        }
    }

    private Preview read(Path archive, char[] password, Path directory, List<Path> created, SelectedContents selected) throws IOException {
        PlainFilePathGuard.requirePlainRegularFile(archive);
        MessageDigest archiveDigest = digest();
        // 压缩容器、认证段及目录表也纳入预算，不能只限制解压后的条目。
        long containerLimit = Math.addExact(limits.maxBytes(), 2L * limits.maxManifestBytes() + 16L * 1024 * 1024);
        try (InputStream file = Files.newInputStream(archive, LinkOption.NOFOLLOW_LINKS);
             InputStream bounded = new BudgetInputStream(file, containerLimit);
             DigestInputStream raw = new DigestInputStream(bounded, archiveDigest)) {
            var opened = ArchiveProtection.decrypt(raw, password);
            try (ZipTailInputStream plaintext = new ZipTailInputStream(opened.stream());
                 ZipInputStream zip = new ZipInputStream(plaintext, StandardCharsets.UTF_8)) {
                List<Measured> measured = new ArrayList<>();
                long total = 0;
                Manifest manifest = null;
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    checkInterrupted();
                    if (entry.isDirectory() || manifest != null) throw new IOException("transfer.archive-invalid");
                    if (MANIFEST.equals(entry.getName())) {
                        byte[] bytes = zip.readNBytes(limits.maxManifestBytes() + 1);
                        if (bytes.length > limits.maxManifestBytes()) throw new IOException("transfer.archive-limit");
                        try { manifest = JSON.readValue(bytes, Manifest.class); }
                        catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException invalid) {
                            throw new IOException("transfer.archive-invalid");
                        }
                    } else {
                        int index = measured.size();
                        if (index >= limits.maxEntries()) throw new IOException("transfer.archive-limit");
                        if (!entry.getName().equals("data/" + index)) throw new IOException("transfer.archive-invalid");
                        MessageDigest digest = digest();
                        long size;
                        if (selected != null && selected.indices.contains(index)) {
                            var output = new ByteArrayOutputStream();
                            size = copy(zip, output, digest, Math.min(limits.maxBytes() - total, selected.remaining));
                            selected.remaining -= size;
                            selected.values.put(index, output.toByteArray());
                        } else if (directory == null) {
                            size = copy(zip, OutputStream.nullOutputStream(), digest, limits.maxBytes() - total);
                        } else {
                            Path target = directory.resolve(Integer.toString(index));
                            PlainFilePathGuard.requirePlainParent(target, false);
                            try (OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW,
                                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                                created.add(target);
                                MigrationFiles.makePrivate(target);
                                size = copy(zip, output, digest, limits.maxBytes() - total);
                            }
                        }
                        total += size;
                        measured.add(new Measured(size, HexFormat.of().formatHex(digest.digest())));
                    }
                    zip.closeEntry();
                }
                // ZIP 到目录表就停止读取，仍须消费加密流尾部以验证最后一段与截断。
                copy(plaintext, OutputStream.nullOutputStream(), null, containerLimit);
                plaintext.verifyEnd(measured.size() + 1);
                if (manifest == null || manifest.encrypted() != opened.encrypted()
                        || manifest.items().size() != measured.size()) throw new IOException("transfer.archive-invalid");
                validateDescriptors(manifest.items().stream().map(Item::descriptor).toList(), opened.encrypted());
                for (int i = 0; i < measured.size(); i++) {
                    Item item = manifest.items().get(i);
                    if (item.size() != measured.get(i).size() || !item.sha256().equals(measured.get(i).sha256()))
                        throw new IOException("transfer.archive-invalid");
                }
                return new Preview(manifest, HexFormat.of().formatHex(archiveDigest.digest()));
            }
        }
    }

    private static void validateDescriptors(List<Descriptor> descriptors, boolean encrypted) throws IOException {
        Set<String> names = new HashSet<>();
        for (Descriptor descriptor : descriptors) {
            if (!encrypted && (descriptor.category() == Category.CREDENTIALS || descriptor.category() == Category.DATABASE || descriptor.category() == Category.STATE))
                throw new IOException("transfer.password-required");
            String identity = (descriptor.owner() + "/" + descriptor.category() + "/" + descriptor.path()).toLowerCase(Locale.ROOT);
            if (!names.add(identity)) throw new IOException("transfer.archive-invalid");
        }
    }

    public static void requireLogicalPath(String path) {
        if (path == null || path.length() > 1024 || !Normalizer.isNormalized(path, Normalizer.Form.NFC))
            throw new IllegalArgumentException("transfer.archive-invalid");
        String[] parts = path.split("/", -1);
        if (parts.length > 16) throw new IllegalArgumentException("transfer.archive-invalid");
        for (String part : parts) {
            if (!part.matches("[\\p{L}\\p{N}_][\\p{L}\\p{N}_. -]*") || part.endsWith(".") || part.endsWith(" ")
                    || part.split("\\.", 2)[0].toUpperCase(Locale.ROOT).matches("CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³]"))
                throw new IllegalArgumentException("transfer.archive-invalid");
        }
    }

    private static long copy(InputStream input, OutputStream output, MessageDigest digest, long maximum) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long count = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            checkInterrupted();
            if (read > maximum - count) throw new IOException("transfer.archive-limit");
            count += read;
            if (digest != null) digest.update(buffer, 0, read);
            output.write(buffer, 0, read);
        }
        return count;
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("transfer.cancelled");
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private record Measured(long size, String sha256) {}

    /** 本格式不允许 ZIP 注释；检查尾记录，避免流式 ZIP 读取器忽略目录表被截断。 */
    private static final class ZipTailInputStream extends FilterInputStream {
        private final byte[] tail = new byte[22];
        private long count;
        ZipTailInputStream(InputStream input) { super(input); }
        @Override public int read() throws IOException {
            int value = in.read();
            if (value >= 0) tail[(int) (count++ % tail.length)] = (byte) value;
            return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = in.read(bytes, offset, length);
            if (read > 0) {
                int retained = Math.min(read, tail.length);
                count += read - retained;
                for (int i = read - retained; i < read; i++) tail[(int) (count++ % tail.length)] = bytes[offset + i];
            }
            return read;
        }
        void verifyEnd(int entries) throws IOException {
            byte[] end = new byte[tail.length];
            for (int i = 0; i < end.length; i++) end[i] = tail[(int) ((count + i) % tail.length)];
            var buffer = java.nio.ByteBuffer.wrap(end).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            if (count < end.length || buffer.getInt() != 0x06054b50 || buffer.getInt() != 0
                    || Short.toUnsignedInt(buffer.getShort()) != entries
                    || Short.toUnsignedInt(buffer.getShort()) != entries || end[20] != 0 || end[21] != 0)
                throw new IOException("transfer.archive-invalid");
        }
    }

    private static final class BudgetInputStream extends FilterInputStream {
        private long remaining;
        BudgetInputStream(InputStream input, long maximum) { super(input); remaining = maximum; }
        @Override public int read() throws IOException {
            int value = in.read();
            if (value != -1 && --remaining < 0) throw new IOException("transfer.archive-limit");
            return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = in.read(bytes, offset, (int) Math.min(length, remaining + 1));
            if (read > 0 && (remaining -= read) < 0) throw new IOException("transfer.archive-limit");
            return read;
        }
    }
}
