package top.sywyar.pixivdownload.gui.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("自定义仓库表单前置校验：id / URL / 代理策略 / 超时大小")
class RepositoryConfigValidatorTest {

    private static RepositoryConfigEntry repo(String id) {
        return RepositoryConfigEntry.create(id, "", "https://x.example/manifest.json", true, "direct-strict", 0, 0, 0, 0);
    }

    @Test
    @DisplayName("id 为空被拒")
    void idEmptyRejected() {
        assertThat(RepositoryConfigValidator.validateId("  ", List.of()))
                .isEqualTo("gui.config.market.repo.error.id-empty");
    }

    @Test
    @DisplayName("保留字 id（official / configured，大小写不敏感）被拒")
    void reservedIdRejected() {
        assertThat(RepositoryConfigValidator.validateId("official", List.of()))
                .isEqualTo("gui.config.market.repo.error.id-reserved");
        assertThat(RepositoryConfigValidator.validateId("Configured", List.of()))
                .isEqualTo("gui.config.market.repo.error.id-reserved");
        assertThat(RepositoryConfigValidator.validateId("OFFICIAL", List.of()))
                .isEqualTo("gui.config.market.repo.error.id-reserved");
    }

    @Test
    @DisplayName("大小写归一后重复的 id 被拒")
    void duplicateIdRejected() {
        assertThat(RepositoryConfigValidator.validateId("MyRepo", List.of(repo("myrepo"))))
                .isEqualTo("gui.config.market.repo.error.id-duplicate");
    }

    @Test
    @DisplayName("合法且唯一的 id 通过")
    void validUniqueIdPasses() {
        assertThat(RepositoryConfigValidator.validateId("community", List.of(repo("other")))).isNull();
    }

    @Test
    @DisplayName("非 HTTPS / 非法 / 相对 / 空 host 的 URL 被拒，合法 https 通过")
    void urlValidation() {
        assertThat(RepositoryConfigValidator.validateManifestUrl(""))
                .isEqualTo("gui.config.market.repo.error.url-empty");
        assertThat(RepositoryConfigValidator.validateManifestUrl("http://example.com/m.json"))
                .isEqualTo("gui.config.market.repo.error.url-not-https");
        assertThat(RepositoryConfigValidator.validateManifestUrl("file:///etc/passwd"))
                .isEqualTo("gui.config.market.repo.error.url-unsupported-scheme");
        assertThat(RepositoryConfigValidator.validateManifestUrl("javascript:alert(1)"))
                .isEqualTo("gui.config.market.repo.error.url-unsupported-scheme");
        assertThat(RepositoryConfigValidator.validateManifestUrl("data:text/plain,hi"))
                .isEqualTo("gui.config.market.repo.error.url-unsupported-scheme");
        assertThat(RepositoryConfigValidator.validateManifestUrl("/relative/manifest.json"))
                .isEqualTo("gui.config.market.repo.error.url-not-absolute");
        assertThat(RepositoryConfigValidator.validateManifestUrl("https:///manifest.json"))
                .isEqualTo("gui.config.market.repo.error.url-no-host");
        assertThat(RepositoryConfigValidator.validateManifestUrl("ht tp://bad"))
                .isEqualTo("gui.config.market.repo.error.url-invalid");
        assertThat(RepositoryConfigValidator.validateManifestUrl("https://repo.example.com/manifest.json")).isNull();

        assertThat(RepositoryConfigValidator.validateManifestUrl("http://repo.example.com/manifest.json", false)).isNull();
        assertThat(RepositoryConfigValidator.validateManifestUrl("ftp://repo.example.com/manifest.json", false))
                .isEqualTo("gui.config.market.repo.error.url-unsupported-scheme");
    }

    @Test
    @DisplayName("未知代理策略被拒，固定枚举通过")
    void proxyPolicyValidation() {
        assertThat(RepositoryConfigValidator.validateProxyPolicy("direct-strict")).isNull();
        assertThat(RepositoryConfigValidator.validateProxyPolicy("proxy-trusted")).isNull();
        assertThat(RepositoryConfigValidator.validateProxyPolicy("custom")).isNull();
        assertThat(RepositoryConfigValidator.validateProxyPolicy("wild-proxy"))
                .isEqualTo("gui.config.market.repo.error.proxy-policy-unknown");
    }

    @Test
    @DisplayName("超时 / 大小覆盖：留空通过、正数通过、零或负数被拒、溢出 / 超上限被拒")
    void overrideValidation() {
        assertThat(RepositoryConfigValidator.validateTimeoutOverride("")).isNull();
        assertThat(RepositoryConfigValidator.validateTimeoutOverride("5000")).isNull();
        assertThat(RepositoryConfigValidator.validateTimeoutOverride("0"))
                .isEqualTo("gui.config.market.repo.error.number-not-positive");
        assertThat(RepositoryConfigValidator.validateTimeoutOverride("-1"))
                .isEqualTo("gui.config.market.repo.error.number-not-positive");
        assertThat(RepositoryConfigValidator.validateTimeoutOverride("99999999999999999999999"))
                .isEqualTo("gui.config.market.repo.error.number-invalid");
        assertThat(RepositoryConfigValidator.validateTimeoutOverride("999999999"))
                .isEqualTo("gui.config.market.repo.error.number-too-large");
        assertThat(RepositoryConfigValidator.validateSizeOverride("104857600")).isNull();
        assertThat(RepositoryConfigValidator.validateSizeOverride("abc"))
                .isEqualTo("gui.config.market.repo.error.number-invalid");
    }

    @Test
    @DisplayName("parseOverride：留空 / 非法 → 0（继承），正数原样")
    void parseOverride() {
        assertThat(RepositoryConfigValidator.parseOverride("")).isZero();
        assertThat(RepositoryConfigValidator.parseOverride("abc")).isZero();
        assertThat(RepositoryConfigValidator.parseOverride("-5")).isZero();
        assertThat(RepositoryConfigValidator.parseOverride("5000")).isEqualTo(5000);
    }
}
