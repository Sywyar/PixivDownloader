package top.sywyar.pixivdownload.plugin.catalog.trust;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogProperties;
import top.sywyar.pixivdownload.plugin.catalog.repository.PluginRepositoryRegistry;
import top.sywyar.pixivdownload.plugin.catalog.security.PluginCatalogStrictJson;
import top.sywyar.pixivdownload.plugin.runtime.admission.PluginArtifactAdmissionRequest;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PluginCatalogRevocationAdmissionPolicyTest {

    @TempDir Path tempDir;

    @Test
    void rejectsRevokedArtifactAndPersistsHighestSequence() throws Exception {
        PluginCatalogProperties properties = new PluginCatalogProperties();
        PluginCatalogProperties.RepositoryConfig config = new PluginCatalogProperties.RepositoryConfig();
        config.setId("sample.repo");
        config.setManifestUrl("https://catalog.example/manifest.json");
        config.setRevocationsUrl("https://catalog.example/revocations.json");
        config.setPublisherId("sample");
        properties.setRepositories(List.of(config));
        PluginRepositoryRegistry repositories = new PluginRepositoryRegistry(properties);
        PluginCatalogTrustStateStore store = new PluginCatalogTrustStateStore(tempDir.resolve("trust.json"));
        store.acceptUpdateSequence("sample.repo", 3L);
        store.acceptUpdateSequence("sample.repo", 2L);
        store.acceptRevocations("sample.repo", new PluginCatalogTrustStateStore.RevocationSnapshot(
                5L, "a".repeat(64), Instant.now().minusSeconds(60).toString(),
                Instant.now().plusSeconds(3600).toString(), Instant.now().toString(), List.of(
                new PluginCatalogTrustStateStore.RevocationEntry("PACKAGE_SHA256", null, null,
                        "b".repeat(64), null, null, "REVOKED", "MALWARE", Instant.now().toString()))));

        var result = new PluginCatalogRevocationAdmissionPolicy(repositories, store).evaluate(
                new PluginArtifactAdmissionRequest("sample.repo", "demo", "1.0.0",
                        "b".repeat(64), "key-1", "Sample"));

        assertThat(result.allowed()).isFalse();
        assertThat(result.code()).isEqualTo("PLUGIN_REVOKED");
        assertThat(new PluginCatalogTrustStateStore(tempDir.resolve("trust.json")).updateSequence("sample.repo"))
                .isEqualTo(3L);
    }

    @Test
    void acceptsLiteralSha256WireNameAndIgnoresFutureRevocation() throws Exception {
        PluginRevocationDocument document = PluginCatalogStrictJson.mapper(true).readValue("""
                {"schemaVersion":1,"repositoryId":"sample.repo","sequence":1,
                 "generatedTime":"2026-01-01T00:00:00Z","nextUpdate":"2026-01-02T00:00:00Z",
                 "entries":[{"scope":"PACKAGE_SHA256","sha256":"%s","reasonCode":"MALWARE",
                 "effectiveTime":"2999-01-01T00:00:00Z"}]}
                """.formatted("b".repeat(64)), PluginRevocationDocument.class);

        assertThat(document.entries().get(0).packageSha256()).isEqualTo("b".repeat(64));
        var entry = new PluginCatalogTrustStateStore.RevocationEntry("PACKAGE_SHA256", null, null,
                document.entries().get(0).packageSha256(), null, null, "REVOKED", "MALWARE",
                document.entries().get(0).effectiveTime());
        assertThat(PluginCatalogRevocationService.matches(entry, null, "demo", "1.0.0",
                "b".repeat(64), null)).isFalse();
    }

    @Test
    void rejectsRevocationRollbackAndDegradesCorruptStateToEmpty() throws Exception {
        Path path = tempDir.resolve("trust.json");
        PluginCatalogTrustStateStore store = new PluginCatalogTrustStateStore(path);
        var accepted = new PluginCatalogTrustStateStore.RevocationSnapshot(
                5L, "a".repeat(64), Instant.now().minusSeconds(60).toString(),
                Instant.now().plusSeconds(3600).toString(), Instant.now().toString(), List.of());
        store.acceptRevocations("sample.repo", accepted);

        assertThatThrownBy(() -> store.acceptRevocations("sample.repo",
                new PluginCatalogTrustStateStore.RevocationSnapshot(
                        5L, "b".repeat(64), accepted.generatedTime(), accepted.nextUpdate(),
                        accepted.verifiedAt(), List.of())))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("rollback or equivocation");
        assertThat(store.revocations("sample.repo")).contains(accepted);

        Files.writeString(path, "not-json");
        assertThat(store.read().repositories()).isEmpty();
    }
}
