package top.sywyar.pixivdownload.config.credential;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.config.RuntimeFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("安装实例凭据密钥保护与旧信封迁移")
class InstallationCredentialKeysTest {
    @TempDir Path temporary;
    private String originalConfig;
    private String originalSecret;

    @BeforeEach
    void configure() throws Exception {
        originalConfig = System.getProperty(RuntimeFiles.CONFIG_DIR_PROPERTY);
        originalSecret = System.getProperty(CredentialKeyProtection.KEY_FILE_PROPERTY);
        System.setProperty(RuntimeFiles.CONFIG_DIR_PROPERTY, temporary.resolve("config").toString());
        Path secret = temporary.resolve("external-secret");
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        Files.writeString(secret, Base64.getEncoder().encodeToString(bytes), StandardCharsets.US_ASCII);
        Arrays.fill(bytes, (byte) 0);
        System.setProperty(CredentialKeyProtection.KEY_FILE_PROPERTY, secret.toString());
    }

    @AfterEach
    void restore() {
        restoreProperty(RuntimeFiles.CONFIG_DIR_PROPERTY, originalConfig);
        restoreProperty(CredentialKeyProtection.KEY_FILE_PROPERTY, originalSecret);
    }

    @Test
    @DisplayName("同一外部保护密钥下不同安装仍使用独立随机主密钥，重建存储对象不换钥")
    void isolatesInstallationsAndSurvivesRestart() throws Exception {
        var first = new PluginCredentialStore();
        first.update("fixture", Map.of("fixture.token", "synthetic-secret"));
        byte[] envelope = Files.readAllBytes(RuntimeFiles.peekPluginCredentialPath("fixture"));
        byte[] savedKey = Files.readAllBytes(RuntimeFiles.pluginCredentialKeyPath());
        assertThat(new PluginCredentialStore().readAll("fixture")).containsEntry("fixture.token", "synthetic-secret");
        assertThat(Files.readAllBytes(RuntimeFiles.pluginCredentialKeyPath())).isEqualTo(savedKey);
        assertThat(new String(savedKey, StandardCharsets.ISO_8859_1)).doesNotContain("synthetic-secret");
        assertThatThrownBy(() -> PluginCredentialCipher.legacy(PluginCredentialKeyMaterial.loadLegacy())
                .decode("fixture", envelope)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> new PluginCredentialCipher(PluginCredentialKeyMaterial.loadLegacy())
                .decode("fixture", envelope)).isInstanceOf(IOException.class).hasMessageContaining("Unknown plugin credential key id");

        System.setProperty(RuntimeFiles.CONFIG_DIR_PROPERTY, temporary.resolve("another").toString());
        var second = new PluginCredentialStore();
        second.update("fixture", Map.of("fixture.token", "synthetic-secret"));
        String secondEnvelope = Files.readString(RuntimeFiles.peekPluginCredentialPath("fixture"));
        assertThat(secondEnvelope.lines().filter(line -> line.startsWith("key-id=")).findFirst())
                .isNotEqualTo(new String(envelope, StandardCharsets.UTF_8).lines().filter(line -> line.startsWith("key-id=")).findFirst());
        Files.write(RuntimeFiles.peekPluginCredentialPath("fixture"), envelope);
        assertThatThrownBy(() -> second.readAll("fixture")).isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("只读旧凭据预览不落盘，普通读取迁移全部 owner 并拒绝旧格式重放")
    void migratesAllOwnersAndClosesLegacyReader() throws Exception {
        byte[] first = legacy("fixture", "old-secret");
        legacy("disabled-owner", "retained-secret");
        var store = new PluginCredentialStore();
        assertThat(store.readForTransfer("fixture")).containsEntry("fixture.token", "old-secret");
        assertThat(RuntimeFiles.pluginCredentialKeyPath()).doesNotExist();
        assertThat(Files.readAllBytes(RuntimeFiles.peekPluginCredentialPath("fixture"))).isEqualTo(first);

        assertThat(store.readAll("fixture")).containsEntry("fixture.token", "old-secret");
        assertThat(store.readAll("disabled-owner")).containsEntry("disabled-owner.token", "retained-secret");
        assertThat(Files.readString(RuntimeFiles.peekPluginCredentialPath("disabled-owner")))
                .contains("format=" + PluginCredentialCipher.FORMAT);
        Files.write(RuntimeFiles.peekPluginCredentialPath("fixture"), first);
        assertThatThrownBy(() -> store.readAll("fixture")).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> store.readForTransfer("fixture")).isInstanceOf(IOException.class);
        assertThat(Files.readAllBytes(RuntimeFiles.peekPluginCredentialPath("fixture"))).isEqualTo(first);
    }

