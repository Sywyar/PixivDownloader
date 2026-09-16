package top.sywyar.pixivdownload.plugin.catalog.community;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogProperties;
import top.sywyar.pixivdownload.plugin.catalog.error.PluginCatalogErrorCode;
import top.sywyar.pixivdownload.plugin.catalog.error.PluginCatalogException;
import top.sywyar.pixivdownload.plugin.catalog.repository.PluginCatalogClientProvider;
import top.sywyar.pixivdownload.plugin.catalog.repository.PluginRepository;
import top.sywyar.pixivdownload.plugin.catalog.repository.RepositoryProxyPolicy;
import top.sywyar.pixivdownload.plugin.signature.CommunityPluginTrustRoots;
import top.sywyar.pixivdownload.plugin.signature.PluginSupplyChainVerifier;
import top.sywyar.pixivdownload.plugin.signature.SignatureMetadata;
import top.sywyar.pixivdownload.plugin.signature.TrustedPluginKey;
import top.sywyar.pixivdownload.sdk.community.directory.DirectoryEntry;
import top.sywyar.pixivdownload.sdk.community.directory.DirectoryGeneration;
import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues;

import java.io.*;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/** 认证目录的小根与按需分片；发现指针不授予信任，旧状态损坏时拒绝重置防回滚水位。 */
@Service
public final class CommunityDirectoryService {
    public static final String REPOSITORY_ID = PluginRepository.COMMUNITY_ID;
    public static final String BASE_URL = PluginRepository.COMMUNITY_BASE_URL;
    public static final String DESCRIPTOR_URL = BASE_URL + "generated/repository.json";
    private static final int ROOT_BYTES = CommunityJson.Kind.DIRECTORY_ROOT.maximumBytes();
    private static final int SHARD_BYTES = CommunityJson.Kind.DIRECTORY_SHARD.maximumBytes();
    private static final int STATE_BYTES = ROOT_BYTES + SHARD_BYTES + 16 * 1024;
    private final Path statePath;
    private final URI base;
    private final BiFunction<String, Long, byte[]> fetch;
    private final PluginSupplyChainVerifier verifier;
    private final boolean enabled;

    @Autowired
    public CommunityDirectoryService(PluginCatalogProperties properties, PluginCatalogClientProvider clients) {
        this(RuntimeFiles.resolveCommunityDirectoryStatePath(), URI.create(BASE_URL),
                (url, maximum) -> clients.clientFor(new PluginRepository(REPOSITORY_ID, "", BASE_URL,
                        true, false, true, RepositoryProxyPolicy.GITHUB_RELEASES, "github-releases",
                        false, true, false, true, properties.getConnectTimeoutMs(), properties.getReadTimeoutMs(),
                        properties.getMaxManifestBytes(), properties.getMaxPackageBytes(), CommunityPluginTrustRoots.roots()))
                        .fetchBytes(url, maximum),
                new PluginSupplyChainVerifier(CommunityPluginTrustRoots.trustStore()), properties.isEnabled());
    }

    CommunityDirectoryService(Path statePath, URI base, BiFunction<String, Long, byte[]> fetch,
                              PluginSupplyChainVerifier verifier, boolean enabled) {
        this.statePath = statePath.toAbsolutePath().normalize();
        this.base = base;
        this.fetch = fetch;
        this.verifier = verifier;
        this.enabled = enabled;
    }

    public record Lookup(long sequence, DirectoryEntry entry, boolean cached) {
        public String status() { return entry == null ? "REMOVED" : entry.status().name(); }
        public boolean certifies(String id, String url, String sha256, List<TrustedPluginKey> keys) {
            if (entry == null || !entry.offersCertifiedIdentity() || !entry.repositoryId().equals(id)
                    || !entry.descriptorUrl().equals(url) || !entry.descriptorSha256().equals(sha256)) return false;
            Set<String> expected = entry.certifiedKeys().stream()
                    .map(key -> key.keyId() + ":" + key.spkiSha256()).collect(Collectors.toSet());
            Set<String> actual = keys.stream().map(key -> key.keyId() + ":" + key.publicKeyFingerprint()).collect(Collectors.toSet());
            return keys.size() == actual.size() && expected.equals(actual);
        }
    }

    public synchronized Lookup lookup(String repositoryId) {
        if (!enabled) throw unavailable("community directory is disabled");
        Snapshot previous = read();
        String prefix = DirectoryGeneration.prefix(repositoryId);
        Snapshot next;
        try {
            var pointer = CommunityJson.strictTree(fetch.apply(base.resolve("generated/current.json").toString(), 64L * 1024), 64 * 1024);
            if (pointer.path("schemaVersion").asInt() != 1) throw unavailable("unsupported directory pointer");
            var reference = pointer.get("directory");
            if (reference == null) throw unavailable("missing directory reference");
            String path = reference.path("path").asText();
            if (!path.matches("generated/generations/[1-9][0-9]*/directory\\.json")) throw unavailable("invalid directory reference");
            URI url = base.resolve(path);
            byte[] rootBytes = fetch.apply(url.toString(), (long) ROOT_BYTES);
            CommunityValues.verifyBytes(rootBytes, reference.path("size").asLong(), reference.path("sha256").asText(), "/directory");
            var metadata = pointer.get("directorySignature");
            if (metadata == null) throw unavailable("missing directory signature");
            SignatureMetadata signature = new SignatureMetadata(metadata.path("formatVersion").asInt(),
                    metadata.path("algorithm").asText(), metadata.path("keyId").asText(), metadata.path("value").asText());
            var document = CommunityJson.parse(CommunityJson.Kind.DIRECTORY_ROOT, rootBytes);
            var root = DirectoryGeneration.verifyRoot(document, url, REPOSITORY_ID, signature, verifier);
            if (!path.equals("generated/generations/" + root.sequence() + "/directory.json")
                    || root.sequence() != pointer.path("sequence").asLong()) throw unavailable("directory generation mismatch");
            if (previous != null && (root.sequence() < previous.root.sequence()
                    || root.sequence() == previous.root.sequence() && !document.sha256().equals(previous.document.sha256())))
                throw unavailable("directory sequence rollback or reuse");
            var ref = reference(root, prefix);
            byte[] shard = ref == null ? new byte[0]
                    : previous != null && previous.document.sha256().equals(document.sha256()) && previous.prefix.equals(prefix)
                    ? previous.shard : fetch.apply(ref.resolve(url).toString(), (long) SHARD_BYTES);
            next = snapshot(url, rootBytes, signature, prefix, shard);
        } catch (RuntimeException failure) {
            if (previous != null && previous.prefix.equals(prefix)) return result(previous, repositoryId, true);
            throw unavailable("community directory unavailable: " + failure.getMessage());
        }
        write(next);
        return result(next, repositoryId, false);
    }

