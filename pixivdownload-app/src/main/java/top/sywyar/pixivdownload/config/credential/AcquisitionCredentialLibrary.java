package top.sywyar.pixivdownload.config.credential;

import org.springframework.stereotype.Service;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import top.sywyar.pixivdownload.plugin.api.download.context.AcquisitionCredentials;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** 核心采集凭据库；持久化复用安装密钥和 core owner 的加密存储。 */
@Service
public final class AcquisitionCredentialLibrary {
    public static final String KEY = "core.acquisition-library";
    public static final int MAX_BYTES = 256 * 1024;
    private static final JsonMapper JSON = JsonMapper.builderWithJackson2Defaults()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final PluginCredentialStore store;

    public AcquisitionCredentialLibrary(PluginCredentialStore store) { this.store = store; }
    public record Entry(String id, String name, boolean enabled, String secret) {
        @Override public String toString() { return "Entry[redacted]"; }
    }
    public record View(String id, String name, boolean enabled) {}
    public record Snapshot(String revision, List<View> entries) {}
    public record Update(String revision, List<Entry> entries) {
        @Override public String toString() { return "Update[redacted]"; }
    }
    public record Document(int schemaVersion, List<Entry> entries) {
        @Override public String toString() { return "Document[redacted]"; }
    }

    public Snapshot read() throws IOException {
        return snapshot(load());
    }

    public Snapshot save(Update update) throws IOException {
        if (update == null || update.entries() == null || update.entries().size() > AcquisitionCredentials.MAX_CANDIDATES)
            throw new IllegalArgumentException("Invalid credential library");
        var result = new java.util.concurrent.atomic.AtomicReference<Snapshot>();
        store.withOwnerLocks(List.of("core"), () -> {
            Document old = load();
            if (!snapshot(old).revision().equals(update.revision())) throw new Conflict();
            List<Entry> entries = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (Entry input : update.entries()) {
                if (input == null || input.id() == null || !input.id().matches("[a-zA-Z0-9-]{1,64}")
                        || !ids.add(input.id()) || input.name() == null || input.name().isBlank() || input.name().length() > 80)
                    throw new IllegalArgumentException("Invalid credential library");
                String secret = input.secret();
                if (secret == null || secret.isBlank()) secret = old.entries().stream()
                        .filter(entry -> entry.id().equals(input.id())).findFirst().map(Entry::secret).orElse(null);
                new AcquisitionCredentials(Collections.singletonList(secret));
                entries.add(new Entry(input.id(), input.name().strip(), input.enabled(), secret.strip()));
            }
            Document next = new Document(1, List.copyOf(entries));
            String encoded = JSON.writeValueAsString(next);
            if (encoded.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
                throw new IllegalArgumentException("Credential library exceeds limit");
            store.update("core", Map.of(KEY, encoded));
            Document verified = load();
            if (!verified.equals(next)) throw new IOException("Credential library verification failed");
            result.set(snapshot(verified));
        });
        return result.get();
    }

    /** 只在已鉴权的请求中解析；顺序属于调用方，不能退回库中的其它条目。 */
    public AcquisitionCredentials resolve(List<String> ids) throws IOException {
        if (ids == null || ids.isEmpty() || ids.size() > AcquisitionCredentials.MAX_CANDIDATES
                || new HashSet<>(ids).size() != ids.size()
                || ids.stream().anyMatch(id -> id == null || !id.matches("[a-zA-Z0-9-]{1,64}"))) throw new IllegalArgumentException("Invalid credential selection");
        Document document = load();
        List<String> values = new ArrayList<>();
        for (String id : ids) values.add(document.entries().stream()
                .filter(entry -> entry.enabled() && entry.id().equals(id)).findFirst()
                .orElseThrow(() -> new top.sywyar.pixivdownload.plugin.api.download.context.AcquisitionCredentialsUnavailableException(0)).secret());
        return new AcquisitionCredentials(values);
    }

    private Document load() throws IOException {
        String raw = store.readAll("core").get(KEY);
        if (raw == null) return new Document(1, List.of());
        try {
            if (raw.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new IllegalArgumentException();
            Document document = JSON.readValue(raw, Document.class);
            if (document.schemaVersion() != 1 || document.entries() == null
                    || document.entries().size() > AcquisitionCredentials.MAX_CANDIDATES) throw new IllegalArgumentException();
            Set<String> ids = new HashSet<>();
            for (Entry entry : document.entries()) {
                if (entry == null || entry.id() == null || !entry.id().matches("[a-zA-Z0-9-]{1,64}")
                        || !ids.add(entry.id()) || entry.name() == null || entry.name().isBlank() || entry.name().length() > 80)
                    throw new IllegalArgumentException();
                new AcquisitionCredentials(Collections.singletonList(entry.secret()));
            }
            return document;
        } catch (RuntimeException invalid) { throw new IOException("Credential library is invalid"); }
    }

    private Snapshot snapshot(Document document) {
        try {
            String revision = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(JSON.writeValueAsBytes(document)));
            return new Snapshot(revision, document.entries().stream()
                    .map(entry -> new View(entry.id(), entry.name(), entry.enabled())).toList());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public static Update parse(byte[] input) {
        if (input.length > MAX_BYTES) throw new IllegalArgumentException("Credential library exceeds limit");
        return JSON.readValue(input, Update.class);
    }
    public static final class Conflict extends RuntimeException {}
}
