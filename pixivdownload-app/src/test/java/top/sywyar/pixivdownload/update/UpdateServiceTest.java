package top.sywyar.pixivdownload.update;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogHttpClient;
import top.sywyar.pixivdownload.plugin.catalog.repository.PluginCatalogClientProvider;
import top.sywyar.pixivdownload.plugin.signature.ManifestVerificationRequest;
import top.sywyar.pixivdownload.plugin.signature.OfficialPluginTrustRoots;
import top.sywyar.pixivdownload.plugin.signature.PluginSupplyChainVerifier;
import top.sywyar.pixivdownload.plugin.signature.PluginTrustStores;
import top.sywyar.pixivdownload.plugin.signature.SignatureMetadata;
import top.sywyar.pixivdownload.plugin.signature.TrustedPluginKey;
import top.sywyar.pixivdownload.plugin.signature.VerificationPolicy;
import top.sywyar.pixivdownload.plugin.signature.VerificationStatus;
import top.sywyar.pixivdownload.plugin.signature.internal.envelope.EnvelopeV1Codec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("UpdateService 单元测试")
class UpdateServiceTest {

    private static final AppMessages APP_MESSAGES = TestI18nBeans.appMessages();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String MANIFEST_URL = "https://example.com/update.json";
    private static final String ASSET_SHA256 = "0".repeat(64);

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Spring 明确选择生产构造器装配更新服务")
    void shouldWireProductionConstructorInSpringContext() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(UpdateConfig.class, UpdateServiceTest::config);
            context.registerBean(AppMessages.class, () -> APP_MESSAGES);
            context.registerBean(PluginCatalogClientProvider.class, () -> mock(PluginCatalogClientProvider.class));
            context.registerBean(UpdateService.class);
            context.refresh();

