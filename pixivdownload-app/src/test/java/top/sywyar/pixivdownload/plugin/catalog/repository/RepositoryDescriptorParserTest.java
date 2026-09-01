package top.sywyar.pixivdownload.plugin.catalog.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.catalog.error.PluginCatalogErrorCode;
import top.sywyar.pixivdownload.plugin.catalog.error.PluginCatalogException;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("第三方仓库描述符解析")
class RepositoryDescriptorParserTest {

    @Test
    @DisplayName("严格解析描述符并展示完整公钥指纹")
    void parsesStrictDescriptorAndDisplaysCompleteFingerprint() throws Exception {
        String spki = Base64.getEncoder().encodeToString(
                KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic().getEncoded());
        byte[] bytes = descriptor(spki, "\"extensions\":{\"future\":true}")
                .getBytes(StandardCharsets.UTF_8);

        ParsedRepositoryDescriptor parsed = new RepositoryDescriptorParser()
                .parse("https://repo.example/repository.json", bytes);

        assertThat(parsed.descriptor().repositoryId()).isEqualTo("sample.repo");
        assertThat(parsed.keyPreviews()).singleElement().satisfies(key -> {
            assertThat(key.fingerprint()).matches("sha256:[0-9a-f]{64}");
            assertThat(key.fingerprintDisplay()).startsWith("SHA-256 ").contains(":");
        });
        assertThat(parsed.networkHosts()).containsExactly("repo.example", "catalog.example");
    }

    @Test
    @DisplayName("拒绝重复字段与未知安全字段")
    void rejectsDuplicateAndUnknownSecurityFields() throws Exception {
        String spki = Base64.getEncoder().encodeToString(
                KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic().getEncoded());
        String duplicate = descriptor(spki, "\"networkProfile\":\"DIRECT_STRICT\"");
        String unknown = descriptor(spki, "\"repositorySignatureUrl\":\"https://evil.example/x\"");

        assertInvalid(duplicate, PluginCatalogErrorCode.REPOSITORY_DESCRIPTOR_INVALID);
        assertInvalid(unknown, PluginCatalogErrorCode.REPOSITORY_DESCRIPTOR_INVALID);
    }

    @Test
    @DisplayName("拒绝超过上限的扩展数组")
    void rejectsOversizedExtensionArray() throws Exception {
        String spki = Base64.getEncoder().encodeToString(
                KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic().getEncoded());

        assertInvalid(descriptor(spki, "\"extensions\":{\"many\":[" + "0,".repeat(256) + "0]}"),
                PluginCatalogErrorCode.REPOSITORY_DESCRIPTOR_INVALID);
    }

    private static void assertInvalid(String json, PluginCatalogErrorCode code) {
        assertThatThrownBy(() -> new RepositoryDescriptorParser().parse(
                "https://repo.example/repository.json", json.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(PluginCatalogException.class,
                        failure -> assertThat(failure.code()).isEqualTo(code));
    }

    private static String descriptor(String spki, String extra) {
        return """
                {
                  "schemaVersion":1,
                  "repositoryId":"sample.repo",
                  "displayName":"Sample Repository",
                  "publisher":{"id":"sample","displayName":"Sample Publisher","homepageUrl":"https://sample.example"},
                  "catalog":{"protocol":"paged-v2","endpoint":"https://catalog.example/v2"},
                  "networkProfile":"DIRECT_STRICT",
                  "trustedKeys":[{"keyId":"sample-1","algorithm":"Ed25519","publicKeySpkiBase64":"%s","state":"ACTIVE","publisher":"Sample Publisher","trustLabel":"User confirmed"}],
                  %s
                }
                """.formatted(spki, extra);
    }
}
