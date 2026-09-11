package top.sywyar.pixivdownload.sdk.community.submission;

import com.fasterxml.jackson.annotation.JsonProperty;
import top.sywyar.pixivdownload.plugin.signature.SignatureMetadata;
import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;
import top.sywyar.pixivdownload.sdk.community.project.CommunityPaths;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 版本投稿只保存作者输入；包内描述符、实际身份与审核结论由独立证据提供。 */
public record VersionSubmission(int schemaVersion, String publisherId, String pluginId, String version,
                                Source source, BuildProfile buildProfile, License license,
                                @JsonProperty("package") Artifact artifact, MarketMetadata market) {
    public record Source(String repository, String commit, String previousReviewedCommit,
                         CommunityValues.RemoteReference archive) {
        public void validate() {
            var uri = CommunityValues.https(repository, true, "/source/repository");
            if (!"github.com".equalsIgnoreCase(uri.getHost()) || uri.getPort() != -1
                    || !uri.getRawPath().matches("/[A-Za-z0-9._-]+/[A-Za-z0-9._-]+")
                    || java.util.Arrays.stream(uri.getRawPath().split("/")).anyMatch(part -> part.equals(".") || part.equals(".."))) {
                throw ContractException.invalid("SOURCE_UNSUPPORTED", "/source/repository");
            }
            archive.validate();
        }
    }
    public record BuildProfile(String id, String projectDir, String artifactPath) {
        public void validate() {
            CommunityPaths.relative(projectDir, true);
            CommunityPaths.relative(artifactPath, false);
        }
    }
    public record License(String expression, List<CommunityValues.Reference> files,
                          Map<String, CommunityValues.Reference> licenseRefs) {
        public License {
            files = List.copyOf(files);
            licenseRefs = licenseRefs == null ? Map.of() : Map.copyOf(licenseRefs);
        }
        public void validate() {
            files.forEach(CommunityValues.Reference::validate);
            CommunityValues.unique(files, CommunityValues.Reference::path, "/license/files");
            licenseRefs.values().forEach(CommunityValues.Reference::validate);
            SpdxExpression.validate(expression, licenseRefs.keySet());
        }
        public void verifySourceFiles(java.nio.file.Path sourceRoot) throws java.io.IOException {
            for (var file : files) file.verify(sourceRoot);
            for (var file : licenseRefs.values()) file.verify(sourceRoot);
        }
    }
    public record Artifact(String url, long expectedSize, String sha256, SignatureMetadata signature) {
        public void validate() { CommunityValues.https(url, false, "/package/url"); }
        public void verify(byte[] bytes) { CommunityValues.verifyBytes(bytes, expectedSize, sha256, "/package"); }
    }

    public static VersionSubmission read(CommunityJson.Document document) {
        if (document.kind() != CommunityJson.Kind.SUBMISSION) throw new ContractException("SCHEMA_INVALID", "");
        VersionSubmission value = document.as(VersionSubmission.class);
        CommunityValues.version(value.version(), "/version");
        value.source().validate();
        value.buildProfile().validate();
        value.license().validate();
        value.artifact().validate();
        value.market().validate();
        return value;
    }

    /** 前序审核关系不要求 Git 祖先关系；转移后的完整审阅由审核状态负责。 */
    public void verifyPreviousSource(String previousReviewedCommit) {
        if (!Objects.equals(source.previousReviewedCommit(), previousReviewedCommit)) {
            throw new ContractException("BASELINE_CHANGED", "/source/previousReviewedCommit");
        }
    }
}
