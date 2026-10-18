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
    @DisplayName("社区描述符仅在认证摘要未变时复用，撤回认证立即拒绝")
    void reusesCertifiedDescriptorWithoutCachingCertification() throws Exception {
        var repository = PluginRepository.community(true, 3000, 4000, 1048576, 2048);
        String url = repository.descriptorUrl();
        String spki = Base64.getEncoder().encodeToString(
                KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic().getEncoded());
        byte[] bytes = CommunityJson.encode(Map.of("schemaVersion", 1, "repositoryId", repository.repositoryId(),
                "displayName", "Sample", "publisher", Map.of("id", "sample", "displayName", "Sample"),
                "catalog", Map.of("protocol", "manifest-v1", "endpoint", repository.manifestUrl()),
                "revocationsUrl", repository.revocationsUrl(), "networkProfile", "GITHUB_RELEASES",
                "trustedKeys", List.of(Map.of("keyId", "test", "algorithm", "Ed25519",
                        "publicKeySpkiBase64", spki, "state", "ACTIVE", "publisher", "Sample", "trustLabel", "Community"))));
        var parsed = new RepositoryDescriptorParser().parse(url, bytes, true);
        var entry = new DirectoryEntry(repository.repositoryId(), url, parsed.descriptorSha256(), null, null, null,
                List.of(new DirectoryEntry.CertifiedKey("test", parsed.trustedKeys().get(0).publicKeyFingerprint())),
                DirectoryEntry.Status.IDENTITY_VERIFIED, null, null, 7, null, null);
        var directory = mock(CommunityDirectoryService.class);
        var client = mock(PluginCatalogHttpClient.class);
        when(client.fetchBytes(eq(url), anyLong())).thenReturn(bytes);
        when(directory.lookup(repository.repositoryId())).thenReturn(new CommunityDirectoryService.Lookup(7, entry, false));
        var service = new PluginRepositoryImportService(new PluginRepositoryRegistry(new PluginCatalogProperties()),
                ignored -> client, new PluginCatalogTrustStateStore(temp.resolve("trust.json")), directory);
        assertThat(service.authenticateCommunity(repository).descriptorSha256()).isEqualTo(parsed.descriptorSha256());
        assertThat(service.authenticateCommunity(repository).descriptorSha256()).isEqualTo(parsed.descriptorSha256());
        verify(client, times(1)).fetchBytes(eq(url), anyLong());
        verify(directory, times(2)).lookup(repository.repositoryId());
        when(directory.lookup(repository.repositoryId())).thenReturn(new CommunityDirectoryService.Lookup(8, null, false));
        assertThatThrownBy(() -> service.authenticateCommunity(repository)).isInstanceOf(RuntimeException.class);
    }

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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    @DisplayName("直接导入与草稿保存均在信任写入失败后恢复配置，重试提交同一已验签更新")
    void failedTrustWriteRestoresConfiguration(boolean draft) throws Exception {
        String url = "https://repo.example/repository.json";
        String proofUrl = "https://repo.example/update.json";
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var key = new PluginCatalogProperties.TrustedKeyConfig();
        key.setKeyId("test");
        key.setPublicKey(Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
        byte[] descriptor = CommunityJson.encode(Map.of("schemaVersion", 1, "repositoryId", "sample.repo",
                "displayName", "Sample", "publisher", Map.of("id", "sample", "displayName", "Sample"),
                "catalog", Map.of("protocol", "paged-v2", "endpoint", "https://repo.example/catalog"),
                "networkProfile", "DIRECT_STRICT", "trustedKeys", List.of(Map.of("keyId", "test",
                        "algorithm", "Ed25519", "publicKeySpkiBase64", key.getPublicKey(), "state", "ACTIVE",
                        "publisher", "Sample", "trustLabel", "User confirmed"))));
        var parsed = new RepositoryDescriptorParser().parse(url, descriptor);
        var repository = new PluginCatalogProperties.RepositoryConfig();
        repository.setId("sample.repo");
        repository.setManifestUrl("https://repo.example/old");
        repository.setDescriptorUrl(url);
        repository.setDescriptorSha256("ab".repeat(32));
        repository.setUpdateProofUrl(proofUrl);
        repository.setTrustedKeys(List.of(key));
        var properties = new PluginCatalogProperties();
        properties.setRepositories(List.of(repository));
        byte[] proof = CommunityJson.encode(new RepositoryUpdateDocument(1, repository.getId(), 4L,
                repository.getDescriptorSha256(), url, parsed.descriptorSha256(),
                List.of("sha256:" + parsed.trustedKeys().get(0).publicKeyFingerprint()), "test"));
        var signer = java.security.Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update(top.sywyar.pixivdownload.plugin.signature.internal.envelope.EnvelopeV1Codec.repositoryUpdateMessage(
                repository.getId(), 4L, proof.length, java.security.MessageDigest.getInstance("SHA-256").digest(proof)));
        byte[] signature = CommunityJson.encode(new top.sywyar.pixivdownload.plugin.signature.SignatureMetadata(
                1, "Ed25519", "test", Base64.getEncoder().encodeToString(signer.sign())));
        var client = mock(PluginCatalogHttpClient.class);
        when(client.fetchBytes(eq(proofUrl), anyLong())).thenReturn(proof);
        when(client.fetchBytes(eq(proofUrl + ".sig"), anyLong())).thenReturn(signature);
        Path trustPath = temp.resolve("trust.json");
        var store = spy(new PluginCatalogTrustStateStore(trustPath));
        store.acceptUpdateSequences(Map.of(repository.getId(), 3L));
        byte[] previousTrust = java.nio.file.Files.readAllBytes(trustPath);
        var service = new PluginRepositoryImportService(new PluginRepositoryRegistry(properties), ignored -> client, store);
        String old = System.getProperty(RuntimeFiles.CONFIG_DIR_PROPERTY);
        System.setProperty(RuntimeFiles.CONFIG_DIR_PROPERTY, temp.resolve("config").toString());
        try (var construction = mockConstruction(PluginCatalogHttpClient.class, (created, context) ->
                when(created.fetchBytes(eq(url), anyLong())).thenReturn(descriptor))) {
            Path path = RuntimeFiles.resolveConfigYamlPath();
            java.nio.file.Files.createDirectories(path.getParent());
            java.nio.file.Files.write(path, List.of("# retained", "app.language: en", "plugin-catalog.repositories:"),
                    java.nio.charset.StandardCharsets.UTF_8);
            var config = new top.sywyar.pixivdownload.gui.config.ConfigFileEditor(path).asConfigFile();
            var entry = service.prepare(url, parsed.descriptorSha256(), true);
            byte[] before = java.nio.file.Files.readAllBytes(path);
            doThrow(new java.io.IOException("trust state write failed")).when(store).acceptUpdateSequences(anyMap());
            assertThatThrownBy(() -> {
                if (draft) service.writeDraft(config, List.of(entry));
                else service.trust(url, parsed.descriptorSha256(), true);
            }).isInstanceOf(draft ? java.io.IOException.class
                    : top.sywyar.pixivdownload.plugin.catalog.error.PluginCatalogException.class);
            assertThat(java.nio.file.Files.readAllBytes(path)).isEqualTo(before);
            assertThat(java.nio.file.Files.readAllBytes(trustPath)).isEqualTo(previousTrust);
            doCallRealMethod().when(store).acceptUpdateSequences(anyMap());
            if (draft) service.writeDraft(config, List.of(entry));
            else service.trust(url, parsed.descriptorSha256(), true);
            assertThat(new PluginRepositoryConfigEditor(config).read()).containsExactly(entry);
            assertThat(new PluginCatalogTrustStateStore(trustPath).updateSequence(repository.getId())).isEqualTo(4L);
            assertThat(config.snapshot().lines()).contains("# retained", "app.language: en");
        } finally {
            if (old == null) System.clearProperty(RuntimeFiles.CONFIG_DIR_PROPERTY);
            else System.setProperty(RuntimeFiles.CONFIG_DIR_PROPERTY, old);
        }
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
