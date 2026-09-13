package top.sywyar.pixivdownload.sdk.community.scan;

import org.erdtman.jcs.JsonCanonicalizer;
import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.Evidence;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.Reference;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;
import top.sywyar.pixivdownload.sdk.community.review.RiskReport;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.io.IOException;

/** 把实际包清点及两版扫描写成固定证据；不推测 Maven 坐标或运行可达性。 */
public final class PluginScanEvidence {
    private PluginScanEvidence() { }

    /** CycloneDX 文件清单描述实际根包及私有 JAR；构建依赖另外由依赖锁记录。 */
    public static Evidence sbom(PluginRiskScanner.Result result, int maximumBytes) {
        var components = result.components().stream().sorted(Comparator.comparing(PluginRiskScanner.Component::archivePath))
                .map(component -> Map.of("type", "file", "name", component.archivePath(),
                        "bom-ref", component.archivePath(),
                        "hashes", List.of(Map.of("alg", "SHA-256", "content", component.sha256())),
                        "properties", List.of(Map.of("name", "pixivdownload:size", "value", Long.toString(component.size())),
                                Map.of("name", "pixivdownload:origin", "value", component.origin().name()))))
                .toList();
        return document(Map.of("bomFormat", "CycloneDX", "specVersion", "1.6", "version", 1,
                "components", components, "metadata", Map.of("properties", List.of(
                        Map.of("name", "pixivdownload:sourceCommit", "value", result.report().sourceCommit()),
                        Map.of("name", "pixivdownload:packageSha256", "value", result.report().packageSha256())))), maximumBytes);
    }

    /** 只比较同一规则集的调用位置；包摘要变化本身不会把全部调用误报为新增风险。 */
    public static Evidence difference(RiskReport current, RiskReport previous, int maximumBytes) {
        if (previous != null && (!current.rulesSha256().equals(previous.rulesSha256())
                || !current.scannerVersion().equals(previous.scannerVersion()))) {
            throw new ContractException("REVIEW_MISMATCH", "/previousScan/rulesSha256");
        }
        var before = previous == null ? Map.<String, RiskReport.Observation>of() : positions(previous);
        var after = positions(current);
        var delta = new LinkedHashMap<String, Object>();
        delta.put("schemaVersion", 1);
        delta.put("sourceCommit", current.sourceCommit());
        delta.put("packageSha256", current.packageSha256());
        delta.put("previousSourceCommit", previous == null ? null : previous.sourceCommit());
        delta.put("previousPackageSha256", previous == null ? null : previous.packageSha256());
        delta.put("rulesSha256", current.rulesSha256());
        delta.put("status", current.status());
        delta.put("previousStatus", previous == null ? null : previous.status());
        delta.put("addedObservations", after.entrySet().stream().filter(entry -> !before.containsKey(entry.getKey()))
                .sorted(Map.Entry.comparingByKey()).map(Map.Entry::getValue).toList());
        delta.put("removedObservations", before.entrySet().stream().filter(entry -> !after.containsKey(entry.getKey()))
                .sorted(Map.Entry.comparingByKey()).map(Map.Entry::getValue).toList());
        return document(delta, maximumBytes);
    }

    private static Map<String, RiskReport.Observation> positions(RiskReport report) {
        return report.observations().stream().collect(Collectors.toMap(observation -> CommunityJson.sha256(
                CommunityJson.encode(List.of(observation.origin(), observation.signal(), observation.location()))),
                observation -> observation));
    }

    private static Evidence document(Object value, int maximumBytes) {
        byte[] bytes;
        try { bytes = new JsonCanonicalizer(CommunityJson.encode(value)).getEncodedUTF8(); }
        catch (IOException invalid) { throw new ContractException("SCHEMA_INVALID", "scanEvidence"); }
        if (maximumBytes <= 0 || bytes.length > maximumBytes) throw new ContractException("LIMIT_EXCEEDED", "scanEvidence");
        var reference = Reference.of("reviews/evidence/" + CommunityJson.sha256(bytes) + ".json", bytes);
        return new Evidence(reference, bytes);
    }
}
