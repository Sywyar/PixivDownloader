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
