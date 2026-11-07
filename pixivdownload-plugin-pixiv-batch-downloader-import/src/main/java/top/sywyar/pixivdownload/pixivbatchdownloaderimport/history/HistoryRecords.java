package top.sywyar.pixivdownload.pixivbatchdownloaderimport.history;

import tools.jackson.core.JsonToken;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Pattern;

/** 记录按作品散列到临时文件，再逐桶合并，不把整份导出留在堆中。 */
final class HistoryRecords implements AutoCloseable {
    // 上游桌面分片为 300 MiB；512 MiB 为格式和版本变化留出余量。
    static final long MAX_BYTES = 512L * 1024 * 1024;
    static final long MAX_TOTAL_BYTES = 1024L * 1024 * 1024;
    static final int MAX_FILES = 64;
    static final int BUCKETS = 256;
    static final int MAX_BUCKET_WORKS = 150_000;
    static final long MAX_BUCKET_WEIGHT = 64L * 1024 * 1024;
    private static final Pattern ID = Pattern.compile("([1-9][0-9]{0,15})(?:_p([0-9]{1,3}))?");
    private static final JsonFactory JSON = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(2).maxStringLength(4096).maxNameLength(32).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private final Path directory;
    private final Path root;
    private int records;
    private long bytesRead;
    private final long salt = new java.security.SecureRandom().nextLong();

    record Key(WorkType type, long id) { String display() { return type.name().toLowerCase(Locale.ROOT) + ":" + id; } }
    static final class Work {
        final Key key;
        final NavigableMap<Integer, Path> files = new TreeMap<>();
        final boolean bare;
        boolean conflict;
        boolean missing;
        Work(Key key, boolean bare) { this.key = key; this.bare = bare; }
    }
    @FunctionalInterface interface Visitor { void visit(Work work) throws IOException; }
    private record Row(Key key, boolean bare, int page, String name) {}
    private HistoryRecords(Path directory, Path root) { this.directory = directory; this.root = root; }

