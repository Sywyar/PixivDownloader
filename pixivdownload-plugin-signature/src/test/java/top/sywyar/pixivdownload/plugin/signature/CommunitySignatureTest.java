package top.sywyar.pixivdownload.plugin.signature;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.signature.community.*;
import top.sywyar.pixivdownload.plugin.signature.internal.ed25519.Ed25519Signer;
import top.sywyar.pixivdownload.plugin.signature.internal.ed25519.Ed25519Verifier;
import top.sywyar.pixivdownload.plugin.signature.internal.envelope.EnvelopeV1Codec;
import top.sywyar.pixivdownload.plugin.signature.internal.envelope.Hashing;
import top.sywyar.pixivdownload.plugin.signature.internal.trust.KeyParsing;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("社区签名域与跨语言公开向量")
class CommunitySignatureTest {
    @TempDir Path directory;
    private final Properties vector = readVector();

    @Test
    @DisplayName("Java 与独立 Node 消费同一固定消息及签名，旧消息保持兼容")
    void matchesIndependentVectors() {
        byte[] raw = hex("rawHex");
        byte[] canonical = hex("canonicalHex");
        byte[] hash = Hashing.sha256(raw);
        Map<String, byte[]> messages = new LinkedHashMap<>();
        messages.put("package", EnvelopeV1Codec.communityPackageMessage(v("algorithm"), v("keyId"),
                v("repositoryId"), v("pluginId"), v("version"), raw.length, hash,
                v("assuranceLevel"), v("sourceCommit"), hex("reviewRecordSha256")));
        messages.put("directory", EnvelopeV1Codec.communityDirectoryMessage(v("repositoryId"), 7, raw.length, hash));
        messages.put("rotation", operationMessage(CommunityOperation.PUBLISHER_KEY_ROTATION, canonical));
        messages.put("status", operationMessage(CommunityOperation.VERSION_STATUS_REQUEST, canonical));
        messages.put("transfer", operationMessage(CommunityOperation.OWNERSHIP_TRANSFER, canonical));
        messages.put("artifact", EnvelopeV1Codec.artifactMessage(v("algorithm"), v("keyId"),
                v("pluginId"), v("version"), raw.length, hash));
        messages.put("repositoryUpdate", EnvelopeV1Codec.repositoryUpdateMessage(v("repositoryId"), 7, raw.length, hash));
        messages.put("revocations", EnvelopeV1Codec.pluginRevocationsMessage(v("repositoryId"), 7, raw.length, hash));
        var privateKey = KeyParsing.ed25519PrivateKey(v("publicTestPrivateKey"));
        for (var entry : messages.entrySet()) {
            byte[] message = entry.getValue();
            byte[] signature = Base64.getDecoder().decode(v(entry.getKey() + ".signature"));
            assertThat(message).as(entry.getKey()).isEqualTo(hex(entry.getKey() + ".messageHex"));
            assertThat(Ed25519Signer.sign(privateKey, message)).isEqualTo(signature);
            assertThat(Ed25519Verifier.verify(v("publicKey"), message, signature)).isTrue();
            for (int i = 0; i < message.length; i++) {
                byte[] changed = message.clone();
                changed[i] ^= 1;
                assertThat(Ed25519Verifier.verify(v("publicKey"), changed, signature)).as("字节 %s", i).isFalse();
            }
        }
        assertThat(key(false, TrustedPluginKey.State.ACTIVE).publicKeyFingerprint()).isEqualTo(v("fingerprint"));
    }

    @Test
    @DisplayName("社区包绑定每个安全字段，并拒绝文件大小、摘要与未签名输入")
    void bindsPackageFacts() throws Exception {
        Path artifact = directory.resolve("plugin.jar");
        byte[] raw = hex("rawHex");
        Files.write(artifact, raw);
        var verifier = verifier(false, TrustedPluginKey.State.ACTIVE);
        var signature = signature("package");
        String[] fields = {v("repositoryId"), v("pluginId"), v("version"), v("assuranceLevel"),
                v("sourceCommit"), v("reviewRecordSha256")};
        assertThat(verifier.verifyCommunityPackage(packageRequest(artifact, fields, raw.length, Hashing.hex(Hashing.sha256(raw)), signature)).accepted()).isTrue();
        for (int i = 0; i < fields.length; i++) {
            String[] changed = fields.clone();
            changed[i] = i == 5 ? "cd".repeat(32) : changed[i] + "x";
            assertThat(verifier.verifyCommunityPackage(packageRequest(artifact, changed, raw.length,
                    Hashing.hex(Hashing.sha256(raw)), signature)).status()).isEqualTo(VerificationStatus.INVALID_SIGNATURE);
        }
        assertThat(verifier.verifyCommunityPackage(packageRequest(artifact, fields, raw.length + 1,
                Hashing.hex(Hashing.sha256(raw)), signature)).status()).isEqualTo(VerificationStatus.HASH_MISMATCH);
        assertThat(verifier.verifyCommunityPackage(packageRequest(artifact, fields, raw.length,
                "00".repeat(32), signature)).status()).isEqualTo(VerificationStatus.HASH_MISMATCH);
        assertThat(verifier.verifyCommunityPackage(packageRequest(artifact, fields, raw.length,
                Hashing.hex(Hashing.sha256(raw)), null)).status()).isEqualTo(VerificationStatus.SIGNATURE_REQUIRED);
        Files.writeString(artifact, "changed", StandardCharsets.UTF_8);
        assertThat(verifier.verifyCommunityPackage(packageRequest(artifact, fields, raw.length,
                Hashing.hex(Hashing.sha256(raw)), signature)).accepted()).isFalse();
    }

