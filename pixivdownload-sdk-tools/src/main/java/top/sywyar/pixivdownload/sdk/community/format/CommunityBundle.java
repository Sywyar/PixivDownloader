package top.sywyar.pixivdownload.sdk.community.format;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

/** 工具与外部分发副本共用的固定资源清单；不抓取可变分支或在线目录。 */
public final class CommunityBundle {
    public record ToolDigest(String version, String sha256) { }
    public record Toolchain(String sdk, String java, ToolDigest maven, ToolDigest gradle, ToolDigest sbt,
                            String jsonSchemaValidator, String jsonCanonicalization, String webpDecoder) { }
    public record Manifest(int schemaVersion, int contractVersion, Toolchain toolchain, List<CommunityValues.Reference> files) {
        public Manifest { files = List.copyOf(files); }
    }
    public record Distribution(int schemaVersion, String sourceCommit, String sdkVersion, int contractVersion,
                               String manifestSha256, CommunityValues.Reference tool) { }
    private CommunityBundle() { }

    /** 清单来自同一已固定工具 JAR；自身可信发行资源的长度不另加外部文档阈值。 */
    public static CommunityValues.Evidence bundledManifest() throws IOException {
        byte[] bytes = resource("bundle-manifest.json");
        return new CommunityValues.Evidence(CommunityValues.Reference.of("bundle-manifest.json", bytes), bytes);
    }

    public static Manifest verifyBundled() throws IOException {
        var manifest = read(bundledManifest());
        for (var reference : manifest.files) reference.verify(resource(reference.path()));
        return manifest;
    }

    /** 校验外部副本前先用工具自带清单核对原始字节，不能信任副本自报的摘要。 */
    public static Manifest verifyCopy(Path directory) throws IOException {
        var trusted = bundledManifest();
        trusted.reference().verify(directory);
        var manifest = read(trusted);
        for (var reference : manifest.files) reference.verify(directory);
        var allowed = new java.util.HashSet<String>();
        allowed.add(""); allowed.add("bundle-manifest.json");
        for (var reference : manifest.files) {
            String path = reference.path();
            allowed.add(path);
            while (path.contains("/")) { path = path.substring(0, path.lastIndexOf('/')); allowed.add(path); }
        }
        try (var paths = java.nio.file.Files.walk(directory)) {
            var unexpected = paths.filter(path -> !allowed.contains(directory.relativize(path).toString().replace('\\', '/'))).findFirst();
            if (unexpected.isPresent()) throw new ContractException("SCHEMA_INVALID", "/files");
        }
        return manifest;
    }

    /** 发行元数据必须来自调用方已固定的发行物；同时检查源码身份、资源和工具实际字节。 */
    public static Distribution verifyDistribution(Path workspace, CommunityValues.Evidence frozenMetadata,
                                                  String expectedSourceCommit) throws IOException {
        if (!frozenMetadata.reference().path().equals("tools/community-contract.json")) {
            throw new ContractException("PATH_MISMATCH", "/metadata");
        }
        frozenMetadata.reference().verify(workspace);
        byte[] bytes = frozenMetadata.bytes();
        var distribution = CommunityJson.decode("sdkDistribution", bytes, bytes.length, Distribution.class);
        var bundled = bundledManifest();
        var manifest = verifyCopy(workspace.resolve("contracts/community/v1"));
        if (!distribution.sourceCommit.equals(expectedSourceCommit)
                || !distribution.sdkVersion.equals(manifest.toolchain.sdk)
                || distribution.contractVersion != manifest.contractVersion
                || !distribution.manifestSha256.equals(bundled.reference().sha256())) {
            throw new ContractException("REVIEW_MISMATCH", "/metadata");
        }
        distribution.tool.verify(workspace);
        return distribution;
    }

    private static Manifest read(CommunityValues.Evidence evidence) {
        Manifest manifest = CommunityJson.decode("bundleManifest", evidence.bytes(), evidence.bytes().length, Manifest.class);
        CommunityValues.unique(manifest.files, CommunityValues.Reference::path, "/files");
        manifest.files.forEach(CommunityValues.Reference::validate);
        if (!manifest.files.equals(manifest.files.stream().sorted(java.util.Comparator.comparing(CommunityValues.Reference::path)).toList())) {
            throw new ContractException("SCHEMA_INVALID", "/files");
        }
        return manifest;
    }

    private static byte[] resource(String path) throws IOException {
        try (InputStream input = CommunityBundle.class.getResourceAsStream("/community/v1/" + path)) {
            if (input == null) throw new ContractException("REVIEW_MISMATCH", path);
            return input.readAllBytes();
        }
    }
}
