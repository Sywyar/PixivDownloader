package top.sywyar.pixivdownload.sdk.community.candidate;

import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageLimits;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageReader;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageVerifier;
import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;
import top.sywyar.pixivdownload.sdk.community.project.PluginProjectLocator;
import top.sywyar.pixivdownload.sdk.community.submission.VersionSubmission.BuildProfile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/** 源码 CI 候选只记录已构建字节，不代表社区审核或发布批准。 */
public record SourceCandidate(int schemaVersion, String repositoryId, String repository, String sourceCommit,
                              String runId, long runAttempt, BuildProfile buildProfile,
                              String pluginId, String version, Artifact artifact) {
    public static final int MAX_BYTES = 64 * 1024;
    public record Artifact(String file, long size, String sha256) { }

    public static SourceCandidate read(byte[] bytes) {
        SourceCandidate candidate = CommunityJson.decode("sourceCandidate", bytes, MAX_BYTES, SourceCandidate.class);
        candidate.buildProfile.validate();
        if (List.of(candidate.repository.split("/")).stream().anyMatch(part -> part.equals(".") || part.equals(".."))
                || candidate.artifact.size > PluginPackageLimits.defaults().maxArchiveBytes()
                || !candidate.artifact.file.equals("pixivdownload-plugin-" + candidate.pluginId + "-" + candidate.version
                    + (candidate.artifact.file.endsWith(".jar") ? ".jar" : ".zip"))) {
            throw new ContractException("CANDIDATE_INVALID", "");
        }
        return candidate;
    }

    /** 调用方先求值构建模型，再传入所选安装产物；复制、结构校验和摘要使用同一冻结文件。 */
    public static SourceCandidate create(Path root, BuildProfile profile, List<String> outputs, String modelVersion,
                                         String repositoryId, String repository, String commit, String runId,
                                         long attempt, Path destination) throws Exception {
        var selected = PluginProjectLocator.select(root, profile.projectDir(), profile.id(), profile.artifactPath(), outputs);
        if (!Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS) || !destination.toRealPath().equals(destination.toAbsolutePath().normalize())) {
            throw new ContractException("PATH_MISMATCH", "/destination");
        }
        String extension = selected.artifact().toString().endsWith(".jar") ? ".jar" : ".zip";
        Path frozen = Files.createTempFile(destination, "candidate-", extension);
        try {
            var limits = PluginPackageLimits.defaults();
            var digest = MessageDigest.getInstance("SHA-256");
            long size = 0;
            try (var input = Files.newInputStream(selected.artifact(), LinkOption.NOFOLLOW_LINKS);
                 var output = Files.newOutputStream(frozen)) {
                byte[] buffer = new byte[8192];
                for (int n; (n = input.read(buffer)) != -1;) {
                    size += n;
                    if (size > limits.maxArchiveBytes()) throw new ContractException("LIMIT_EXCEEDED", "/artifact");
                    output.write(buffer, 0, n); digest.update(buffer, 0, n);
                }
            }
            PluginPackageVerifier.verifyAndMeasure(frozen, limits);
            var descriptor = PluginPackageReader.inspect(frozen, limits).descriptor();
            if (!descriptor.externalValidationErrors().isEmpty() || !descriptor.version().equals(modelVersion)) {
                throw new ContractException("MODEL_PACKAGE_VERSION_MISMATCH", "/artifact");
            }
            String file = "pixivdownload-plugin-" + descriptor.id() + "-" + descriptor.version() + extension;
            var candidate = read(CommunityJson.encode(new SourceCandidate(1, repositoryId, repository, commit, runId,
                    attempt, selected.profile(), descriptor.id(), descriptor.version(),
                    new Artifact(file, size, HexFormat.of().formatHex(digest.digest())))));
            Files.move(frozen, destination.resolve(file));
            Files.write(destination.resolve("source-candidate.json"), CommunityJson.encode(candidate), StandardOpenOption.CREATE_NEW);
            return candidate;
        } finally { Files.deleteIfExists(frozen); }
    }
}