    private Snapshot snapshot(URI url, byte[] rootBytes, SignatureMetadata signature, String prefix, byte[] shard) {
        if (!url.toString().startsWith(base.resolve("generated/generations/").toString())
                || !prefix.matches("[0-9a-f]{2}")) throw unavailable("invalid cached directory location");
        var document = CommunityJson.parse(CommunityJson.Kind.DIRECTORY_ROOT, rootBytes);
        var root = DirectoryGeneration.verifyRoot(document, url, REPOSITORY_ID, signature, verifier);
        if (!url.equals(base.resolve("generated/generations/" + root.sequence() + "/directory.json")))
            throw unavailable("cached directory generation mismatch");
        var ref = reference(root, prefix);
        List<DirectoryEntry> entries;
        if (ref == null) {
            if (shard.length != 0) throw unavailable("unexpected cached shard");
            entries = List.of();
        } else entries = DirectoryGeneration.validateShard(root, ref,
                CommunityJson.parse(CommunityJson.Kind.DIRECTORY_SHARD, shard)).entries();
        return new Snapshot(url, document, root, signature, prefix, shard, entries);
    }

    private static DirectoryGeneration.ShardReference reference(DirectoryGeneration.Root root, String prefix) {
        return root.shards().stream().filter(ref -> ref.prefix().equals(prefix)).findFirst().orElse(null);
    }
    private static Lookup result(Snapshot snapshot, String id, boolean cached) {
        return new Lookup(snapshot.root.sequence(), snapshot.entries.stream()
                .filter(entry -> entry.repositoryId().equals(id)).findFirst().orElse(null), cached);
    }
    private record Snapshot(URI url, CommunityJson.Document document, DirectoryGeneration.Root root,
                            SignatureMetadata signature, String prefix, byte[] shard, List<DirectoryEntry> entries) { }

    private Snapshot read() {
        if (!Files.exists(statePath, LinkOption.NOFOLLOW_LINKS)) return null;
        try (var input = new DataInputStream(Files.newInputStream(statePath, LinkOption.NOFOLLOW_LINKS))) {
            if (!Files.isRegularFile(statePath, LinkOption.NOFOLLOW_LINKS) || Files.size(statePath) > STATE_BYTES
                    || input.readInt() != 1) throw new IOException("invalid directory state");
            URI url = URI.create(input.readUTF());
            var signature = new SignatureMetadata(input.readInt(), input.readUTF(), input.readUTF(), input.readUTF());
            String prefix = input.readUTF();
            byte[] root = bytes(input, ROOT_BYTES);
            byte[] shard = bytes(input, SHARD_BYTES);
            if (input.read() != -1) throw new IOException("trailing directory state");
            return snapshot(url, root, signature, prefix, shard);
        } catch (IOException | RuntimeException failure) {
            throw unavailable("cached directory state is invalid");
        }
    }
    private static byte[] bytes(DataInputStream input, int maximum) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > maximum) throw new IOException("directory state limit exceeded");
        byte[] result = input.readNBytes(count);
        if (result.length != count) throw new EOFException();
        return result;
    }
    private void write(Snapshot snapshot) {
        // ponytail: 只持久化最近一个桶；需要离线浏览多个桶时再扩展有界缓存。
        Path temp = null;
        try {
            Files.createDirectories(statePath.getParent());
            temp = Files.createTempFile(statePath.getParent(), ".community-directory-", ".tmp");
            try (var out = new DataOutputStream(Files.newOutputStream(temp, LinkOption.NOFOLLOW_LINKS))) {
                out.writeInt(1); out.writeUTF(snapshot.url.toString());
                out.writeInt(snapshot.signature.formatVersion()); out.writeUTF(snapshot.signature.algorithm());
                out.writeUTF(snapshot.signature.keyId()); out.writeUTF(snapshot.signature.value()); out.writeUTF(snapshot.prefix);
                byte[] root = snapshot.document.bytes();
                out.writeInt(root.length); out.write(root); out.writeInt(snapshot.shard.length); out.write(snapshot.shard);
            }
            try (var channel = FileChannel.open(temp, StandardOpenOption.WRITE)) { channel.force(true); }
            Files.move(temp, statePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            throw unavailable("could not persist directory sequence");
        } finally {
            if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException ignored) { }
        }
    }
    private static PluginCatalogException unavailable(String detail) {
        return new PluginCatalogException(PluginCatalogErrorCode.CATALOG_UNAVAILABLE, detail);
    }
}
