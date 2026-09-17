package top.sywyar.pixivdownload.sdk.community.review;

import com.fasterxml.jackson.databind.JsonNode;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.install.model.CommunityPackageEvidence;
import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;
import top.sywyar.pixivdownload.sdk.community.submission.DescriptorSnapshot;

import java.nio.charset.StandardCharsets;

/** 客户端只消费社区根认证的发布事实；不在客户端重新执行平台审核。 */
public record CommunityReview(String pluginId, String version, long packageSize, String packageSha256,
                              CommunityValues.Owner owner, String sourceCommit, String assuranceLevel,
                              CommunityValues.Reference submissionRef, DescriptorSnapshot descriptor) {
    public static CommunityReview read(CommunityPackageEvidence evidence) {
        var document = CommunityJson.parse(CommunityJson.Kind.REVIEW, evidence.reviewJson().getBytes(StandardCharsets.UTF_8));
        var review = read(document);
        if (!document.sha256().equals(evidence.reviewSha256()) || !review.sourceCommit.equals(evidence.sourceCommit())
                || !review.assuranceLevel.equals(evidence.assuranceLevel())) throw mismatch();
        return review;
    }

    public static CommunityReview read(CommunityJson.Document document) {
        if (document.kind() != CommunityJson.Kind.REVIEW) throw mismatch();
        JsonNode node = document.value();
        var descriptor = value("descriptor", node.get("descriptor"), DescriptorSnapshot.class);
        descriptor.validate();
        var review = new CommunityReview(node.get("pluginId").textValue(), node.get("version").textValue(),
                node.get("packageSize").longValue(), node.get("packageSha256").textValue(),
                value("owner", node.get("owner"), CommunityValues.Owner.class), node.get("source").get("commit").textValue(),
                node.get("assuranceLevel").textValue(), value("reference", node.get("submissionRef"), CommunityValues.Reference.class), descriptor);
        CommunityValues.version(review.version, "/version");
        review.submissionRef.validate();
        if (!"SOURCE_REVIEWED".equals(review.assuranceLevel) || !"PASS".equals(node.path("riskScan").path("gate").asText()))
            throw mismatch();
        return review;
    }

    public void requirePackage(String id, String expectedVersion, long size, String sha256) {
        if (!pluginId.equals(id) || !version.equals(expectedVersion) || packageSize != size || !packageSha256.equals(sha256))
            throw mismatch();
    }

    public void requireDescriptor(PluginDescriptor actual) {
        if (!descriptor.equals(DescriptorSnapshot.from(actual, pluginId, version))) throw mismatch();
    }

    public static <T> T value(String definition, JsonNode node, Class<T> type) {
        byte[] bytes = CommunityJson.encode(node);
        return CommunityJson.decode(definition, bytes, bytes.length, type);
    }
    private static ContractException mismatch() { return new ContractException("REVIEW_MISMATCH", "/review"); }
}
