package top.sywyar.pixivdownload.plugin.verification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogPackage;
import top.sywyar.pixivdownload.plugin.catalog.repository.PluginRepository;
import top.sywyar.pixivdownload.plugin.catalog.repository.RepositoryProxyPolicy;
import top.sywyar.pixivdownload.plugin.runtime.install.PluginPackageSource;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.PluginProvenanceRecord;
import top.sywyar.pixivdownload.plugin.signature.SignatureMetadata;
import top.sywyar.pixivdownload.plugin.signature.TrustedPluginKey;
import top.sywyar.pixivdownload.plugin.signature.VerificationStatus;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("插件验签状态后端投影")
class PluginVerificationProjectorTest {

    @Test
    @DisplayName("catalog 包：官方 ACTIVE official key 投影为官方已验证")
    void officialActiveKeyProjectsAsVerifiedOfficial() {
        TrustedPluginKey key = key("official-key", TrustedPluginKey.State.ACTIVE, true);

        PluginVerificationView view = PluginVerificationProjector.forCatalogPackage(
                repository(true, key), pkg(signature("official-key")));

        assertThat(view.status()).isEqualTo(PluginVerificationProjector.VERIFIED_OFFICIAL);
        assertThat(view.source()).isEqualTo("official");
        assertThat(view.keyId()).isEqualTo("official-key");
    }

    @Test
    @DisplayName("catalog 包：自定义仓库 ACTIVE key 投影为自定义仓库已验证")
    void customActiveKeyProjectsAsVerifiedCustom() {
        TrustedPluginKey key = key("custom-key", TrustedPluginKey.State.ACTIVE, false);

        PluginVerificationView view = PluginVerificationProjector.forCatalogPackage(
                repository(false, key), pkg(signature("custom-key")));

        assertThat(view.status()).isEqualTo(PluginVerificationProjector.VERIFIED_CUSTOM);
        assertThat(view.source()).isEqualTo("custom");
    }

    @Test
    @DisplayName("catalog 包：官方仓库不能用自定义 key 伪装官方可信来源")
    void officialRepositoryRejectsCustomKeyProjection() {
        TrustedPluginKey key = key("custom-key", TrustedPluginKey.State.ACTIVE, false);

        PluginVerificationView view = PluginVerificationProjector.forCatalogPackage(
                repository(true, key), pkg(signature("custom-key")));

        assertThat(view.status()).isEqualTo(PluginVerificationProjector.UNKNOWN_KEY);
        assertThat(view.diagnosticCode()).isEqualTo("OFFICIAL_KEY_REQUIRED");
    }

    @Test
    @DisplayName("catalog 包：退役 / 撤销 / 未知 key 均不投影为已验证")
    void inactiveOrUnknownKeyDoesNotProjectAsVerified() {
        TrustedPluginKey retired = key("retired-key", TrustedPluginKey.State.RETIRED, false);
        TrustedPluginKey revoked = key("revoked-key", TrustedPluginKey.State.REVOKED, false);
        PluginRepository repository = repository(false, retired, revoked);

        assertThat(PluginVerificationProjector.forCatalogPackage(repository, pkg(signature("retired-key"))).status())
                .isEqualTo(PluginVerificationProjector.UNKNOWN_KEY);
        assertThat(PluginVerificationProjector.forCatalogPackage(repository, pkg(signature("revoked-key"))).status())
                .isEqualTo(PluginVerificationProjector.REVOKED_KEY);
        assertThat(PluginVerificationProjector.forCatalogPackage(repository, pkg(signature("missing-key"))).status())
                .isEqualTo(PluginVerificationProjector.UNKNOWN_KEY);
    }

    @Test
    @DisplayName("catalog 包：缺签 / malformed / 非 Ed25519 不投影为已验证")
    void missingOrMalformedSignatureDoesNotProjectAsVerified() {
        TrustedPluginKey key = key("custom-key", TrustedPluginKey.State.ACTIVE, false);
        PluginRepository repository = repository(false, key);

        assertThat(PluginVerificationProjector.forCatalogPackage(repository, pkg(null)).status())
                .isEqualTo(PluginVerificationProjector.SIGNATURE_REQUIRED);
        assertThat(PluginVerificationProjector.forCatalogPackage(repository,
                pkg(new SignatureMetadata(2, SignatureMetadata.ED25519, "custom-key", "c2ln"))).status())
                .isEqualTo(PluginVerificationProjector.INVALID_SIGNATURE);
        assertThat(PluginVerificationProjector.forCatalogPackage(repository,
                pkg(new SignatureMetadata(1, "RSA", "custom-key", "c2ln"))).status())
                .isEqualTo(PluginVerificationProjector.INVALID_SIGNATURE);
    }