    static HistoryRecords read(Path file, Path root, Path cache) throws IOException {
        plain(root, true);
        List<Path> inputs = new ArrayList<>();
        if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
            plain(file, true);
            try (var children = Files.newDirectoryStream(file, "*.json")) {
                for (Path child : children) {
                    if (inputs.size() == MAX_FILES) throw new Invalid("limit");
                    inputs.add(child);
                }
            }
            inputs.sort(Comparator.naturalOrder());
            if (inputs.isEmpty()) throw new Invalid("format");
        } else inputs.add(file);
        long total = 0;
        for (Path input : inputs) {
            plain(input, false);
            long size = Files.size(input);
            total += size;
            if (size > MAX_BYTES || total > MAX_TOTAL_BYTES) throw new Invalid("limit");
        }
        Files.createDirectories(cache);
        plain(cache, true);
        HistoryRecords result = new HistoryRecords(Files.createTempDirectory(cache, "history-"), root);
        try { for (Path input : inputs) result.partition(input); return result; }
        catch (IOException | RuntimeException | Error failure) {
            try { result.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
    int records() { return records; }
    Path candidates() { return directory.resolve("candidates"); }

    private void partition(Path file) throws IOException {
        Map<Integer, DataOutputStream> outputs = new HashMap<>();
        try (var input = new BoundedInput(Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS));
             var parser = JSON.createParser(input)) {
            if (parser.nextToken() != JsonToken.START_ARRAY) throw new Invalid("format");
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                interrupted();
                if (parser.currentToken() != JsonToken.START_OBJECT) throw new Invalid("format");
                String id = null, name = null;
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    if (parser.currentToken() != JsonToken.PROPERTY_NAME) throw new Invalid("format");
                    String field = parser.currentName();
                    if (!field.equals("id") && !field.equals("n") && !field.equals("d")) throw new Invalid("format");
                    if (parser.nextToken() != JsonToken.VALUE_STRING) throw new Invalid("format");
                    String value = parser.getString();
                    if (value.length() > 4096) throw new Invalid("limit");
                    if (field.equals("id")) id = value;
                    if (field.equals("n")) name = value;
                }
                if (id == null || name == null) throw new Invalid("format");
                var match = ID.matcher(id);
                if (!match.matches()) throw new Invalid("format");
                boolean bare = match.group(2) == null;
                String lower = name.toLowerCase(Locale.ROOT);
                WorkType type = bare && (lower.endsWith(".txt") || lower.endsWith(".epub")) ? WorkType.NOVEL : WorkType.ARTWORK;
                Key key = new Key(type, Long.parseLong(match.group(1)));
                long hash = key.id() ^ salt ^ type.ordinal();
                hash = (hash ^ (hash >>> 30)) * 0xbf58476d1ce4e5b9L;
                hash = (hash ^ (hash >>> 27)) * 0x94d049bb133111ebL;
                int bucket = (int) (hash ^ (hash >>> 31)) & (BUCKETS - 1);
                DataOutputStream output = outputs.get(bucket);
                if (output == null) {
                    output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(
                            directory.resolve(Integer.toString(bucket)), StandardOpenOption.CREATE, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS)));
                    outputs.put(bucket, output);
                }
                writeRow(output, new Row(key, bare, bare ? 0 : Integer.parseInt(match.group(2)), name));
                records++;
            }
            if (parser.nextToken() != null) throw new Invalid("format");
        } catch (JacksonException failure) {
            if (failure.getCause() instanceof IOException inputFailure) throw inputFailure;
            throw new IOException("Invalid history JSON", failure);
        } finally {
            IOException failure = null;
            for (var output : outputs.values()) try { output.close(); } catch (IOException exception) { failure = exception; }
            if (failure != null) throw failure;
        }
    }
    void forEach(Visitor visitor) throws IOException {
        for (int i = 0; i < BUCKETS; i++) {
            interrupted();
            Path bucket = directory.resolve(Integer.toString(i));
            if (!Files.exists(bucket, LinkOption.NOFOLLOW_LINKS)) continue;
            Map<Key, Work> works = new LinkedHashMap<>();
            long weight = 0;
            try (var input = input(bucket)) {
                Row row;
                while ((row = readRow(input)) != null) {
                    interrupted();
                    Work work = works.get(row.key());
                    if (work == null) {
                        if (works.size() == MAX_BUCKET_WORKS) throw new Invalid("limit");
                        work = new Work(row.key(), row.bare());
                        works.put(row.key(), work);
                        weight += 128;
                    }
                    if (row.bare() != work.bare) work.conflict = true;
                    if (row.name().isBlank()) { work.missing = true; continue; }
                    try {
                        if (row.name().chars().anyMatch(Character::isISOControl)) throw new InvalidPathException(row.name(), "control");
                        Path path = root.resolve(row.name().replace('\\', '/')).normalize();
                        if (!path.startsWith(root) || path.equals(root)) { work.conflict = true; continue; }
                        Path relative = root.relativize(path);
                        Path old = work.files.putIfAbsent(row.page(), relative);
                        if (old != null && !old.equals(relative)) work.conflict = true;
                        if (old == null) weight += 128L + 2L * relative.toString().length();
                        if (weight > MAX_BUCKET_WEIGHT) throw new Invalid("limit");
                    } catch (InvalidPathException invalid) { work.conflict = true; }
                }
            }
            for (Work work : works.values()) {
                interrupted();
                if (work.files.isEmpty() || work.files.lastKey() + 1 != work.files.size()) work.missing = true;
                if (new HashSet<>(work.files.values()).size() != work.files.size()) work.conflict = true;
                for (Path path : work.files.values()) {
                    try { plain(root.resolve(path), false); if (Files.size(root.resolve(path)) == 0) work.missing = true; }
                    catch (IOException unavailable) { work.missing = true; }
                }
                visitor.visit(work);
            }
            Files.delete(bucket);
        }
    }
    DataOutputStream candidateOutput() throws IOException {
        return new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(candidates(),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)));
    }
    void writeCandidate(DataOutputStream output, Work work) throws IOException {
        output.writeInt(work.files.size());
        for (var entry : work.files.entrySet())
            writeRow(output, new Row(work.key, work.bare, entry.getKey(), entry.getValue().toString()));
    }
    Work readCandidate(DataInputStream input) throws IOException {
        int first = input.read();
        if (first < 0) return null;
        int count = (first << 24) | (input.readUnsignedByte() << 16) | (input.readUnsignedByte() << 8) | input.readUnsignedByte();
        if (count < 1 || count > 1000) throw new Invalid("format");
        Work work = null;
        for (int i = 0; i < count; i++) {
            Row row = readRow(input);
            if (row == null) throw new EOFException();
            if (work == null) work = new Work(row.key(), row.bare());
            if (!row.key().equals(work.key) || row.page() != i) throw new Invalid("format");
            Path path = root.resolve(row.name()).normalize();
            if (!path.startsWith(root) || path.equals(root)) throw new Invalid("format");
            work.files.put(i, root.relativize(path));
        }
        return work;
    }
    static DataInputStream input(Path file) throws IOException {
        plain(file, false);
        return new DataInputStream(new BufferedInputStream(Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)));
    }
    private static void writeRow(DataOutputStream out, Row row) throws IOException {
        out.writeByte(row.key().type() == WorkType.NOVEL ? 1 : 0);
        out.writeLong(row.key().id());
        out.writeBoolean(row.bare());
        out.writeShort(row.page());
        out.writeUTF(row.name());
    }
    private static Row readRow(DataInputStream input) throws IOException {
        int type = input.read();
        if (type < 0) return null;
        if (type > 1) throw new Invalid("format");
        return new Row(new Key(type == 1 ? WorkType.NOVEL : WorkType.ARTWORK, input.readLong()),
                input.readBoolean(), input.readUnsignedShort(), input.readUTF());
    }
    @Override public void close() throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        plain(directory, true);
        try (var children = Files.newDirectoryStream(directory)) {
            for (Path child : children) {
                // 仅回收本次随机目录中的普通暂存文件，不递归进入目录或链接。
                plain(child, false);
                Files.delete(child);
            }
        }
        Files.delete(directory);
    }
    static void plain(Path path, boolean directory) throws IOException {
        if (!path.isAbsolute()) throw new IOException("absolute path required");
        Path current = path.getRoot();
        for (Path segment : path) {
            current = current.resolve(segment);
            BasicFileAttributes attrs = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            boolean expected = current.equals(path) && !directory ? attrs.isRegularFile() : attrs.isDirectory();
            if (!expected || attrs.isSymbolicLink() || attrs.isOther()) throw new IOException("plain path required");
        }
    }
    static void interrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("cancelled");
    }
    static final class Invalid extends IOException {
        final String code;
        Invalid(String code) { super(code); this.code = code; }
    }
    private final class BoundedInput extends FilterInputStream {
        private long read;
        BoundedInput(InputStream input) { super(input); }
        private void count(int n) throws IOException {
            if (n > 0) {
                read += n; bytesRead += n;
                if (read > MAX_BYTES || bytesRead > MAX_TOTAL_BYTES) throw new Invalid("limit");
            }
        }
        @Override public int read() throws IOException { int value = in.read(); count(value < 0 ? 0 : 1); return value; }
        @Override public int read(byte[] data, int offset, int length) throws IOException {
            int n = in.read(data, offset, (int) Math.min(length, MAX_BYTES - read + 1)); count(n); return n;
        }
    }
}
