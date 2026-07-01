package top.sywyar.pixivdownload.plugin.runtime.install;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.signature.SignatureMetadata;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("插件包来源建模与完整性校验（本地、无网络）")
class PluginPackageIntegrityTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("本地上传来源：无完整性期望，verify 直接通过")
    void localUploadHasNoExpectations() throws IOException {
        Path file = writeFile("p.zip", "abc");
        PluginPackageOrigin origin = PluginPackageOrigin.localUpload();

        assertThat(origin.source()).isEqualTo(PluginPackageSource.LOCAL_UPLOAD);
        assertThat(origin.hasIntegrityExpectations()).isFalse();
        assertThat(PluginPackageIntegrity.verify(origin, file).ok()).isTrue();
    }

    @Test
    @DisplayName("本地上传来源携带完整性期望：构造期即拒绝（无可信清单背书）")
    void localUploadRejectsExpectations() {
        assertThatThrownBy(() -> new PluginPackageOrigin(
                PluginPackageSource.LOCAL_UPLOAD, null, false, 10L, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("受信目录来源 + 正确大小与 SHA-256：verify 通过")
    void trustedCatalogMatchingPasses() throws IOException {
        Path file = writeFile("p.zip", "abc");
        String sha = PluginPackageIntegrity.sha256Hex(file);
        long size = Files.size(file);
        PluginPackageOrigin origin = PluginPackageOrigin.forTrustedCatalog("test-repository", false, size, sha, null);

        assertThat(origin.source()).isEqualTo(PluginPackageSource.MARKET_CATALOG);
        assertThat(origin.hasIntegrityExpectations()).isTrue();
        assertThat(PluginPackageIntegrity.verify(origin, file).ok()).isTrue();
    }

    @Test
    @DisplayName("受信目录来源 + 错误大小：verify 失败")
    void trustedCatalogWrongSizeFails() throws IOException {
        Path file = writeFile("p.zip", "abc");
        PluginPackageOrigin origin = PluginPackageOrigin.forTrustedCatalog("test-repository", false, 9999L, null, null);

        PluginPackageIntegrity.Result result = PluginPackageIntegrity.verify(origin, file);
        assertThat(result.ok()).isFalse();
        assertThat(result.detail()).contains("size");
    }

    @Test
    @DisplayName("受信目录来源 + 错误 SHA-256：verify 失败")
    void trustedCatalogWrongShaFails() throws IOException {
        Path file = writeFile("p.zip", "abc");
        PluginPackageOrigin origin = PluginPackageOrigin.forTrustedCatalog(
                "test-repository", false,
                null, "0000000000000000000000000000000000000000000000000000000000000000", null);

        assertThat(PluginPackageIntegrity.verify(origin, file).ok()).isFalse();
    }

    @Test
    @DisplayName("受信目录来源声明了签名但无校验器：fail-closed 拒绝（绝不放行未校验的已签名包）")
    void signatureExpectationFailsClosed() throws IOException {
        Path file = writeFile("p.zip", "abc");
        SignatureMetadata metadata = new SignatureMetadata(
                SignatureMetadata.FORMAT_VERSION, SignatureMetadata.ED25519, "test-key", "c2ln");
        PluginPackageOrigin origin = PluginPackageOrigin.forTrustedCatalog(
                "test-repository", false, null, null, metadata);

        PluginPackageIntegrity.Result result = PluginPackageIntegrity.verify(origin, file);
        assertThat(result.ok()).isFalse();
        assertThat(result.detail()).contains("signature");
    }

    @Test
    @DisplayName("sha256Hex 与已知测试向量一致（\"abc\" 的 SHA-256）")
    void sha256MatchesKnownVector() throws IOException {
        Path file = writeFile("abc.bin", "abc");
        assertThat(PluginPackageIntegrity.sha256Hex(file))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    private Path writeFile(String name, String content) throws IOException {
        Path file = dir.resolve(name);
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }
}