    @Test
    @DisplayName("部分迁移后失败保留同一密钥和未迁移字节，修复来源后可恢复")
    void resumesPartialMigrationWithoutReplacingTheKey() throws Exception {
        legacy("first", "first-secret");
        byte[] second = legacy("second", "second-secret");
        var current = InstallationCredentialKeys.forWrite(false);
        Files.write(RuntimeFiles.peekPluginCredentialPath("first"), current.encrypt("first", Map.of("first.token", "first-secret")));
        byte[] key = Files.readAllBytes(RuntimeFiles.pluginCredentialKeyPath());
        byte[] invalid = "broken-credential".getBytes(StandardCharsets.UTF_8);
        Files.write(RuntimeFiles.peekPluginCredentialPath("second"), invalid);
        assertThat(new PluginCredentialStore().readAll("first")).containsEntry("first.token", "first-secret");
        assertThatThrownBy(() -> new PluginCredentialStore().readAll("second")).isInstanceOf(IOException.class);
        assertThat(Files.readAllBytes(RuntimeFiles.pluginCredentialKeyPath())).isEqualTo(key);
        assertThat(Files.readAllBytes(RuntimeFiles.peekPluginCredentialPath("second"))).isEqualTo(invalid);
        Files.write(RuntimeFiles.peekPluginCredentialPath("second"), second);
        assertThat(new PluginCredentialStore().readAll("second")).containsEntry("second.token", "second-secret");
        assertThat(current.decode("first", Files.readAllBytes(RuntimeFiles.peekPluginCredentialPath("first"))).values())
                .containsEntry("first.token", "first-secret");
    }

    @Test
    @DisplayName("密钥损坏、保护密钥错误及安装密钥丢失均保留凭据且不重新生成")
    void failsClosedForMissingDamagedOrWrongKeys() throws Exception {
        var store = new PluginCredentialStore();
        store.update("fixture", Map.of("fixture.token", "keep-secret"));
        Path credential = RuntimeFiles.peekPluginCredentialPath("fixture");
        Path keyPath = RuntimeFiles.pluginCredentialKeyPath();
        byte[] content = Files.readAllBytes(credential);
        byte[] key = Files.readAllBytes(keyPath);
        byte[] corrupted = key.clone();
        corrupted[corrupted.length - 1] ^= 1;
        Files.write(keyPath, corrupted);
        assertThatThrownBy(() -> store.update("fixture", Map.of("fixture.token", "replacement"))).isInstanceOf(IOException.class);
        assertThat(Files.readAllBytes(keyPath)).isEqualTo(corrupted);
        Files.write(keyPath, key);
        Path otherSecret = temporary.resolve("other-secret");
        Files.writeString(otherSecret, Base64.getEncoder().encodeToString(new byte[32]), StandardCharsets.US_ASCII);
        System.setProperty(CredentialKeyProtection.KEY_FILE_PROPERTY, otherSecret.toString());
        assertThatThrownBy(() -> store.readAll("fixture")).isInstanceOf(IOException.class);
        assertThat(Files.readAllBytes(keyPath)).isEqualTo(key);
        Files.delete(keyPath);
        assertThatThrownBy(() -> store.readAll("fixture")).isInstanceOf(IOException.class).hasMessageContaining("missing");
        assertThat(keyPath).doesNotExist();
        assertThat(Files.readAllBytes(credential)).isEqualTo(content);
    }

