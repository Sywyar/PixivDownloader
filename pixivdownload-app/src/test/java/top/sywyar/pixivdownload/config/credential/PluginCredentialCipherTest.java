package top.sywyar.pixivdownload.config.credential;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("插件凭证加密信封")
class PluginCredentialCipherTest {

    private static final String OWNER = "fixture";
    private static final String KEY = "fixture.api-key";
    private static final String SECRET = "令牌=fixture-token\n第二行\t\\尾";

    @Test
    @DisplayName("完整凭证映射经 owner 派生密钥加密且不泄露字段名和值")
    void encryptsWholeCredentialMapWithoutPlaintextMetadata() throws Exception {
        PluginCredentialCipher cipher = cipher((byte) 0x21);

        byte[] encrypted = cipher.encrypt(OWNER, Map.of(KEY, SECRET));
        String envelope = new String(encrypted, StandardCharsets.UTF_8);

        assertThat(envelope)
                .contains("format=" + PluginCredentialCipher.FORMAT)
                .contains("key-id=")
                .contains("nonce=")
                .contains("ciphertext=")
                .doesNotContain(KEY, SECRET, "fixture-token");
        assertThat(cipher.decode(OWNER, encrypted).values()).containsEntry(KEY, SECRET);
    }

    @Test
    @DisplayName("同一明文的独立加密使用不同随机 nonce")
    void encryptsSamePlaintextWithFreshNonce() throws Exception {
        PluginCredentialCipher cipher = cipher((byte) 0x22);

        byte[] first = cipher.encrypt(OWNER, Map.of(KEY, SECRET));
        byte[] second = cipher.encrypt(OWNER, Map.of(KEY, SECRET));

        assertThat(second).isNotEqualTo(first);
        assertThat(envelopeFields(second).get("nonce"))
                .isNotEqualTo(envelopeFields(first).get("nonce"));
    }

    @Test
    @DisplayName("信封绑定 owner，复制到另一插件后认证失败")
    void bindsEnvelopeToOwner() throws Exception {
        PluginCredentialCipher cipher = cipher((byte) 0x23);
        byte[] encrypted = cipher.encrypt(OWNER, Map.of(KEY, SECRET));

        assertThatThrownBy(() -> cipher.decode("other", encrypted))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("authentication failed");
    }

    @Test
    @DisplayName("格式、密钥编号、nonce 与密文任一篡改均拒绝解密")
    void rejectsTamperedEnvelopeFields() throws Exception {
        PluginCredentialCipher cipher = cipher((byte) 0x24);
        byte[] encrypted = cipher.encrypt(OWNER, Map.of(KEY, SECRET));
        Map<String, String> original = envelopeFields(encrypted);

        Map<String, Map<String, String>> tampered = new LinkedHashMap<>();
        tampered.put("format", with(original, "format", PluginCredentialCipher.FORMAT + "-tampered"));
        tampered.put("key-id", with(original, "key-id", "unknown-key-id"));
        tampered.put("nonce", with(original, "nonce", flipEncodedByte(original.get("nonce"))));
        tampered.put("ciphertext",
                with(original, "ciphertext", flipEncodedByte(original.get("ciphertext"))));

        for (Map.Entry<String, Map<String, String>> entry : tampered.entrySet()) {
            byte[] content = serializeEnvelope(entry.getValue());
            assertThatThrownBy(() -> cipher.decode(OWNER, content))
                    .as(entry.getKey())
                    .isInstanceOf(IOException.class);
        }
    }

    @Test
    @DisplayName("不完整信封与占用保留字段的旧明文均 fail-closed")
    void rejectsIncompleteOrReservedLegacyContent() throws Exception {
        PluginCredentialCipher cipher = cipher((byte) 0x25);
        Map<String, String> fields = envelopeFields(
                cipher.encrypt(OWNER, Map.of(KEY, SECRET)));
        fields.remove("format");

        assertThatThrownBy(() -> cipher.decode(OWNER, serializeEnvelope(fields)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Incomplete");
        assertThatThrownBy(() -> cipher.decode(
                OWNER, "format=legacy-value\nfixture.api-key=value\n"
                        .getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("非当前或未知根密钥不能读取信封")
    void rejectsUnknownRootKey() throws Exception {
        byte[] encrypted = cipher((byte) 0x26)
                .encrypt(OWNER, Map.of(KEY, SECRET));

        assertThatThrownBy(() -> cipher((byte) 0x27).decode(OWNER, encrypted))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Unknown plugin credential key id");
    }

    @Test
    @DisplayName("凭证文件被替换成明文时拒绝降级且不当作迁移输入")
    void rejectsPlaintextDowngrade() {
        assertThatThrownBy(() -> cipher((byte) 0x28).decode(
                OWNER,
                (KEY + "=旧令牌\\n第二行\\\\尾\n").getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("authenticated");
    }

    @Test
    @DisplayName("前导空白与 Properties 控制字符无损往返")
    void preservesLeadingWhitespaceAndPropertiesControlCharacters() throws Exception {
        PluginCredentialCipher cipher = cipher((byte) 0x29);
        String value = "  \t\f 前导空白\n尾部 ";

        PluginCredentialCipher.Decoded decoded =
                cipher.decode(OWNER, cipher.encrypt(OWNER, Map.of(KEY, value)));

        assertThat(decoded.values()).containsEntry(KEY, value);
    }

    @Test
    @DisplayName("归一化后重名的凭证键拒绝进入加密信封")
    void rejectsDuplicateNormalizedCredentialKeys() {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        values.put(KEY, "first-secret");
        values.put(" " + KEY + " ", "second-secret");

        assertThatThrownBy(() -> cipher((byte) 0x2A).encrypt(OWNER, values))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Duplicate normalized plugin credential key")
                .hasMessageContaining(KEY);
    }

    private static PluginCredentialCipher cipher(byte keyByte) {
        byte[] key = repeated(keyByte);
        return new PluginCredentialCipher(PluginCredentialKeyMaterial.forTesting(key, key));
    }

    private static byte[] repeated(byte value) {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, value);
        return key;
    }

    private static Map<String, String> envelopeFields(byte[] content) {
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        for (String line : new String(content, StandardCharsets.UTF_8).split("\\R")) {
            int separator = line.indexOf('=');
            if (separator > 0) {
                fields.put(line.substring(0, separator), line.substring(separator + 1));
            }
        }
        return fields;
    }

    private static Map<String, String> with(
            Map<String, String> source, String key, String value) {
        LinkedHashMap<String, String> copy = new LinkedHashMap<>(source);
        copy.put(key, value);
        return copy;
    }

    private static String flipEncodedByte(String encoded) {
        byte[] decoded = Base64.getUrlDecoder().decode(encoded);
        decoded[0] ^= 0x01;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(decoded);
    }

    private static byte[] serializeEnvelope(Map<String, String> fields) {
        StringBuilder content = new StringBuilder();
        fields.forEach((key, value) -> content.append(key).append('=').append(value).append('\n'));
        return content.toString().getBytes(StandardCharsets.UTF_8);
    }
}