    @Test
    @DisplayName("provenance：离线 HASH_MISMATCH 优先覆盖安装时 VERIFIED")
    void offlineHashMismatchOverridesInstalledVerified() {
        PluginProvenanceRecord record = provenance(
                PluginPackageSource.MARKET_CATALOG, true,
                VerificationStatus.VERIFIED, VerificationStatus.HASH_MISMATCH, "SHA256_MISMATCH");

        PluginVerificationView view = PluginVerificationProjector.fromProvenance(record);

        assertThat(view.status()).isEqualTo(PluginVerificationProjector.HASH_MISMATCH);
        assertThat(view.source()).isEqualTo("official");
        assertThat(view.offlineReverifySuccess()).isFalse();
        assertThat(view.diagnosticCode()).isEqualTo("SHA256_MISMATCH");
        assertThat(view.keyId()).isEqualTo("test-key");
        assertThat(view.publisher()).isEqualTo("Test Publisher");
        assertThat(view.trustLabel()).isEqualTo("Test Trust");
        assertThat(view.lastVerifiedAt()).isEqualTo("2026-07-01T00:01:00Z");
    }

    @Test
    @DisplayName("provenance：离线 INVALID_SIGNATURE 优先覆盖安装时 VERIFIED")
    void offlineInvalidSignatureOverridesInstalledVerified() {
        PluginProvenanceRecord record = provenance(
                PluginPackageSource.MARKET_CATALOG, false,
                VerificationStatus.VERIFIED, VerificationStatus.INVALID_SIGNATURE, "INVALID_SIGNATURE");

        PluginVerificationView view = PluginVerificationProjector.fromProvenance(record);

        assertThat(view.status()).isEqualTo(PluginVerificationProjector.INVALID_SIGNATURE);
        assertThat(view.source()).isEqualTo("custom");
        assertThat(view.offlineReverifySuccess()).isFalse();
        assertThat(view.diagnosticCode()).isEqualTo("INVALID_SIGNATURE");
        assertThat(view.keyId()).isEqualTo("test-key");
        assertThat(view.publisher()).isEqualTo("Test Publisher");
        assertThat(view.trustLabel()).isEqualTo("Test Trust");
    }

    @Test
    @DisplayName("provenance：本地 unsigned 放行投影保持 UNSIGNED_ALLOWED")
    void localUnsignedAllowedProjectionRemainsUnchanged() {
        PluginProvenanceRecord record = provenance(
                PluginPackageSource.LOCAL_UPLOAD, false,
                VerificationStatus.UNSIGNED_ALLOWED, VerificationStatus.UNSIGNED_ALLOWED, "UNSIGNED_ALLOWED");

        PluginVerificationView view = PluginVerificationProjector.fromProvenance(record);

        assertThat(view.status()).isEqualTo(PluginVerificationProjector.UNSIGNED_ALLOWED);
        assertThat(view.source()).isEqualTo("local");
        assertThat(view.offlineReverifySuccess()).isTrue();
    }

    private static PluginCatalogPackage pkg(SignatureMetadata signature) {
        return new PluginCatalogPackage("1.0.0", "https://example.test/plugin.jar", 123L,
                "abcdef", signature, null, "1.0", List.of(), null, List.of(), "stable", false);
    }

    private static SignatureMetadata signature(String keyId) {
        return new SignatureMetadata(1, SignatureMetadata.ED25519, keyId, "c2ln");
    }

    private static TrustedPluginKey key(String keyId, TrustedPluginKey.State state, boolean official) {
        return new TrustedPluginKey(keyId, SignatureMetadata.ED25519, "public-key", state,
                "Publisher", "Trust Root", official);
    }

    private static PluginRepository repository(boolean official, TrustedPluginKey... keys) {
        return new PluginRepository(official ? "official" : "custom",
                official ? "plugin.market.repository.official.name" : "plugin.market.repository.custom.name",
                "https://example.test/manifest.json", true, official, official,
                RepositoryProxyPolicy.DIRECT_STRICT, RepositoryProxyPolicy.DIRECT_STRICT.configId(),
                false, true, false, false, 1000, 1000, 1024 * 1024, 1024 * 1024,
                List.of(keys));
    }

    private static PluginProvenanceRecord provenance(PluginPackageSource source, boolean official,
                                                     VerificationStatus installedStatus,
                                                     VerificationStatus offlineStatus,
                                                     String diagnosticCode) {
        boolean catalog = source == PluginPackageSource.MARKET_CATALOG;
        return new PluginProvenanceRecord(
                source,
                catalog ? "test-repository" : null,
                official,
                catalog ? 123L : null,
                catalog ? "abcdef" : null,
                catalog ? signature("test-key") : null,
                installedStatus,
                catalog ? "test-key" : null,
                catalog ? "Test Publisher" : null,
                catalog ? "Test Trust" : null,
                Instant.parse("2026-07-01T00:00:00Z"),
                offlineStatus,
                Instant.parse("2026-07-01T00:01:00Z"),
                diagnosticCode);
    }
}
