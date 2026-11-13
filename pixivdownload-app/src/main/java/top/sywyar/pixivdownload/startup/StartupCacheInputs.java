package top.sywyar.pixivdownload.startup;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import top.sywyar.pixivdownload.common.PlainFilePathGuard;
import top.sywyar.pixivdownload.plugin.runtime.artifact.PluginArtifactScanner;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.PluginProvenanceStore;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.PluginProvenanceRecord;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.InstalledPluginInventorySnapshot;
import top.sywyar.pixivdownload.plugin.catalog.trust.PluginCatalogTrustStateStore;
import top.sywyar.pixivdownload.plugin.catalog.security.PluginCatalogStrictJson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** 只冻结训练需要的插件字节、启停与公开信任配置，不复制业务数据或凭据。 */
final class StartupCacheInputs {
    record Artifact(Path source, String sha256, long bytes, PluginProvenanceRecord provenance) {}
    record Snapshot(List<Artifact> artifacts, String configuration,
                    Map<String, PluginCatalogTrustStateStore.RevocationSnapshot> revocations,
                    String identity, Path plugins, Path config, Path trustState) {}

    private StartupCacheInputs() {}

    static Snapshot capture(Path plugins, Path config, Path trustState) throws IOException {
        var scan = PluginArtifactScanner.scan(plugins);
        if (!scan.rejectedCandidates().isEmpty()) throw new IOException("Invalid plugin artifact");
        MessageDigest digest = digest();
        String configuration = trainingConfiguration(config);
        digest.update(configuration.getBytes(StandardCharsets.UTF_8));
        var mapper = PluginCatalogStrictJson.mapper(false);
        var revocations = new TreeMap<String, PluginCatalogTrustStateStore.RevocationSnapshot>();
        new PluginCatalogTrustStateStore(trustState).readSnapshot().repositories().forEach((id, state) -> {
            if (state.revocations() != null) revocations.put(id, state.revocations());
        });
        var revocationIdentity = mapper.valueToTree(revocations);
        // 同一文档再次验签只更新诊断时间，不改变执行准入；冻结副本仍保留完整快照。
        for (var snapshot : revocationIdentity)
            ((tools.jackson.databind.node.ObjectNode) snapshot).remove("verifiedAt");
        digest.update(mapper.writeValueAsBytes(revocationIdentity));
        var artifacts = new ArrayList<Artifact>();
        long remaining = PluginArtifactScanner.MAX_TOTAL_CANDIDATE_BYTES;
        long provenanceRemaining = InstalledPluginInventorySnapshot.MAX_PROVENANCE_BYTES;
        var provenanceStore = new PluginProvenanceStore(plugins);
        for (Path path : scan.candidates()) {
            checkInterrupted();
            long bytes = Files.size(path);
            if (bytes > remaining) throw new IOException("Plugin byte budget exceeded");
            String hash = hash(path, bytes);
            if (Files.size(path) != bytes) throw new InputsChanged();
            remaining -= bytes;
            var measured = provenanceStore.readMeasuredStrict(path, provenanceRemaining).orElse(null);
            PluginProvenanceRecord provenance = measured == null ? null : measured.record();
            if (measured != null) provenanceRemaining -= measured.byteCount();
            artifacts.add(new Artifact(path, hash, bytes, provenance));
            digest.update(path.getFileName().toString().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(hash.getBytes(StandardCharsets.US_ASCII));
            // 离线复验时间与诊断不是执行授权，不能让每次正常启动都使缓存失效。
            var trust = mapper.valueToTree(provenance);
            if (trust instanceof tools.jackson.databind.node.ObjectNode object)
                object.remove(List.of("verifiedAt", "offlineStatus", "offlineVerifiedAt", "diagnosticCode"));
            digest.update(mapper.writeValueAsBytes(trust));
        }
        return new Snapshot(List.copyOf(artifacts), configuration, Map.copyOf(revocations),
                HexFormat.of().formatHex(digest.digest()), plugins, config, trustState);
    }

    static void verifyCurrent(Snapshot snapshot) throws IOException {
        if (!snapshot.identity().equals(capture(snapshot.plugins(), snapshot.config(), snapshot.trustState()).identity()))
            throw new InputsChanged();
    }

    static void copy(Snapshot snapshot, Path plugins, Path config, Path trustState) throws IOException {
        verifyCurrent(snapshot);
        Files.createDirectories(plugins);
        Files.createDirectories(config.getParent());
        Files.writeString(config, snapshot.configuration(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        var destination = new PluginProvenanceStore(plugins);
        byte[] buffer = new byte[65536];
        for (Artifact artifact : snapshot.artifacts()) {
            checkInterrupted();
            Path source = artifact.source();
            PlainFilePathGuard.requirePlainRegularFile(source);
            Path target = plugins.resolve(source.getFileName().toString());
            var hash = digest();
            long bytes = 0;
            try (var input = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS);
                 var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                for (int read; (read = input.read(buffer)) != -1;) {
                    checkInterrupted();
                    bytes += read;
                    if (bytes > artifact.bytes()) throw new InputsChanged();
                    hash.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
            }
            if (bytes != artifact.bytes() || !HexFormat.of().formatHex(hash.digest()).equals(artifact.sha256()))
                throw new InputsChanged();
            if (artifact.provenance() == null) throw new IOException("Plugin provenance is missing");
            destination.write(target, artifact.provenance());
        }
        var stateStore = new PluginCatalogTrustStateStore(trustState);
        for (var entry : snapshot.revocations().entrySet()) stateStore.acceptRevocations(entry.getKey(), entry.getValue());
        verifyCurrent(snapshot);
    }

    static String trainingConfiguration(Path config) throws IOException {
        Map<?, ?> root = Map.of();
        if (Files.exists(config, LinkOption.NOFOLLOW_LINKS)) {
            PlainFilePathGuard.requirePlainRegularFile(config);
            var limits = new LoaderOptions();
            limits.setAllowDuplicateKeys(false);
            try (var reader = Files.newBufferedReader(config, StandardCharsets.UTF_8)) {
                Object value = new Yaml(new SafeConstructor(limits)).load(reader);
                if (!(value instanceof Map<?, ?> map)) throw new IOException("Invalid application configuration");
                root = map;
            } catch (RuntimeException invalid) {
                throw new IOException("Invalid application configuration", invalid);
            }
        }
        Map<String, Object> selected = new TreeMap<>();
        for (var entry : root.entrySet()) {
            if (entry.getKey() instanceof String key
                    && (key.startsWith("plugins.") && key.endsWith(".enabled")
                    || key.equals("app.gui-provider") || key.equals("app.theme"))) {
                selected.put(key, entry.getValue());
            }
        }
        var toggles = new TreeMap<String, Object>();
        selected.forEach((key, value) -> { if (key.startsWith("plugins.")) toggles.put(key, value); });
        var enabled = top.sywyar.pixivdownload.plugin.runtime.bootstrap.PluginEnabledSnapshot.of(toggles, "config.yaml");
        for (String key : toggles.keySet()) {
            if (enabled.isEnabled(key)) selected.remove(key); else selected.put(key, false);
        }
        selected.putIfAbsent("app.gui-provider", "");
        selected.putIfAbsent("app.theme", "system");
        // 仓库身份和公钥供离线复验；不继承目录地址、代理或任何业务凭据。
        Object repositories = catalogValue(root, "repositories");
        if (repositories instanceof List<?> list && !list.isEmpty()) {
            var copies = new ArrayList<Map<String, Object>>();
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> repository)) throw new IOException("Invalid plugin repository");
                var copy = new LinkedHashMap<String, Object>();
                copy.put("id", repository.get("id"));
                copy.put("manifest-url", "https://training.invalid/manifest.json");
                copy.put("trusted-keys", repository.getOrDefault("trusted-keys", null));
                copy.put("publisher-id", repository.getOrDefault("publisher-id", null));
                if (repository.get("revocations-url") instanceof String url && !url.isBlank())
                    copy.put("revocations-url", "https://training.invalid/revocations.json");
                copies.add(copy);
            }
            selected.put("plugin-catalog.repositories", copies);
        }
        if (catalogValue(root, "manifest-url") instanceof String url && !url.isBlank()) {
            selected.put("plugin-catalog.manifest-url", "https://training.invalid/manifest.json");
            selected.put("plugin-catalog.trusted-keys", catalogValue(root, "trusted-keys"));
        }
        selected.put("plugin-catalog.enabled", false);
        selected.put("proxy.enabled", false);
        selected.put("server.port", 0);
        selected.put("download.root-folder", "downloads");
        var output = new DumperOptions();
        output.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        return new Yaml(output).dump(selected);
    }

    private static Object catalogValue(Map<?, ?> root, String key) {
        if (root.containsKey("plugin-catalog." + key)) return root.get("plugin-catalog." + key);
        return root.get("plugin-catalog") instanceof Map<?, ?> section ? section.get(key) : null;
    }

    static String hash(Path path, long limit) throws IOException {
        PlainFilePathGuard.requirePlainRegularFile(path);
        var digest = digest();
        long bytes = 0;
        byte[] buffer = new byte[65536];
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            for (int read; (read = input.read(buffer)) != -1;) {
                checkInterrupted();
                bytes += read;
                if (bytes > limit) throw new IOException("Plugin byte budget exceeded");
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    static void checkInterrupted() throws java.io.InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Training cancelled");
    }

    static final class InputsChanged extends IOException {
        InputsChanged() { super("Startup training inputs changed"); }
    }
}