    @Test
    @DisplayName("三种操作域不能互换，正文、ID、目录序号与原字节都绑定")
    void bindsOperationsAndDirectory() {
        var verifier = verifier(false, TrustedPluginKey.State.ACTIVE);
        String[] names = {"rotation", "status", "transfer"};
        var operations = CommunityOperation.values();
        for (int i = 0; i < operations.length; i++) {
            for (int j = 0; j < operations.length; j++) {
                assertThat(verifier.verifyCommunityOperation(new CommunityOperationVerificationRequest(
                        operations[i], hex("canonicalHex"), v("requestId"), signature(names[j]), false)).accepted())
                        .isEqualTo(i == j);
            }
            byte[] changed = hex("canonicalHex");
            changed[0] ^= 1;
            assertThat(verifier.verifyCommunityOperation(new CommunityOperationVerificationRequest(
                    operations[i], changed, v("requestId"), signature(names[i]), false)).status())
                    .isEqualTo(VerificationStatus.HASH_MISMATCH);
            assertThat(verifier.verifyCommunityOperation(new CommunityOperationVerificationRequest(
                    operations[i], changed, Hashing.hex(Hashing.sha256(changed)), signature(names[i]), false)).status())
                    .isEqualTo(VerificationStatus.INVALID_SIGNATURE);
        }
        assertThat(verifier.verifyCommunityDirectory(directoryRequest(hex("rawHex"), v("repositoryId"), 7, signature("directory"), false)).accepted()).isTrue();
        assertThat(verifier.verifyCommunityDirectory(directoryRequest(hex("rawHex"), "other", 7, signature("directory"), false)).accepted()).isFalse();
        assertThat(verifier.verifyCommunityDirectory(directoryRequest(hex("rawHex"), v("repositoryId"), 8, signature("directory"), false)).accepted()).isFalse();
        assertThat(verifier.verifyCommunityDirectory(directoryRequest(new byte[]{1}, v("repositoryId"), 7, signature("directory"), false)).accepted()).isFalse();
        byte[] source = hex("rawHex");
        var snapshot = directoryRequest(source, v("repositoryId"), 7, signature("directory"), false);
        Arrays.fill(source, (byte) 0);
        Arrays.fill(snapshot.documentBytes(), (byte) 0);
        assertThat(verifier.verifyCommunityDirectory(snapshot).accepted()).isTrue();
    }

