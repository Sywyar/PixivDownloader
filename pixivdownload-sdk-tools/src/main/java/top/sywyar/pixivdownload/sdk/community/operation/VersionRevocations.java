package top.sywyar.pixivdownload.sdk.community.operation;

import com.fasterxml.jackson.annotation.JsonInclude;
import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.Evidence;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.Reference;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.TreeMap;

/** 受保护决定到既有 revocations-v1 的投影；决定来源不写入旧客户端文档。 */
public final class VersionRevocations {
    // 与既有客户端的撤销读取预算一致；不把新社区文档预算套入旧格式。
    public static final int MAX_BYTES = 512 * 1024;
    private final Wire document;
    private final List<Restriction> restrictions;
    private final Evidence archive;

    /** 来源及决定引用由受保护历史重建，不能由状态请求自行声明。 */
    public record Restriction(Reference decisionRef, boolean communityIndependent, Entry entry) { }
    public record Wire(int schemaVersion, String repositoryId, long sequence, String generatedTime,
                       String nextUpdate, List<Entry> entries) {
        public Wire { entries = List.copyOf(entries); }
    }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Entry(String scope, String pluginId, String version, String packageSha256,
                        String keyId, String publisherId, String action, String reasonCode, String effectiveTime) {
        String selector() {
            return scope + '|' + pluginId + '|' + version + '|' + packageSha256 + '|' + keyId + '|' + publisherId;
        }
        boolean owns(VersionState state) {
            return "PACKAGE_SHA256".equals(scope) && state.pluginId().equals(pluginId)
                    && state.version().equals(version) && state.packageSha256().equals(packageSha256);
        }
    }

    private VersionRevocations(Evidence archive, List<Restriction> restrictions) {
        this.document = CommunityJson.decode("legacyRevocations", archive.bytes(), MAX_BYTES, Wire.class);
        this.restrictions = List.copyOf(restrictions);
        this.archive = archive;
        if (!Instant.parse(document.nextUpdate).isAfter(Instant.parse(document.generatedTime))) {
            throw new ContractException("SCHEMA_INVALID", "/nextUpdate");
        }
        CommunityValues.unique(document.entries, Entry::selector, "/entries");
        if (!new HashSet<>(project(restrictions, document.generatedTime)).equals(new HashSet<>(document.entries))) {
            throw new ContractException("REVIEW_MISMATCH", "/entries");
        }
    }

    public static VersionRevocations read(Evidence archive, List<Restriction> restrictions) {
        return new VersionRevocations(archive, restrictions);
    }

    public static VersionRevocations generate(String repositoryId, long sequence, String generatedTime,
                                               String nextUpdate, List<Restriction> restrictions) {
        var wire = new Wire(1, repositoryId, sequence, generatedTime, nextUpdate, project(restrictions, generatedTime));
        return read(OperationContext.archive(CommunityJson.encode(wire)), restrictions);
    }

    public Wire document() { return document; }
    public List<Restriction> restrictions() { return restrictions; }
    public Evidence archive() { return archive; }

    Restriction managed(VersionState state) {
        var matches = restrictions.stream().filter(r -> !r.communityIndependent && r.entry.owns(state)).toList();
        if (state.state() == VersionState.State.ACTIVE && matches.isEmpty()) return null;
        if (matches.size() != 1 || !matches.get(0).entry.action.equals(state.state().name())
                || !matches.get(0).decisionRef.sha256().equals(state.decisionSha256())) {
            throw new ContractException("BASELINE_CHANGED", "/state");
        }
        return matches.get(0);
    }

    private static List<Entry> project(List<Restriction> restrictions, String generatedTime) {
        var entries = new TreeMap<String, Entry>();
        CommunityValues.unique(restrictions, r -> r.decisionRef.sha256() + '/' + r.communityIndependent
                + '/' + r.entry.selector(), "/restrictions");
        for (var restriction : restrictions) {
            restriction.decisionRef.validate();
            Entry entry = restriction.entry;
            byte[] bytes = CommunityJson.encode(entry);
            CommunityJson.validateStructure("legacyRevocationEntry", CommunityJson.strictTree(bytes, MAX_BYTES));
            entries.merge(entry.selector(), entry, (first, second) -> combine(first, second, generatedTime));
        }
        return List.copyOf(entries.values());
    }

    private static Entry combine(Entry first, Entry second, String generatedTime) {
        if (first.action.equals(second.action)) {
            return Instant.parse(first.effectiveTime).isAfter(Instant.parse(second.effectiveTime)) ? second : first;
        }
        Entry revoke = "REVOKED".equals(first.action) ? first : second;
        Entry yank = revoke == first ? second : first;
        // 同一 selector 的先下架、后吊销不能被旧格式的一条记录无损表达。
        if (Instant.parse(revoke.effectiveTime).isAfter(Instant.parse(yank.effectiveTime))
                && Instant.parse(revoke.effectiveTime).isAfter(Instant.parse(generatedTime))) {
            throw new ContractException("REVOCATION_REJECTED", "/entries");
        }
        return revoke;
    }
}
