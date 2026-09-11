package top.sywyar.pixivdownload.sdk.community.format;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

@DisplayName("固定合同资源可由工具和独立分发副本消费")
class CommunityBundleTest {
    @TempDir Path temporary;

    @Test
    @DisplayName("工具内完整资源匹配清单，外部副本拒绝改写、缺失与额外文件")
    void verifiesFrozenCopies() throws Exception {
        var manifest = CommunityBundle.verifyBundled();
        var bundled = CommunityBundle.bundledManifest();
        Files.write(temporary.resolve(bundled.reference().path()), bundled.bytes());
        for (var reference : manifest.files()) {
            var file = temporary.resolve(reference.path()); Files.createDirectories(file.getParent());
            try (var input = getClass().getResourceAsStream("/community/v1/" + reference.path())) { Files.copy(input, file); }
        }
        assertThat(CommunityBundle.verifyCopy(temporary)).isEqualTo(manifest);
        var messages = temporary.resolve("messages.json");
        byte[] original = Files.readAllBytes(messages);
        CommunityJson.validateStructure("signatureMessages", CommunityJson.strictTree(original, original.length));
        Files.writeString(messages, "{}", java.nio.charset.StandardCharsets.UTF_8);
        assertThatThrownBy(() -> CommunityBundle.verifyCopy(temporary)).isInstanceOf(ContractException.class);
        Files.write(messages, original);
        Files.writeString(temporary.resolve("extra.json"), "{}", java.nio.charset.StandardCharsets.UTF_8);
        assertThatThrownBy(() -> CommunityBundle.verifyCopy(temporary)).isInstanceOf(ContractException.class);
        Files.delete(temporary.resolve("extra.json")); Files.delete(messages);
        assertThatThrownBy(() -> CommunityBundle.verifyCopy(temporary)).isInstanceOfAny(ContractException.class, java.io.IOException.class);
    }

    @Test
    @DisplayName("固定发行元数据核对源码版本、工具和资源，不接受副本重新声明摘要")
    void verifiesDistributionBytes() throws Exception {
        var manifest = CommunityBundle.verifyBundled();
        var bundled = CommunityBundle.bundledManifest();
        var contracts = temporary.resolve("contracts/community/v1");
        Files.createDirectories(contracts);
        Files.write(contracts.resolve("bundle-manifest.json"), bundled.bytes());
        for (var ref : manifest.files()) {
            var file = contracts.resolve(ref.path()); Files.createDirectories(file.getParent());
            try (var input = getClass().getResourceAsStream("/community/v1/" + ref.path())) { Files.copy(input, file); }
        }
        var tool = temporary.resolve("tools/sdk-tools.jar"); Files.createDirectories(tool.getParent());
        byte[] toolBytes = "test tool bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(tool, toolBytes);
        String source = "ab".repeat(20);
        var distribution = new CommunityBundle.Distribution(1, source, manifest.toolchain().sdk(), 1,
                bundled.reference().sha256(), CommunityValues.Reference.of("tools/sdk-tools.jar", toolBytes));
        byte[] metadata = CommunityJson.encode(distribution);
        var frozen = new CommunityValues.Evidence(CommunityValues.Reference.of("tools/community-contract.json", metadata), metadata);
        Files.write(temporary.resolve(frozen.reference().path()), metadata);
        assertThat(CommunityBundle.verifyDistribution(temporary, frozen, source)).isEqualTo(distribution);
        assertThatThrownBy(() -> CommunityBundle.verifyDistribution(temporary, frozen, "cd".repeat(20)))
                .isInstanceOf(ContractException.class);
        Files.writeString(tool, "other tool bytes", java.nio.charset.StandardCharsets.UTF_8);
        assertThatThrownBy(() -> CommunityBundle.verifyDistribution(temporary, frozen, source)).isInstanceOf(ContractException.class);
        Files.write(tool, toolBytes);
        for (var field : java.util.List.of("sdkVersion", "manifestSha256", "sourceCommit", "contractVersion")) {
            var changed = (com.fasterxml.jackson.databind.node.ObjectNode) CommunityJson.strictTree(metadata, metadata.length);
            if (field.equals("contractVersion")) changed.put(field, 2);
            else changed.put(field, field.equals("sdkVersion") ? "99.0.0" : "cd".repeat(field.equals("sourceCommit") ? 20 : 32));
            byte[] bytes = CommunityJson.encode(changed);
            var alternative = new CommunityValues.Evidence(CommunityValues.Reference.of(frozen.reference().path(), bytes), bytes);
            Files.write(temporary.resolve(frozen.reference().path()), bytes);
            assertThatThrownBy(() -> CommunityBundle.verifyDistribution(temporary, alternative, source)).isInstanceOf(ContractException.class);
        }
        Files.write(temporary.resolve(frozen.reference().path()), metadata);
        Files.writeString(contracts.resolve("messages.json"), "{}", java.nio.charset.StandardCharsets.UTF_8);
        assertThatThrownBy(() -> CommunityBundle.verifyDistribution(temporary, frozen, source)).isInstanceOf(ContractException.class);
    }
}