            assertThat(context.getBean(UpdateService.class)).isNotNull();
        }
    }

    @Test
    @DisplayName("生产更新信任根与官方插件信任根相互隔离")
    void productionUpdateTrustRootIsIsolatedFromPluginRoot() {
        byte[] manifest = "{}".getBytes(StandardCharsets.UTF_8);
        String invalidSignature = Base64.getEncoder().encodeToString(new byte[64]);
        SignatureMetadata updateSignature = new SignatureMetadata(
                SignatureMetadata.FORMAT_VERSION, SignatureMetadata.ED25519,
                UpdateService.UPDATE_SIGNING_KEY_ID, invalidSignature);
        SignatureMetadata pluginSignature = new SignatureMetadata(
                SignatureMetadata.FORMAT_VERSION, SignatureMetadata.ED25519,
                OfficialPluginTrustRoots.OFFICIAL_KEY_ID, invalidSignature);

        assertThat(UpdateService.updateManifestVerifier().verifyManifest(new ManifestVerificationRequest(
                manifest, UpdateService.UPDATE_MANIFEST_REPOSITORY_ID,
                updateSignature, VerificationPolicy.officialRepository())).status())
                .isEqualTo(VerificationStatus.INVALID_SIGNATURE);
        assertThat(UpdateService.updateManifestVerifier().verifyManifest(new ManifestVerificationRequest(
                manifest, UpdateService.UPDATE_MANIFEST_REPOSITORY_ID,
                pluginSignature, VerificationPolicy.officialRepository())).status())
                .isEqualTo(VerificationStatus.UNKNOWN_KEY);
        assertThat(new PluginSupplyChainVerifier().verifyManifest(new ManifestVerificationRequest(
                manifest, UpdateService.UPDATE_MANIFEST_REPOSITORY_ID,
                updateSignature, VerificationPolicy.officialRepository())).status())
                .isEqualTo(VerificationStatus.UNKNOWN_KEY);
    }

    @Test
    @DisplayName("已签名 manifest 按 UTF-8 解析并保存防回滚高水位")
    void shouldVerifyAndParseManifestAsUtf8Bytes() throws Exception {
        SigningSupport signing = new SigningSupport();
        String releaseNotes = "本次更新：修复了若干问题，新增「听书」功能。";
        byte[] manifest = manifest(10, "stable", "999.0.0", "2099-01-01T00:00:00Z",
                releaseNotes, ASSET_SHA256);
        UpdateService service = service(signing, manifest, signing.signature(manifest), tempDir.resolve("trust.json"));

        UpdateCheckResult result = service.checkForUpdate(true);

        assertThat(result.isCheckSucceeded()).isTrue();
        assertThat(result.getLatestVersion()).isEqualTo("999.0.0");
        assertThat(result.getReleaseNotes()).isEqualTo(releaseNotes);
        assertThat(tempDir.resolve("trust.json")).exists();
    }

    @Test
    @DisplayName("缺少 detached 签名时拒绝更新清单")
    void shouldRejectMissingSignature() throws Exception {
        SigningSupport signing = new SigningSupport();
        byte[] manifest = manifest(10, "stable", "999.0.0", "2099-01-01T00:00:00Z",
                "notes", ASSET_SHA256);
        UpdateCheckResult result = service(signing, manifest, new byte[0], tempDir.resolve("trust.json"))
                .checkForUpdate(true);

        assertThat(result.isCheckSucceeded()).isFalse();
        assertThat(result.isUpdateAvailable()).isFalse();
    }

    @Test
    @DisplayName("缺少资产哈希或大小、清单已过期时 fail closed")
    void shouldRejectInvalidIntegrityMetadataAndExpiry() throws Exception {
        SigningSupport signing = new SigningSupport();
        byte[] missingHash = manifest(10, "stable", "999.0.0", "2099-01-01T00:00:00Z",
                "notes", "");
        byte[] missingSize = manifest(11, "stable", "999.0.1", "2099-01-01T00:00:00Z",
                "notes", ASSET_SHA256, 0);
        byte[] expired = manifest(12, "stable", "999.0.2", "2000-01-01T00:00:00Z",
                "notes", ASSET_SHA256);

        assertThat(service(signing, missingHash, signing.signature(missingHash), tempDir.resolve("missing.json"))
                .checkForUpdate(true).isCheckSucceeded()).isFalse();
        assertThat(service(signing, missingSize, signing.signature(missingSize), tempDir.resolve("missing-size.json"))
                .checkForUpdate(true).isCheckSucceeded()).isFalse();
        assertThat(service(signing, expired, signing.signature(expired), tempDir.resolve("expired.json"))
                .checkForUpdate(true).isCheckSucceeded()).isFalse();
    }

    @Test
    @DisplayName("损坏的持久化高水位使更新检查 fail closed")
    void shouldRejectCorruptTrustState() throws Exception {
        SigningSupport signing = new SigningSupport();
        Path state = tempDir.resolve("trust.json");
        Files.writeString(state, "{\"stableSequence\":1,\"nightlySequence\":0}", StandardCharsets.UTF_8);
        byte[] manifest = manifest(10, "stable", "999.0.0", "2099-01-01T00:00:00Z",
                "notes", ASSET_SHA256);

        UpdateCheckResult result = service(signing, manifest, signing.signature(manifest), state)
                .checkForUpdate(true);

        assertThat(result.isCheckSucceeded()).isFalse();
        assertThat(result.getError()).contains("invalid stable update trust state");
    }

    @Test
    @DisplayName("持久化高水位拒绝旧序号和同版本替换")
    void shouldRejectRollbackAndSameVersionReplacement() throws Exception {
        SigningSupport signing = new SigningSupport();
        Path state = tempDir.resolve("trust.json");
        byte[] first = manifest(20, "stable", "999.0.0", "2099-01-01T00:00:00Z",
                "first", ASSET_SHA256);
        assertThat(service(signing, first, signing.signature(first), state)
                .checkForUpdate(true).isCheckSucceeded()).isTrue();

        byte[] rollback = manifest(19, "stable", "998.0.0", "2099-01-01T00:00:00Z",
                "old", ASSET_SHA256);
        UpdateCheckResult rollbackResult = service(signing, rollback, signing.signature(rollback), state)
                .checkForUpdate(true);
        assertThat(rollbackResult.isCheckSucceeded()).isFalse();
        assertThat(rollbackResult.getError()).contains("rollback rejected");

        byte[] replacement = manifest(21, "stable", "999.0.0", "2099-01-01T00:00:00Z",
                "replaced", ASSET_SHA256);
        UpdateCheckResult replacementResult = service(signing, replacement, signing.signature(replacement), state)
                .checkForUpdate(true);
        assertThat(replacementResult.isCheckSucceeded()).isFalse();
        assertThat(replacementResult.getError()).contains("replaced with different content");
    }

    @Test
    @DisplayName("HTTP 更新清单 URL 在联网前被拒绝")
    void shouldRejectHttpManifestBeforeNetwork() {
        UpdateConfig config = config();
        config.setManifestUrl("http://example.com/update.json");
        PluginCatalogClientProvider provider = mock(PluginCatalogClientProvider.class);
        UpdateService service = new UpdateService(config, APP_MESSAGES, provider);

        assertThat(service.checkForUpdate(true).isCheckSucceeded()).isFalse();
        verifyNoInteractions(provider);
    }

    @Test
    @DisplayName("安装包实际大小不符时删除临时文件并拒绝下载")
    void shouldRejectDownloadedInstallerSizeMismatch() throws Exception {
        String oldOsName = System.getProperty("os.name");
        String oldOsArch = System.getProperty("os.arch");
        String version = "999.0.3-size-mismatch-test";
        Path part = Path.of("update-cache", "PixivDownload-" + version + "-win-x64-setup.exe.part");
        try {
            System.setProperty("os.name", "Windows 11");
            System.setProperty("os.arch", "amd64");
            SigningSupport signing = new SigningSupport();
            byte[] manifest = manifest(30, "stable", version, "2099-01-01T00:00:00Z",
                    "notes", ASSET_SHA256);
            PluginCatalogHttpClient client = mock(PluginCatalogHttpClient.class);
            when(client.fetchBytes(MANIFEST_URL, 1024L * 1024L)).thenReturn(manifest);
            when(client.fetchBytes(MANIFEST_URL + ".sig", 16L * 1024L)).thenReturn(signing.signature(manifest));
            doAnswer(invocation -> {
                Path target = invocation.getArgument(2);
                Files.write(target, new byte[]{1, 2});
                invocation.<LongConsumer>getArgument(3).accept(2);
                return 2L;
            }).when(client).streamToFile(anyString(), anyLong(), any(Path.class), any(LongConsumer.class));
            PluginCatalogClientProvider provider = mock(PluginCatalogClientProvider.class);
            when(provider.clientFor(any())).thenReturn(client);
            UpdateService service = new UpdateService(
                    config(), APP_MESSAGES, signing.verifier(), tempDir.resolve("trust.json"), provider);

            assertThat(service.checkForUpdate(true).isUpdateAvailable()).isTrue();
            assertThatThrownBy(() -> service.downloadInstaller(false))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("size mismatch");
            assertThat(part).doesNotExist();
        } finally {
            restoreProperty("os.name", oldOsName);
            restoreProperty("os.arch", oldOsArch);
            Files.deleteIfExists(part);
        }
    }

    @Test
    @DisplayName("异步下载固定启动时选中的更新对象")
    void shouldKeepSelectedUpdateSnapshotForAsyncDownload() throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        UpdateCheckResult selected = UpdateCheckResult.builder()
                .updateAvailable(true)
                .latestVersion("999.0.4-selected")
                .assetUrl("https://example.com/selected.exe")
                .assetSha256(ASSET_SHA256)
                .assetSizeBytes(1)
                .build();
        UpdateCheckResult replacement = UpdateCheckResult.builder()
                .updateAvailable(true)
                .latestVersion("999.0.5-replacement")
                .assetUrl("https://example.com/replacement.exe")
                .assetSha256(ASSET_SHA256)
                .assetSizeBytes(1)
                .build();
        java.util.concurrent.atomic.AtomicReference<UpdateCheckResult> captured =
                new java.util.concurrent.atomic.AtomicReference<>();
        UpdateService service = new UpdateService(config(), APP_MESSAGES, mock(PluginCatalogClientProvider.class)) {
            @Override
            UpdateDownloadResult downloadInstaller(UpdateCheckResult check) throws IOException {
                captured.set(check);
                received.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IOException("timed out waiting for test release");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
                return UpdateDownloadResult.builder().version(check.getLatestVersion()).build();
            }
        };
        var lastResult = UpdateService.class.getDeclaredField("lastResult");
        lastResult.setAccessible(true);
        lastResult.set(service, selected);

        service.startDownloadAsync(false);
        assertThat(received.await(5, TimeUnit.SECONDS)).isTrue();
        lastResult.set(service, replacement);
        release.countDown();

        assertThat(captured.get()).isSameAs(selected);
    }

    @Test
    @DisplayName("安装启动仅使用服务端已验证文件并在启动前重验大小和哈希")
    void shouldBindInstallToVerifiedDownloadSnapshot() throws Exception {
        String oldOsName = System.getProperty("os.name");
        String oldOsArch = System.getProperty("os.arch");
        byte[] payload = {1, 2, 3};
        String hash = java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(payload));
        String version = "999.0.4-verified-installer-test";
        Path target = Path.of("update-cache", "PixivDownload-" + version + "-win-x64-setup.exe");
        try {
            System.setProperty("os.name", "Windows 11");
            System.setProperty("os.arch", "amd64");
            PluginCatalogHttpClient client = mock(PluginCatalogHttpClient.class);
            doAnswer(invocation -> {
                Path output = invocation.getArgument(2);
                Files.write(output, payload);
                invocation.<LongConsumer>getArgument(3).accept(payload.length);
                return (long) payload.length;
            }).when(client).streamToFile(anyString(), anyLong(), any(Path.class), any(LongConsumer.class));
            PluginCatalogClientProvider provider = mock(PluginCatalogClientProvider.class);
            when(provider.clientFor(any())).thenReturn(client);
            UpdateService service = new UpdateService(config(), APP_MESSAGES, provider);
            UpdateCheckResult selected = UpdateCheckResult.builder()
                    .updateAvailable(true)
                    .latestVersion(version)
                    .assetUrl("https://example.com/" + target.getFileName())
                    .assetSha256(hash)
                    .assetSizeBytes(payload.length)
                    .nightly(false)
                    .build();

            service.downloadInstaller(selected);
            assertThat(service.verifiedInstallerForLaunch()).isEqualTo(target.toAbsolutePath().normalize());

            Files.write(target, new byte[]{9, 9, 9});
            assertThatThrownBy(service::verifiedInstallerForLaunch)
                    .isInstanceOf(IOException.class);
        } finally {
            restoreProperty("os.name", oldOsName);
            restoreProperty("os.arch", oldOsArch);
            Files.deleteIfExists(target);
        }
    }

    @Test
    @DisplayName("update.enabled=false 时不联网，直接返回未启用结果")
    void shouldShortCircuitWhenDisabled() {
        UpdateConfig config = new UpdateConfig();
        config.setEnabled(false);
        PluginCatalogClientProvider provider = mock(PluginCatalogClientProvider.class);
        UpdateService service = new UpdateService(config, APP_MESSAGES, provider);

        UpdateCheckResult result = service.checkForUpdate(true);

        assertThat(result.isEnabled()).isFalse();
        assertThat(result.isCheckSucceeded()).isFalse();
        assertThat(result.isUpdateAvailable()).isFalse();
        verifyNoInteractions(provider);
    }

    private UpdateService service(SigningSupport signing, byte[] manifest, byte[] signature, Path state) {
        PluginCatalogHttpClient client = mock(PluginCatalogHttpClient.class);
        when(client.fetchBytes(MANIFEST_URL, 1024L * 1024L)).thenReturn(manifest);
        when(client.fetchBytes(MANIFEST_URL + ".sig", 16L * 1024L)).thenReturn(signature);
        PluginCatalogClientProvider provider = mock(PluginCatalogClientProvider.class);
        when(provider.clientFor(any())).thenReturn(client);
        return new UpdateService(config(), APP_MESSAGES, signing.verifier(), state, provider);
    }

    private static UpdateConfig config() {
        UpdateConfig config = new UpdateConfig();
        config.setEnabled(true);
        config.setManifestUrl(MANIFEST_URL);
        config.setCheckNightly(false);
        return config;
    }

    private static byte[] manifest(long sequence, String channel, String version, String expiresAt,
                                   String releaseNotes, String sha256) {
        return manifest(sequence, channel, version, expiresAt, releaseNotes, sha256, 1);
    }

    private static byte[] manifest(long sequence, String channel, String version, String expiresAt,
                                   String releaseNotes, String sha256, long sizeBytes) {
        String json = """
                {
                  "channel": "%s",
                  "sequence": %d,
                  "expiresAt": "%s",
                  "latestVersion": "%s",
                  "releaseDate": "2026-05-22",
                  "releaseNotes": "%s",
                  "releaseNotesUrl": "https://example.com/notes",
                  "assets": {
                    "win-x64-installer": {
                      "url": "https://example.com/PixivDownload-%s-win-x64-setup.exe",
                      "sha256": "%s",
                      "sizeBytes": %d
                    }
                  }
                }
                """.formatted(channel, sequence, expiresAt, version, releaseNotes, version, sha256, sizeBytes);
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private static final class SigningSupport {
        private final String keyId = "test-update-key";
        private final KeyPair keyPair;
        private final PluginSupplyChainVerifier verifier;

        private SigningSupport() throws Exception {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(SignatureMetadata.ED25519);
            keyPair = generator.generateKeyPair();
            TrustedPluginKey key = new TrustedPluginKey(
                    keyId,
                    SignatureMetadata.ED25519,
                    Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()),
                    TrustedPluginKey.State.ACTIVE,
                    "test",
                    "test update root",
                    true);
            verifier = new PluginSupplyChainVerifier(PluginTrustStores.of(java.util.List.of(key)));
        }

        private PluginSupplyChainVerifier verifier() {
            return verifier;
        }

        private byte[] signature(byte[] manifest) throws Exception {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(manifest);
            byte[] message = EnvelopeV1Codec.manifestMessage(
                    UpdateService.UPDATE_MANIFEST_REPOSITORY_ID, manifest.length, digest);
            Signature signer = Signature.getInstance(SignatureMetadata.ED25519);
            signer.initSign(keyPair.getPrivate());
            signer.update(message);
            SignatureMetadata metadata = new SignatureMetadata(
                    SignatureMetadata.FORMAT_VERSION,
                    SignatureMetadata.ED25519,
                    keyId,
                    Base64.getEncoder().encodeToString(signer.sign()));
            return MAPPER.writeValueAsBytes(metadata);
        }
    }
}
