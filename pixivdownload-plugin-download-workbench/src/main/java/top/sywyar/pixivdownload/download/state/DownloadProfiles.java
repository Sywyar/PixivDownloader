package top.sywyar.pixivdownload.download.state;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import top.sywyar.pixivdownload.plugin.api.storage.RuntimePathProvider;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import top.sywyar.pixivdownload.plugin.api.database.migration.MigrationFiles;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** 可复用方案只保存下载参数；页面当前值与计划任务各自保存独立副本。 */
public final class DownloadProfiles {
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    public static final int MAX_PROFILES = 64;
    private static final ObjectMapper JSON = JsonMapper.builderWithJackson2Defaults()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Set<String> EXCLUDED = Set.of("cookie", "credential", "password", "token", "secret",
            "collectionid", "userkind", "searchkind", "filenametemplates", "queue", "rootfolder", "proxy");
    private final Path file;

    public record Profile(String id, String name, ObjectNode settings, ObjectNode filters) { }
    public record Snapshot(String revision, List<Profile> profiles) { }
    public record Document(int schemaVersion, List<Profile> profiles) { }

    public DownloadProfiles(RuntimePathProvider paths) { file = paths.configFile("json"); }

    public synchronized Snapshot read() throws IOException {
        MigrationFiles.requirePlain(file, false);
        byte[] bytes;
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(MAX_BYTES + 1);
        } catch (NoSuchFileException missing) {
            return new Snapshot("0", List.of());
        }
        if (bytes.length > MAX_BYTES) throw new IOException("profiles.file-too-large");
        try {
            Document document = JSON.readValue(bytes, Document.class);
            if (document.schemaVersion() != 1) throw new IllegalArgumentException();
            validate(document.profiles());
            return new Snapshot(revision(bytes), document.profiles());
        } catch (RuntimeException invalid) {
            // 损坏文件保留原样，不能用空清单覆盖用户已有方案。
            throw new IOException("profiles.file-invalid");
        }
    }

    public synchronized Snapshot save(Snapshot expected) throws IOException {
        if (expected == null || expected.revision() == null) throw new IllegalArgumentException("profiles.invalid");
        validate(expected.profiles());
        byte[] bytes = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(new Document(1, expected.profiles()));
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("profiles.too-large");
        validate(JSON.readValue(bytes, Document.class).profiles());
        MigrationFiles.requirePlain(file, false);
        Path lockFile = file.resolveSibling(file.getFileName() + ".lock");
        MigrationFiles.requirePlain(lockFile, false);
        Files.createDirectories(file.getParent());
        try (var lockChannel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS); var ignored = lockChannel.lock()) {
            if (!read().revision().equals(expected.revision())) throw new Conflict();
            Path temporary = Files.createTempFile(file.getParent(), "download-profiles-", ".tmp");
            try {
                try (var output = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                    ByteBuffer buffer = ByteBuffer.wrap(bytes);
                    while (buffer.hasRemaining()) output.write(buffer);
                    output.force(true);
                }
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
        return new Snapshot(revision(bytes), JSON.readValue(bytes, Document.class).profiles());
    }

    public static Snapshot parse(byte[] bytes) {
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("profiles.too-large");
        return JSON.readValue(bytes, Snapshot.class);
    }

    private static void validate(List<Profile> profiles) {
        if (profiles == null || profiles.size() > MAX_PROFILES) throw new IllegalArgumentException("profiles.invalid");
        var ids = new HashSet<String>();
        var names = new HashSet<String>();
        for (Profile profile : profiles) {
            if (profile == null || profile.id() == null || !profile.id().matches("[a-zA-Z0-9-]{1,64}")
                    || !ids.add(profile.id()) || profile.name() == null || profile.name().isBlank()
                    || profile.name().length() > 80 || !names.add(profile.name().strip()))
                throw new IllegalArgumentException("profiles.invalid");
            validateValues(profile.settings());
            validateValues(profile.filters());
            if (JSON.writeValueAsBytes(profile).length > 64 * 1024)
                throw new IllegalArgumentException("profiles.too-large");
        }
    }

    private static void validateValues(ObjectNode values) {
        if (values == null || values.size() > 128) throw new IllegalArgumentException("profiles.invalid");
        values.properties().forEach(entry -> {
            String key = entry.getKey();
            if (!key.matches("[a-zA-Z][a-zA-Z0-9_.-]{0,95}")
                    || !key.equals("autoStartOnEnqueue")
                    && EXCLUDED.stream().anyMatch(key.toLowerCase(java.util.Locale.ROOT)::contains))
                throw new IllegalArgumentException("profiles.invalid");
            JsonNode value = entry.getValue();
            if (value.isArray()) {
                if (value.size() > 256) throw new IllegalArgumentException("profiles.invalid");
                value.forEach(DownloadProfiles::validateScalar);
            } else validateScalar(value);
        });
    }

    private static void validateScalar(JsonNode value) {
        if (!(value.isNull() || value.isBoolean() || value.isNumber() || value.isString())
                || value.isString() && value.stringValue().length() > 8192)
            throw new IllegalArgumentException("profiles.invalid");
    }

    private static String revision(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public static final class Conflict extends RuntimeException { }
}
