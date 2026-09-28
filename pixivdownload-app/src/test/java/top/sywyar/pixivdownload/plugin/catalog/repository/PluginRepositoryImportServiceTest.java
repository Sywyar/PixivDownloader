package top.sywyar.pixivdownload.plugin.catalog.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.gui.config.PluginRepositoryConfigEditor;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogHttpClient;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogProperties;
import top.sywyar.pixivdownload.plugin.catalog.community.CommunityDirectoryService;
import top.sywyar.pixivdownload.plugin.catalog.trust.PluginCatalogTrustStateStore;
import top.sywyar.pixivdownload.sdk.community.directory.DirectoryEntry;
import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;

import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("目录认证接入既有仓库预览及配置保存链")
class PluginRepositoryImportServiceTest {
    @TempDir Path temp;

    @Test
    void confirmedDraftDefersWritesAndRechecksBytesBeforeSaving() throws Exception {
        String url = "https://repo.example/repository.json";
        String spki = Base64.getEncoder().encodeToString(
                KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic().getEncoded());
        byte[] bytes = CommunityJson.encode(Map.of("schemaVersion", 1, "repositoryId", "sample.repo",
                "displayName", "Sample", "publisher", Map.of("id", "sample", "displayName", "Sample"),
                "catalog", Map.of("protocol", "paged-v2", "endpoint", "https://repo.example/catalog"),
                "networkProfile", "DIRECT_STRICT", "trustedKeys", List.of(Map.of("keyId", "test",
                        "algorithm", "Ed25519", "publicKeySpkiBase64", spki, "state", "ACTIVE",
                        "publisher", "Sample", "trustLabel", "User confirmed"))));
        var remote = new java.util.concurrent.atomic.AtomicReference<>(bytes);
        var trustState = spy(new PluginCatalogTrustStateStore(temp.resolve("trust.json")));
        var service = new PluginRepositoryImportService(new PluginRepositoryRegistry(new PluginCatalogProperties()),
                ignored -> mock(PluginCatalogHttpClient.class), trustState);
        var config = mock(top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost.ConfigFile.class);
        var disk = new java.util.concurrent.atomic.AtomicReference<>(
                new top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost.ConfigSnapshot(true,
                        List.of("# retained", "app.language: zh-CN", "plugin-catalog.repositories:")));
        when(config.snapshot()).thenAnswer(ignored -> disk.get());
        doAnswer(call -> { disk.set(call.getArgument(0)); return null; }).when(config).restore(any());
        var original = disk.get();
        try (var construction = mockConstruction(PluginCatalogHttpClient.class, (client, context) ->
                when(client.fetchBytes(eq(url), anyLong())).thenAnswer(ignored -> remote.get()))) {
            var preview = service.preview(url);
            assertThatThrownBy(() -> service.prepare(url, preview.descriptorSha256(), false))
                    .isInstanceOf(top.sywyar.pixivdownload.plugin.catalog.error.PluginCatalogException.class);
            var draft = service.prepare(url, preview.descriptorSha256(), true);
            assertThat(disk.get()).isSameAs(original);
            assertThat(java.nio.file.Files.exists(temp.resolve("trust.json"))).isFalse();
            remote.set((new String(bytes, java.nio.charset.StandardCharsets.UTF_8) + " ")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertThatThrownBy(() -> service.writeDraft(config, List.of(draft)))
                    .isInstanceOf(top.sywyar.pixivdownload.plugin.catalog.error.PluginCatalogException.class);
            assertThat(disk.get()).isSameAs(original);
            remote.set(bytes);
            var manual = top.sywyar.pixivdownload.plugin.api.gui.RepositoryConfigEntry.create(
                    "another.repo", "", "https://another.example/manifest.json", true, "direct-strict", 0, 0, 0, 0);
            doThrow(new java.io.IOException("trust state write failed")).when(trustState).acceptUpdateSequences(anyMap());
            assertThatThrownBy(() -> service.writeDraft(config, List.of(manual, draft)))
                    .isInstanceOf(java.io.IOException.class);
            assertThat(disk.get()).isSameAs(original);
            doCallRealMethod().when(trustState).acceptUpdateSequences(anyMap());
            service.writeDraft(config, List.of(manual, draft));
            assertThat(new PluginRepositoryConfigEditor(config).read()).containsExactly(manual, draft);
            assertThat(disk.get().lines()).contains("# retained", "app.language: zh-CN");
            verify(config, times(3)).restore(any());
        }
    }

    @Test
    void updateSequencesAreWrittenTogetherAndInvalidBatchPreservesSnapshot() throws Exception {
        Path path = temp.resolve("trust.json");
        var store = new PluginCatalogTrustStateStore(path);
        store.acceptUpdateSequences(Map.of("first", 7L, "second", 4L));
        store.acceptUpdateSequences(Map.of("first", 3L, "second", 5L));
        assertThat(store.updateSequence("first")).isEqualTo(7L);
        assertThat(store.updateSequence("second")).isEqualTo(5L);
        byte[] before = java.nio.file.Files.readAllBytes(path);
        var invalid = new java.util.LinkedHashMap<String, Long>();
        invalid.put("first", 8L);
        invalid.put("", 9L);
        assertThatThrownBy(() -> store.acceptUpdateSequences(invalid)).isInstanceOf(java.io.IOException.class);
        assertThat(java.nio.file.Files.readAllBytes(path)).isEqualTo(before);
    }

    @Test
    @DisplayName("精确认证允许描述符更新，撤回认证后仍要求旧仓库连续性证明")
    void certifiedUpdateUsesExistingImportChain() throws Exception {
        String url = "https://repo.example/repository.json";
        String spki = Base64.getEncoder().encodeToString(
                KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic().getEncoded());
        byte[] bytes = CommunityJson.encode(Map.of("schemaVersion", 1, "repositoryId", "sample.repo",
                "displayName", "Sample", "publisher", Map.of("id", "sample", "displayName", "Sample"),
                "catalog", Map.of("protocol", "paged-v2", "endpoint", "https://repo.example/catalog"),
                "networkProfile", "DIRECT_STRICT", "trustedKeys", List.of(Map.of("keyId", "test",
                        "algorithm", "Ed25519", "publicKeySpkiBase64", spki, "state", "ACTIVE",
                        "publisher", "Sample", "trustLabel", "User confirmed"))));
        var parsed = new RepositoryDescriptorParser().parse(url, bytes);
        var config = new PluginCatalogProperties.RepositoryConfig();
        config.setId("sample.repo");
        config.setManifestUrl("https://repo.example/old");
        config.setDescriptorUrl(url);
        config.setDescriptorSha256("ab".repeat(32));
        var properties = new PluginCatalogProperties();
        properties.setRepositories(List.of(config));
        var registry = new PluginRepositoryRegistry(properties);
        var directory = mock(CommunityDirectoryService.class);
        var entry = new DirectoryEntry("sample.repo", url, parsed.descriptorSha256(), null, null, null,
                List.of(new DirectoryEntry.CertifiedKey("test", parsed.trustedKeys().get(0).publicKeyFingerprint())),
                DirectoryEntry.Status.IDENTITY_VERIFIED, null, null, 7, null, null);
        when(directory.lookup("sample.repo")).thenReturn(new CommunityDirectoryService.Lookup(7, entry, false));
        var service = new PluginRepositoryImportService(registry, ignored -> mock(PluginCatalogHttpClient.class),
                new PluginCatalogTrustStateStore(temp.resolve("trust.json")), directory);
        String old = System.getProperty(RuntimeFiles.CONFIG_DIR_PROPERTY);
        System.setProperty(RuntimeFiles.CONFIG_DIR_PROPERTY, temp.resolve("config").toString());
        try (var construction = mockConstruction(PluginCatalogHttpClient.class, (client, context) ->
                when(client.fetchBytes(eq(url), anyLong())).thenReturn(bytes))) {
            var preview = service.preview(url);
            assertThat(preview.communityDirectoryStatus()).isEqualTo("IDENTITY_VERIFIED");
            assertThat(preview.updateProofStatus()).isEqualTo("COMMUNITY_VERIFIED");
            assertThatThrownBy(() -> service.trust(url, "cd".repeat(32), true)).isInstanceOf(RuntimeException.class);
            assertThat(service.trust(url, preview.descriptorSha256(), true).trustSource()).isEqualTo("COMMUNITY_VERIFIED");
            assertThat(new PluginRepositoryConfigEditor(RuntimeFiles.resolveConfigYamlPath()).read())
                    .singleElement().satisfies(saved -> assertThat(saved.id()).isEqualTo("sample.repo"));
            when(directory.lookup("sample.repo")).thenReturn(new CommunityDirectoryService.Lookup(8, null, false));
            assertThat(service.preview(url).communityDirectoryStatus()).isEqualTo("REMOVED");
            assertThatThrownBy(() -> service.trust(url, preview.descriptorSha256(), true))
                    .isInstanceOf(top.sywyar.pixivdownload.plugin.catalog.error.PluginCatalogException.class);
        } finally {
            if (old == null) System.clearProperty(RuntimeFiles.CONFIG_DIR_PROPERTY);
            else System.setProperty(RuntimeFiles.CONFIG_DIR_PROPERTY, old);
        }
    }
}