    @Test
    @DisplayName("并发首次写入共用同一耐久安装密钥，所有 owner 均可恢复")
    void concurrentInitializationKeepsAllOwnersReadable() throws Exception {
        try (var executor = Executors.newFixedThreadPool(4)) {
            var futures = java.util.stream.IntStream.range(0, 8).mapToObj(index -> executor.submit(() -> {
                new PluginCredentialStore().update("owner-" + index, Map.of("owner-" + index + ".token", "value-" + index));
                return true;
            })).toList();
            for (var future : futures) assertThat(future.get(30, TimeUnit.SECONDS)).isTrue();
        }
        for (int index = 0; index < 8; index++) assertThat(new PluginCredentialStore().readAll("owner-" + index))
                .containsEntry("owner-" + index + ".token", "value-" + index);
    }

    @Test
    @DisplayName("导入准备不改变现有凭据，目标安装用自己的密钥保存导出值")
    void preparesTransferWithoutRewritingLiveCredentials() throws Exception {
        byte[] old = legacy("fixture", "keep-secret");
        var store = new PluginCredentialStore();
        byte[] prepared = store.prepareTransfer("fixture", Map.of("fixture.extra", "imported-secret"));
        assertThat(Files.readAllBytes(RuntimeFiles.peekPluginCredentialPath("fixture"))).isEqualTo(old);
        Files.write(RuntimeFiles.peekPluginCredentialPath("fixture"), prepared);
        assertThat(new PluginCredentialStore().readAll("fixture"))
                .containsEntry("fixture.token", "keep-secret").containsEntry("fixture.extra", "imported-secret");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("真实 Windows 当前用户 DPAPI 可往返并拒绝篡改，存储文件仅允许所有者访问")
    void windowsDpapiRoundTripAndTampering() throws Exception {
        System.setProperty(CredentialKeyProtection.KEY_FILE_PROPERTY, "");
        new PluginCredentialStore().update("fixture", Map.of("fixture.token", "windows-secret"));
        Path keyPath = RuntimeFiles.pluginCredentialKeyPath();
        byte[] key = Files.readAllBytes(keyPath);
        assertThat(CredentialKeyProtection.storedMode(key)).isEqualTo(CredentialKeyProtection.WINDOWS);
        assertThat(new PluginCredentialStore().readAll("fixture")).containsEntry("fixture.token", "windows-secret");
        var acl = Files.getFileAttributeView(keyPath, java.nio.file.attribute.AclFileAttributeView.class).getAcl();
        assertThat(acl).allSatisfy(entry -> assertThat(entry.principal()).isEqualTo(Files.getOwner(keyPath)));
        key[key.length - 1] ^= 1;
        Files.write(keyPath, key);
        assertThatThrownBy(() -> new PluginCredentialStore().readAll("fixture")).isInstanceOf(IOException.class);
        assertThat(Files.readAllBytes(keyPath)).isEqualTo(key);
    }

    @Test
    @DisplayName("未初始化实例的空凭据读取与归档预览不创建目录或要求外部密钥")
    void emptyReadDoesNotCreateOrUnlockAnything() throws Exception {
        System.setProperty(CredentialKeyProtection.KEY_FILE_PROPERTY, temporary.resolve("missing-secret").toString());
        var store = new PluginCredentialStore();
        assertThat(store.readAll("fixture")).isEmpty();
        assertThat(store.readForTransfer("fixture")).isEmpty();
        assertThat(RuntimeFiles.configDirectory()).doesNotExist();
    }

    private byte[] legacy(String owner, String value) throws Exception {
        var cipher = PluginCredentialCipher.legacy(PluginCredentialKeyMaterial.loadLegacy());
        byte[] envelope = cipher.encrypt(owner, Map.of(owner + ".token", value));
        Path path = RuntimeFiles.resolvePluginCredentialPath(owner);
        Files.write(path, envelope);
        return envelope;
    }

    @Test
    @DisplayName("外部密钥缺失、格式错误或超限时不改写旧凭据")
    void invalidExternalSecretPreservesLegacyCredentials() throws Exception {
        byte[] old = legacy("fixture", "retained-secret");
        Path secret = Path.of(System.getProperty(CredentialKeyProtection.KEY_FILE_PROPERTY));
        for (String invalid : new String[]{"not-base64", Base64.getEncoder().encodeToString(new byte[31]), "A".repeat(65)}) {
            Files.writeString(secret, invalid, StandardCharsets.US_ASCII);
            assertThatThrownBy(() -> new PluginCredentialStore().readAll("fixture")).isInstanceOf(IOException.class);
            assertThat(RuntimeFiles.pluginCredentialKeyPath()).doesNotExist();
            assertThat(Files.readAllBytes(RuntimeFiles.peekPluginCredentialPath("fixture"))).isEqualTo(old);
        }
        Files.delete(secret);
        assertThatThrownBy(() -> new PluginCredentialStore().readAll("fixture")).isInstanceOf(IOException.class);
        assertThat(Files.readAllBytes(RuntimeFiles.peekPluginCredentialPath("fixture"))).isEqualTo(old);
    }

    @Test
    @DisplayName("密钥初始化锁被占用时明确失败，释放后可以安全重试")
    void refusesContendedInitializationWithoutPublishingAKey() throws Exception {
        byte[] old = legacy("fixture", "retained-secret");
        try (var channel = java.nio.channels.FileChannel.open(RuntimeFiles.pluginCredentialKeyLockPath(),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            assertThatThrownBy(() -> new PluginCredentialStore().readAll("fixture"))
                    .isInstanceOf(IOException.class).hasMessageContaining("already in progress");
            assertThat(RuntimeFiles.pluginCredentialKeyPath()).doesNotExist();
            assertThat(Files.readAllBytes(RuntimeFiles.peekPluginCredentialPath("fixture"))).isEqualTo(old);
        }
        assertThat(new PluginCredentialStore().readAll("fixture")).containsEntry("fixture.token", "retained-secret");
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) System.clearProperty(name); else System.setProperty(name, value);
    }

    @Test
    @DisplayName("超限密钥与凭据在解码前拒绝，并保留原文件")
    void refusesOversizedFilesWithoutRewriting() throws Exception {
        var store = new PluginCredentialStore();
        store.update("fixture", Map.of("fixture.token", "retained-secret"));
        Path keyPath = RuntimeFiles.pluginCredentialKeyPath();
        Path credential = RuntimeFiles.peekPluginCredentialPath("fixture");
        byte[] key = Files.readAllBytes(keyPath);
        byte[] old = Files.readAllBytes(credential);
        byte[] oversizedKey = new byte[CredentialKeyProtection.MAX_WRAPPED_BYTES + 1];
        Files.write(keyPath, oversizedKey);
        assertThatThrownBy(() -> store.readAll("fixture"))
                .isInstanceOf(IOException.class).hasMessageContaining("size limit");
        assertThat(Files.size(keyPath)).isEqualTo(oversizedKey.length);
        assertThat(Files.readAllBytes(credential)).isEqualTo(old);
        Files.write(keyPath, key);
        byte[] oversizedCredential = new byte[InstallationCredentialKeys.MAX_CREDENTIAL_BYTES + 1];
        Files.write(credential, oversizedCredential);
        assertThatThrownBy(() -> store.readAll("fixture"))
                .isInstanceOf(IOException.class).hasMessageContaining("size limit");
        assertThat(Arrays.equals(Files.readAllBytes(credential), oversizedCredential)).isTrue();
        assertThat(Files.readAllBytes(keyPath)).isEqualTo(key);
    }
}
