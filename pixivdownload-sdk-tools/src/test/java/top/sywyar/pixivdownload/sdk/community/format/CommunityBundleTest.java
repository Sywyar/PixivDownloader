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
}
