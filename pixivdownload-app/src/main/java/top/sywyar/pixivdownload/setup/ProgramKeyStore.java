package top.sywyar.pixivdownload.setup;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import top.sywyar.pixivdownload.plugin.api.web.HttpApiNullable;
import top.sywyar.pixivdownload.plugin.api.database.migration.MigrationFiles;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;

/** 只持久化随机密钥摘要；授权变更在原子写入成功后才对请求可见。 */
final class ProgramKeyStore {
    static final int MAX_KEYS = 128, MAX_PERMISSIONS = 512, MAX_NETWORKS = 64;
    static final int MAX_NAME_LENGTH = 80;
    static final int MAX_FILE_BYTES = 16 * 1024 * 1024;
    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(tools.jackson.databind.DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(tools.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private final Path path;
    private final Map<UUID, Long> lastUsed = new HashMap<>();
    private Map<UUID, Stored> keys;

    private static final java.util.regex.Pattern PERMISSION_ID =
            java.util.regex.Pattern.compile("[a-z][a-z0-9.-]{0,95}:[a-z][a-z0-9.-]{0,95}");

    static boolean isPermissionId(String id) {
        return id != null && PERMISSION_ID.matcher(id).matches();
    }

    @Schema(requiredProperties = {"name", "permissions", "networks"})
    record Policy(@Schema(minLength = 1, maxLength = MAX_NAME_LENGTH) String name,
                  @ArraySchema(maxItems = MAX_PERMISSIONS) Set<String> permissions, boolean anyIp,
                  @ArraySchema(maxItems = MAX_NETWORKS) List<String> networks) {
        Policy {
            if (name == null || name.isBlank() || name.length() > MAX_NAME_LENGTH || name.chars().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("PROGRAM_KEY_NAME");
            name = name.strip();
            if (permissions == null || permissions.size() > MAX_PERMISSIONS)
                throw new IllegalArgumentException("PROGRAM_KEY_PERMISSIONS");
            for (String permission : permissions)
                if (!isPermissionId(permission))
                    throw new IllegalArgumentException("PROGRAM_KEY_PERMISSIONS");
            permissions = Set.copyOf(permissions);
            if (networks == null || networks.size() > MAX_NETWORKS || (!anyIp && networks.isEmpty())
                    || (anyIp && !networks.isEmpty())) throw new IllegalArgumentException("PROGRAM_KEY_NETWORKS");
            var validated = new LinkedHashSet<String>();
            for (String value : networks) {
                if (value == null || value.length() > 64) throw new IllegalArgumentException("PROGRAM_KEY_NETWORKS");
                String network = value.strip();
                if (!network.contains("/")) {
                    byte[] address = IpNetwork.parseNumericAddress(network);
                    if (address == null) throw new IllegalArgumentException("PROGRAM_KEY_NETWORKS");
                    network += "/" + address.length * 8;
                }
                try { IpNetwork.parse(network); }
                catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("PROGRAM_KEY_NETWORKS"); }
                validated.add(network);
            }
            networks = List.copyOf(validated);
        }
    }
    enum Transport { HTTPS_REQUIRED, REVIEWED, HTTP_ALLOWED }
    record View(UUID id, long revision, Policy policy, long createdAt, @HttpApiNullable Long lastUsedAt, Transport transport) {}
    record Created(View key, String secret) {
        @Override public String toString() { return "Created[redacted]"; }
    }
    record Stored(UUID id, long revision, Policy policy, long createdAt, String digest, Transport transport) {
        Stored {
            if (transport == null) transport = Transport.HTTPS_REQUIRED; // Existing keys require HTTPS.
            Objects.requireNonNull(id); Objects.requireNonNull(policy);
            if (revision < 1 || createdAt < 0 || digest == null || !digest.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid program key record");
        }
        @Override public String toString() { return "Stored[redacted]"; }
    }
    record Document(int version, List<Stored> keys) {}

    ProgramKeyStore(Path path) { this.path = path.toAbsolutePath().normalize(); }

    synchronized List<View> list() throws IOException {
        load();
        return keys.values().stream().map(this::view).toList();
    }

    synchronized View get(UUID id) throws IOException {
        load();
        var key = keys.get(id);
        if (key == null) throw new NoSuchElementException("PROGRAM_KEY_NOT_FOUND");
        return view(key);
    }

    synchronized Created create(Policy policy) throws IOException {
        load();
        if (keys.size() >= MAX_KEYS) throw new IllegalStateException("PROGRAM_KEY_CAPACITY");
        byte[] random = new byte[32]; new SecureRandom().nextBytes(random);
        UUID id = UUID.randomUUID();
        String secret = "pdk_" + id + "_" + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        Arrays.fill(random, (byte) 0);
        var stored = new Stored(id, 1, policy, System.currentTimeMillis(), digest(secret), Transport.HTTPS_REQUIRED);
        var next = new LinkedHashMap<>(keys); next.put(id, stored);
        save(next);
        return new Created(view(stored), secret);
    }

    synchronized View update(UUID id, long revision, Policy policy) throws IOException {
        Stored old = current(id, revision);
        var next = new LinkedHashMap<>(keys);
        var changed = new Stored(id, Math.incrementExact(old.revision()), policy, old.createdAt(), old.digest(), Transport.HTTPS_REQUIRED);
        next.put(id, changed); save(next);
        return view(changed);
    }

    synchronized View transport(UUID id, long revision, Transport requested) throws IOException {
        Stored old = current(id, revision);
        boolean valid = switch (requested) {
            case REVIEWED -> old.transport() == Transport.HTTPS_REQUIRED;
            case HTTP_ALLOWED -> old.transport() == Transport.REVIEWED;
            case HTTPS_REQUIRED -> true;
        };
        if (!valid) throw new IllegalStateException("PROGRAM_KEY_CHANGED");
        var next = new LinkedHashMap<>(keys);
        var changed = new Stored(id, Math.incrementExact(old.revision()), old.policy(), old.createdAt(), old.digest(), requested);
        next.put(id, changed);
        save(next);
        return view(changed);
    }

    synchronized void revoke(UUID id, long revision) throws IOException {
        current(id, revision);
        var next = new LinkedHashMap<>(keys); next.remove(id); save(next); lastUsed.remove(id);
    }

    synchronized View authenticate(String secret, String remoteAddress, boolean secure) throws IOException {
        load();
        if (secret == null || !secret.matches("pdk_[0-9a-f-]{36}_[A-Za-z0-9_-]{43}")) return null;
        UUID id;
        try { id = UUID.fromString(secret.substring(4, 40)); }
        catch (IllegalArgumentException invalid) { return null; }
        Stored key = keys.get(id);
        if (key == null || !MessageDigest.isEqual(key.digest().getBytes(StandardCharsets.US_ASCII),
                digest(secret).getBytes(StandardCharsets.US_ASCII))) return null;
        byte[] address = IpNetwork.parseNumericAddress(remoteAddress);
        if (address == null || (!key.policy().anyIp() && key.policy().networks().stream()
                .map(IpNetwork::parse).noneMatch(network -> network.contains(address)))) return null;
        if (!secure && key.transport() != Transport.HTTP_ALLOWED && !IpNetwork.isLocalOrLan(address))
            throw new HttpsRequired();
        lastUsed.put(id, System.currentTimeMillis());
        return view(key);
    }

    static final class HttpsRequired extends RuntimeException {}

    private Stored current(UUID id, long revision) throws IOException {
        load();
        var value = keys.get(id);
        if (value == null) throw new NoSuchElementException("PROGRAM_KEY_NOT_FOUND");
        if (value.revision() != revision) throw new IllegalStateException("PROGRAM_KEY_CHANGED");
        return value;
    }

    private View view(Stored key) { return new View(key.id(), key.revision(), key.policy(), key.createdAt(), lastUsed.get(key.id()), key.transport()); }

    private void load() throws IOException {
        if (keys != null) return;
        MigrationFiles.requirePlain(path, false);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) { keys = Map.of(); return; }
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES) throw new IOException("PROGRAM_KEY_STORE_LIMIT");
            var document = JSON.readValue(bytes, Document.class);
            if (document == null || document.version() != 1 || document.keys() == null || document.keys().size() > MAX_KEYS)
                throw new IOException("PROGRAM_KEY_STORE_INVALID");
            var loaded = new LinkedHashMap<UUID, Stored>();
            for (Stored key : document.keys())
                if (key == null || loaded.putIfAbsent(key.id(), key) != null) throw new IOException("PROGRAM_KEY_STORE_INVALID");
            keys = Collections.unmodifiableMap(loaded);
        } catch (RuntimeException invalid) {
            throw new IOException("PROGRAM_KEY_STORE_INVALID");
        }
    }

    private void save(Map<UUID, Stored> next) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(new Document(1, List.copyOf(next.values())));
        if (bytes.length > MAX_FILE_BYTES) throw new IOException("PROGRAM_KEY_STORE_LIMIT");
        MigrationFiles.requirePlain(path, false);
        Path temporary = path.resolveSibling(".program-keys-" + UUID.randomUUID() + ".tmp");
        try {
            MigrationFiles.createPrivateFile(temporary);
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            MigrationFiles.requirePlain(path, false);
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            keys = Collections.unmodifiableMap(new LinkedHashMap<>(next));
        } finally { Files.deleteIfExists(temporary); }
    }

    private static String digest(String secret) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.US_ASCII))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