    @Test
    @DisplayName("严格编码拒绝截断、非标准 Base64、错误格式、公钥与官方根混用")
    void rejectsMalformedProofsAndWrongTrustRoots() {
        var valid = signature("directory");
        var verifier = verifier(false, TrustedPluginKey.State.ACTIVE);
        for (String value : new String[]{valid.value().stripTrailing().replace("=", ""),
                " " + valid.value(), valid.value() + "\n", "!", Base64.getEncoder().encodeToString(new byte[63])}) {
            assertThat(verifier.verifyCommunityDirectory(directoryRequest(hex("rawHex"), v("repositoryId"), 7,
                    new SignatureMetadata(1, "Ed25519", v("keyId"), value), false)).status())
                    .isEqualTo(VerificationStatus.MALFORMED_SIGNATURE);
        }
        for (SignatureMetadata invalid : List.of(new SignatureMetadata(2, "Ed25519", v("keyId"), valid.value()),
                new SignatureMetadata(1, "RSA", v("keyId"), valid.value()),
                new SignatureMetadata(1, "Ed25519", " " + v("keyId"), valid.value()),
                new SignatureMetadata(1, "Ed25519", "unknown", valid.value()))) {
            assertThat(verifier.verifyCommunityDirectory(directoryRequest(hex("rawHex"), v("repositoryId"), 7, invalid, false)).accepted()).isFalse();
        }
        assertThat(verifier(true, TrustedPluginKey.State.ACTIVE).verifyCommunityDirectory(
                directoryRequest(hex("rawHex"), v("repositoryId"), 7, valid, false)).status()).isEqualTo(VerificationStatus.UNKNOWN_KEY);
        assertThat(verifier(false, TrustedPluginKey.State.RETIRED).verifyCommunityDirectory(
                directoryRequest(hex("rawHex"), v("repositoryId"), 7, valid, false)).status()).isEqualTo(VerificationStatus.RETIRED_KEY);
        assertThat(verifier(false, TrustedPluginKey.State.RETIRED).verifyCommunityDirectory(
                directoryRequest(hex("rawHex"), v("repositoryId"), 7, valid, true)).accepted()).isTrue();
        assertThat(verifier(false, TrustedPluginKey.State.REVOKED).verifyCommunityDirectory(
                directoryRequest(hex("rawHex"), v("repositoryId"), 7, valid, true)).status()).isEqualTo(VerificationStatus.REVOKED_KEY);
        TrustedPluginKey malformed = new TrustedPluginKey(v("keyId"), "Ed25519", "AA==",
                TrustedPluginKey.State.ACTIVE, "test", "test", false);
        assertThat(new PluginSupplyChainVerifier(id -> Optional.of(malformed)).verifyCommunityDirectory(
                directoryRequest(hex("rawHex"), v("repositoryId"), 7, valid, false)).accepted()).isFalse();
        assertThatThrownBy(() -> KeyParsing.canonicalEd25519PublicKey(v("publicKey").replace("=", "")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("社区工厂拒绝非规范公钥和官方标记，保留历史状态及旧工厂行为")
    void communityTrustStoreUsesCanonicalKeys() {
        for (var state : TrustedPluginKey.State.values()) {
            var key = key(false, state);
            assertThat(PluginTrustStores.community(List.of(key)).findByKeyId(key.keyId())).contains(key);
        }
        byte[] canonical = Base64.getDecoder().decode(v("publicKey"));
        byte[] alternate = new byte[canonical.length + 2];
        System.arraycopy(canonical, 0, alternate, 0, 9);
        alternate[1] += 2; alternate[3] += 2;
        alternate[9] = 5; alternate[10] = 0;
        System.arraycopy(canonical, 9, alternate, 11, canonical.length - 9);
        for (String encoded : List.of(v("publicKey").replace("=", ""), Base64.getEncoder().encodeToString(alternate))) {
            var key = new TrustedPluginKey(v("keyId"), "Ed25519", encoded, TrustedPluginKey.State.ACTIVE, "test", "test", false);
            assertThat(PluginTrustStores.of(List.of(key)).findByKeyId(key.keyId())).contains(key);
            assertThatThrownBy(() -> PluginTrustStores.community(List.of(key))).isInstanceOf(IllegalArgumentException.class);
        }
        var official = key(true, TrustedPluginKey.State.ACTIVE);
        assertThat(PluginTrustStores.of(List.of(official)).findByKeyId(official.keyId())).contains(official);
        assertThatThrownBy(() -> PluginTrustStores.community(List.of(official))).isInstanceOf(IllegalArgumentException.class);
        var valid = key(false, TrustedPluginKey.State.ACTIVE);
        assertThatThrownBy(() -> PluginTrustStores.community(List.of(valid, valid))).isInstanceOf(IllegalArgumentException.class);
        var malformed = new TrustedPluginKey(v("keyId"), "Ed25519", "invalid", TrustedPluginKey.State.ACTIVE, "test", "test", false);
        assertThatThrownBy(() -> PluginTrustStores.community(List.of(malformed))).isInstanceOf(IllegalArgumentException.class);
    }

    private CommunityPackageVerificationRequest packageRequest(Path artifact, String[] fields, long size,
            String sha, SignatureMetadata signature) {
        return new CommunityPackageVerificationRequest(artifact, fields[0], fields[1], fields[2], size, sha,
                fields[3], fields[4], fields[5], signature, false);
    }

    private CommunityDirectoryVerificationRequest directoryRequest(byte[] bytes, String repositoryId, long sequence,
            SignatureMetadata signature, boolean retired) {
        return new CommunityDirectoryVerificationRequest(bytes, repositoryId, sequence, signature, retired);
    }

    private byte[] operationMessage(CommunityOperation operation, byte[] canonical) {
        return EnvelopeV1Codec.communityOperationMessage(operation, v("algorithm"), v("keyId"),
                canonical.length, Hashing.sha256(canonical));
    }

    private SignatureMetadata signature(String name) { return new SignatureMetadata(1, v("algorithm"), v("keyId"), v(name + ".signature")); }
    private TrustedPluginKey key(boolean official, TrustedPluginKey.State state) { return new TrustedPluginKey(v("keyId"), v("algorithm"), v("publicKey"), state, "Public test", "Public test", official); }
    private PluginSupplyChainVerifier verifier(boolean official, TrustedPluginKey.State state) { return new PluginSupplyChainVerifier(PluginTrustStores.of(List.of(key(official, state)))); }
    private String v(String name) { return vector.getProperty(name); }
    private byte[] hex(String name) { return HexFormat.of().parseHex(v(name)); }

    private static Properties readVector() {
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(Path.of("../contracts/community/v1/vectors/signatures.properties"), StandardCharsets.UTF_8)) {
            properties.load(reader);
            return properties;
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
