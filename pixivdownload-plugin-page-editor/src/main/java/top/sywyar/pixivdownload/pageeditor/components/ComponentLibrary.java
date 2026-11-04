package top.sywyar.pixivdownload.pageeditor.components;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** 组件源码属于编辑器 owner；不可变版本先落盘，再原子发布可见引用。 */
public final class ComponentLibrary {
    public static final String TYPE = "page-editor:custom";
    public static final int MAX_SOURCE_BYTES = 1024 * 1024;
    private static final int MAX_COMPONENTS = 128;
    private static final int MAX_VERSIONS = 4096;
    public static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final Path directory;
    private final Map<String, ObjectNode> summaries = new LinkedHashMap<>();
    private boolean loaded;

    public ComponentLibrary(Path directory) { this.directory = directory.toAbsolutePath().normalize(); }

    public static IllegalArgumentException invalid(String code) { return new IllegalArgumentException(code); }

    public static JsonNode parse(byte[] bytes) {
        if (bytes.length > MAX_SOURCE_BYTES) throw invalid("PAGE_COMPONENT_BUDGET");
        try {
            JsonNode result = JSON.readTree(bytes);
            if (result == null) throw invalid("PAGE_COMPONENT_SOURCE_INVALID");
            return result;
        } catch (IOException error) { throw invalid("PAGE_COMPONENT_SOURCE_INVALID"); }
    }

    public static ObjectNode source(JsonNode value) {
        fields(value, Set.of("name", "html", "css", "javascript", "properties"));
        for (String key : List.of("name", "html", "css", "javascript"))
            if (!value.path(key).isTextual()) throw invalid("PAGE_COMPONENT_SOURCE_INVALID");
        if (value.path("name").asText().isBlank() || value.path("name").asText().length() > 128)
            throw invalid("PAGE_COMPONENT_SOURCE_INVALID");
        if (value.toString().getBytes(StandardCharsets.UTF_8).length > MAX_SOURCE_BYTES)
            throw invalid("PAGE_COMPONENT_BUDGET");
        JsonNode properties = value.path("properties");
        if (!properties.isObject() || properties.size() > 64) throw invalid("PAGE_COMPONENT_PROPERTIES_INVALID");
        properties.fields().forEachRemaining(entry -> {
            if (!entry.getKey().matches("[A-Za-z][A-Za-z0-9_-]{0,63}")
                    || Set.of("__proto__", "constructor", "prototype").contains(entry.getKey()))
                throw invalid("PAGE_COMPONENT_PROPERTIES_INVALID");
            JsonNode property = entry.getValue();
            fields(property, Set.of("type", "label", "default"));
            if (!property.path("label").isTextual() || property.path("label").asText().length() > 128
                    || !matches(property.path("type").asText(), property.path("default")))
                throw invalid("PAGE_COMPONENT_PROPERTIES_INVALID");
        });
        return ((ObjectNode) value).deepCopy();
    }

    private static boolean matches(String type, JsonNode value) {
        return switch (type) {
            case "string" -> value.isTextual() && value.asText().length() <= 16384;
            case "boolean" -> value.isBoolean();
            case "number" -> value.isNumber() && Double.isFinite(value.asDouble());
            default -> false;
        };
    }

    private static void fields(JsonNode value, Set<String> allowed) {
        if (!value.isObject()) throw invalid("PAGE_COMPONENT_SOURCE_INVALID");
        value.fieldNames().forEachRemaining(key -> {
            if (!allowed.contains(key)) throw invalid("PAGE_COMPONENT_SOURCE_INVALID");
        });
    }

    public synchronized List<ObjectNode> catalog() {
        load();
        return summaries.values().stream().map(ObjectNode::deepCopy).toList();
    }

