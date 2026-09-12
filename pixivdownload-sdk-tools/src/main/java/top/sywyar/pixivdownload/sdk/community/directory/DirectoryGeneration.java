package top.sywyar.pixivdownload.sdk.community.directory;

import top.sywyar.pixivdownload.plugin.signature.PluginSupplyChainVerifier;
import top.sywyar.pixivdownload.plugin.signature.SignatureMetadata;
import top.sywyar.pixivdownload.plugin.signature.VerificationStatus;
import top.sywyar.pixivdownload.plugin.signature.community.CommunityDirectoryVerificationRequest;
import top.sywyar.pixivdownload.plugin.runtime.http.HttpsLocation;
import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 固定分桶的一代完整目录。字节、引用和签名一起验证，不改写旧代。 */
public final class DirectoryGeneration {
    public static final String PARTITIONING = "sha256-repository-id-prefix-2";
    public record ShardReference(String prefix, String url, long size, String sha256) {
        public URI resolve(URI authenticatedRootUrl) {
            CommunityValues.https(authenticatedRootUrl.toString(), false, "/rootUrl");
            if (url == null) throw new ContractException("URL_UNSAFE", "/shards/url");
            if (url.length() > HttpsLocation.MAX_URL_CHARS) throw CommunityJson.limit("/shards/url", HttpsLocation.MAX_URL_CHARS, "UTF-16");
            try {
                URI relative = new URI(url);
                if (relative.getRawFragment() != null || relative.getRawUserInfo() != null || url.isBlank()) {
                    throw new ContractException("URL_UNSAFE", "/shards/url");
                }
                return CommunityValues.https(authenticatedRootUrl.resolve(relative).toString(), false, "/shards/url");
            } catch (java.net.URISyntaxException e) { throw new ContractException("URL_UNSAFE", "/shards/url"); }
        }
    }
    public record Root(int schemaVersion, String repositoryId, long sequence, String generatedAt, String partitioning,
                       List<ShardReference> shards) {
        public Root { shards = List.copyOf(shards); }
    }
    public record Shard(int schemaVersion, String prefix, List<DirectoryEntry> entries) {
        public Shard { entries = List.copyOf(entries); }
    }
    /** 生成与取回使用同一原始字节集合；Map 的键为 SHA，URL 只表达取回位置。 */
    public record Candidate(CommunityJson.Document root, Map<String, CommunityJson.Document> shards) {
        public Candidate { shards = Map.copyOf(shards); }
    }

    private final Candidate candidate;
    private final Root root;
    private final SignatureMetadata signature;
    private DirectoryGeneration(Candidate candidate, Root root, SignatureMetadata signature) {
        this.candidate = candidate; this.root = root; this.signature = signature;
    }
    public Candidate candidate() { return candidate; }
    public Root root() { return root; }
    public SignatureMetadata signature() { return signature; }

    public static String prefix(String repositoryId) {
        return CommunityJson.sha256(repositoryId.getBytes(StandardCharsets.UTF_8)).substring(0, 2);
    }

    /** 只输出非空桶，移除记录由外部不可变审计保留，不进入活动分片。 */
    public static Candidate generate(String repositoryId, long sequence, String generatedAt, List<DirectoryEntry> entries) {
        CommunityValues.unique(entries, DirectoryEntry::repositoryId, "/entries");
        var buckets = new TreeMap<String, List<DirectoryEntry>>();
        for (var entry : entries) {
            entry.validate();
            if (entry.status() == DirectoryEntry.Status.REMOVED) continue;
            buckets.computeIfAbsent(prefix(entry.repositoryId()), key -> new ArrayList<>()).add(entry);
        }
        var documents = new HashMap<String, CommunityJson.Document>();
        var references = new ArrayList<ShardReference>();
        buckets.forEach((prefix, values) -> {
            values.sort(Comparator.comparing(DirectoryEntry::repositoryId));
            var document = CommunityJson.parse(CommunityJson.Kind.DIRECTORY_SHARD, CommunityJson.encode(new Shard(1, prefix, values)));
            documents.put(document.sha256(), document);
            references.add(new ShardReference(prefix, "shards/" + document.sha256() + ".json", document.bytes().length, document.sha256()));
        });
        var document = CommunityJson.parse(CommunityJson.Kind.DIRECTORY_ROOT,
                CommunityJson.encode(new Root(1, repositoryId, sequence, generatedAt, PARTITIONING, references)));
        var candidate = new Candidate(document, documents);
        validateData(candidate, URI.create("https://generation.invalid/root.json"));
        return candidate;
    }

    public static DirectoryGeneration verify(Candidate candidate, URI rootUrl, String expectedRepositoryId,
                                             SignatureMetadata signature, PluginSupplyChainVerifier verifier) {
        Root root = validateData(candidate, rootUrl);
        if (!root.repositoryId.equals(expectedRepositoryId)) throw new ContractException("IDENTITY_MISMATCH", "/repositoryId");
        var verified = verifier.verifyCommunityDirectory(new CommunityDirectoryVerificationRequest(candidate.root.bytes(),
                root.repositoryId, root.sequence, signature, false));
        if (verified.status() != VerificationStatus.VERIFIED) throw new ContractException(verified.status().name(), "/signature");
        return new DirectoryGeneration(candidate, root, signature);
    }

    private static Root validateData(Candidate candidate, URI rootUrl) {
        CommunityValues.https(rootUrl.toString(), false, "/rootUrl");
        if (candidate.root.kind() != CommunityJson.Kind.DIRECTORY_ROOT) throw new ContractException("SCHEMA_INVALID", "/root");
        Root root = candidate.root.as(Root.class);
        CommunityValues.unique(root.shards, ShardReference::prefix, "/shards/prefix");
        CommunityValues.unique(root.shards, ShardReference::sha256, "/shards/sha256");
        if (!root.shards.equals(root.shards.stream().sorted(Comparator.comparing(ShardReference::prefix)).toList())
                || candidate.shards.size() != root.shards.size()) throw new ContractException("REVIEW_MISMATCH", "/shards");
        for (var reference : root.shards) {
            reference.resolve(rootUrl);
            var document = candidate.shards.get(reference.sha256);
            if (document == null || document.kind() != CommunityJson.Kind.DIRECTORY_SHARD) throw new ContractException("REVIEW_MISMATCH", "/shards");
            CommunityValues.verifyBytes(document.bytes(), reference.size, reference.sha256, "/shards");
            var shard = document.as(Shard.class);
            if (!shard.prefix.equals(reference.prefix)) throw new ContractException("REVIEW_MISMATCH", "/shards/prefix");
            CommunityValues.unique(shard.entries, DirectoryEntry::repositoryId, "/entries");
            if (!shard.entries.equals(shard.entries.stream().sorted(Comparator.comparing(DirectoryEntry::repositoryId)).toList())) {
                throw new ContractException("REVIEW_MISMATCH", "/entries");
            }
            for (var entry : shard.entries) {
                entry.validate();
                if (!prefix(entry.repositoryId()).equals(shard.prefix) || entry.directorySequence() > root.sequence
                        || Instant.parse(entry.lastReviewedAt()).isAfter(Instant.parse(root.generatedAt))
                        || entry.status() == DirectoryEntry.Status.REMOVED) {
                    throw new ContractException("REVIEW_MISMATCH", "/entries");
                }
            }
        }
        return root;
    }
}