    private void load() {
        if (loaded) return;
        try {
            if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                requireDirectory(directory);
                try (var files = Files.newDirectoryStream(directory, "*.json")) {
                    for (Path file : files) {
                        if (summaries.size() >= MAX_COMPONENTS) throw invalid("PAGE_COMPONENT_BUDGET");
                        String name = file.getFileName().toString();
                        String id = name.substring(0, name.length() - 5);
                        file(id);
                        try { summaries.put(id, summary(read(id))); }
                        catch (RuntimeException error) {
                            summaries.put(id, JSON.createObjectNode().put("id", id).put("name", id)
                                    .put("enabled", false).put("latest", "").put("unavailable", true));
                        }
                    }
                }
            }
            loaded = true;
        } catch (IOException error) { summaries.clear(); throw storage(error); }
        catch (RuntimeException error) { summaries.clear(); throw error; }
    }

    public synchronized ObjectNode read(String id) {
        Path file = file(id);
        ObjectNode state = readFile(file, 2 * MAX_SOURCE_BYTES);
        if (!state.path("id").asText().equals(id) || !state.path("revision").isIntegralNumber()
                || !state.path("revision").canConvertToLong() || state.path("revision").asLong() < 1
                || !state.path("enabled").isBoolean() || !state.path("versions").isArray()
                || state.path("versions").size() > MAX_VERSIONS || !state.path("latest").isTextual())
            throw storage(null);
        source(state.path("draft"));
        for (JsonNode version : state.path("versions")) requireVersion(version.asText());
        if (!state.path("latest").asText().isEmpty() && !containsVersion(state, state.path("latest").asText())) throw storage(null);
        return state;
    }

    public synchronized ObjectNode save(String id, long revision, JsonNode draft) {
        ObjectNode valid = source(draft);
        load();
        ObjectNode state;
        if (id.isEmpty()) {
            if (revision != 0) throw invalid("PAGE_REVISION_CONFLICT");
            if (summaries.size() >= MAX_COMPONENTS) throw invalid("PAGE_COMPONENT_BUDGET");
            id = UUID.randomUUID().toString();
            state = JSON.createObjectNode().put("id", id).put("revision", 0).put("enabled", true).put("latest", "");
            state.putArray("versions");
        } else state = expected(id, revision);
        state.set("draft", valid);
        return writeState(state);
    }

    public synchronized ObjectNode publish(String id, long revision) {
        ObjectNode state = expected(id, revision);
        ObjectNode source = source(state.path("draft"));
        byte[] bytes = source.toString().getBytes(StandardCharsets.UTF_8);
        String version = digest(bytes);
        if (!containsVersion(state, version)) {
            if (state.path("versions").size() >= MAX_VERSIONS) throw invalid("PAGE_COMPONENT_BUDGET");
            write(versionFile(id, version), bytes);
            ((ArrayNode) state.path("versions")).add(version);
        }
        state.put("latest", version).put("enabled", true);
        return writeState(state);
    }

    public synchronized ObjectNode availability(String id, long revision, boolean enabled) {
        ObjectNode state = expected(id, revision);
        state.put("enabled", enabled);
        return writeState(state);
    }

    public synchronized ObjectNode published(String id, String version) {
        ObjectNode state = read(id);
        if (!state.path("enabled").asBoolean()) throw invalid("PAGE_COMPONENT_UNAVAILABLE");
        return version(state, version);
    }

    public synchronized ObjectNode version(String id, String version) { return version(read(id), version); }

    public synchronized boolean enabled(String id) {
        file(id); load();
        return summaries.containsKey(id) && summaries.get(id).path("enabled").asBoolean();
    }

    private ObjectNode version(ObjectNode state, String version) {
        requireVersion(version);
        if (!containsVersion(state, version)) throw invalid("PAGE_COMPONENT_UNAVAILABLE");
        Path file = versionFile(state.path("id").asText(), version);
        byte[] bytes = bytes(file, MAX_SOURCE_BYTES);
        if (!digest(bytes).equals(version)) throw storage(null);
        return source(parse(bytes));
    }

    public synchronized void validateConfiguration(JsonNode config, boolean publishing) {
        fields(config, Set.of("componentId", "version", "properties"));
        ObjectNode definition = publishing ? published(config.path("componentId").asText(), config.path("version").asText())
                : version(config.path("componentId").asText(), config.path("version").asText());
        JsonNode values = config.path("properties"), properties = definition.path("properties");
        if (!values.isObject()) throw invalid("PAGE_COMPONENT_PROPERTIES_INVALID");
        values.fields().forEachRemaining(entry -> {
            if (!properties.has(entry.getKey()) || !entry.getValue().isTextual()
                    || !encodedValue(properties.path(entry.getKey()).path("type").asText(), entry.getValue().asText()))
                throw invalid("PAGE_COMPONENT_PROPERTIES_INVALID");
        });
    }

    private static boolean encodedValue(String type, String text) {
        if (type.equals("string")) return text.length() <= 16384;
        try { return matches(type, parse(text.getBytes(StandardCharsets.UTF_8))); }
        catch (IllegalArgumentException error) { return false; }
    }

    private ObjectNode expected(String id, long revision) {
        load();
        ObjectNode state = read(id);
        if (state.path("revision").asLong() != revision) throw invalid("PAGE_REVISION_CONFLICT");
        return state;
    }

    private ObjectNode writeState(ObjectNode state) {
        state.put("revision", Math.addExact(state.path("revision").asLong(), 1));
        byte[] bytes = state.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 2 * MAX_SOURCE_BYTES) throw invalid("PAGE_COMPONENT_BUDGET");
        ObjectNode summary = summary(state);
        write(file(state.path("id").asText()), bytes);
        summaries.put(state.path("id").asText(), summary);
        return state;
    }

    private ObjectNode summary(ObjectNode state) {
        ObjectNode summary = JSON.createObjectNode().put("id", state.path("id").asText())
                .put("name", state.path("draft").path("name").asText()).put("revision", state.path("revision").asLong())
                .put("latest", state.path("latest").asText()).put("enabled", state.path("enabled").asBoolean());
        if (!state.path("latest").asText().isEmpty()) {
            ObjectNode published = version(state, state.path("latest").asText());
            summary.put("name", published.path("name").asText());
        }
        return summary;
    }

    private boolean containsVersion(ObjectNode state, String version) {
        for (JsonNode item : state.path("versions")) if (item.asText().equals(version)) return true;
        return false;
    }

    private Path file(String id) {
        if (id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw invalid("PAGE_COMPONENT_ID_INVALID");
        return directory.resolve(id + ".json");
    }

    private Path versionFile(String id, String version) {
        file(id); requireVersion(version);
        return directory.resolve("versions").resolve(id).resolve(version + ".json");
    }

    private static void requireVersion(String version) {
        if (version == null || !version.matches("[0-9a-f]{64}")) throw invalid("PAGE_COMPONENT_UNAVAILABLE");
    }

    private ObjectNode readFile(Path file, int limit) {
        try {
            JsonNode state = JSON.readTree(bytes(file, limit));
            if (state == null || !state.isObject()) throw storage(null);
            return (ObjectNode) state;
        } catch (IOException error) { throw storage(error); }
    }

    private byte[] bytes(Path file, int limit) {
        try {
            requireDirectory(file.getParent());
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw storage(null);
            try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                byte[] bytes = input.readNBytes(limit + 1);
                if (bytes.length > limit) throw storage(null);
                return bytes;
            }
        } catch (IOException error) { throw storage(error); }
    }

    private void requireDirectory(Path path) throws IOException {
        if (!path.startsWith(directory)) throw new IOException("outside component directory");
        Path current = path;
        while (current != null && current.startsWith(directory)) {
            if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(current))
                throw new IOException("invalid component directory");
            current = current.getParent();
        }
    }

    private void write(Path file, byte[] bytes) {
        Path temporary = null;
        try {
            createDirectory(file.getParent()); requireDirectory(file.getParent());
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw storage(null);
            temporary = Files.createTempFile(file.getParent(), ".component-", ".tmp");
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException error) { throw storage(error); }
        finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException error) { throw storage(error); }
        }
    }

    private void createDirectory(Path path) throws IOException {
        if (!path.startsWith(directory)) throw new IOException("outside component directory");
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (!path.equals(directory)) createDirectory(path.getParent());
            else Files.createDirectories(directory.getParent());
            Files.createDirectory(path);
        }
        requireDirectory(path);
    }

    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    private static IllegalStateException storage(Throwable cause) { return new IllegalStateException("PAGE_COMPONENT_STORAGE_FAILED", cause); }
}
