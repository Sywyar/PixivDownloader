package com.example.captcha;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.altcha.altcha.v2.Altcha;
import org.json.JSONObject;
import org.junit.jupiter.api.*;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.MapPropertySource;
import top.sywyar.pixivdownload.plugin.api.credential.PluginCredentials;
import top.sywyar.pixivdownload.plugin.api.http.*;
import top.sywyar.pixivdownload.plugin.api.security.*;
import static org.junit.jupiter.api.Assertions.*;

class CaptchaVerificationTest {
    private final Map<String, String> values = new HashMap<>();
    private final PluginCredentials credentials = new PluginCredentials() {
        public Optional<String> read(String key) { return Optional.ofNullable(values.get(key)); }
        public void update(Map<String, String> updates) { values.putAll(updates); }
    };
    private final StandardEnvironment environment = new StandardEnvironment();
    private final Map<String, Object> config = new HashMap<>();
    private String remote = "{}", sent;
    private int exchanges, closed;
    private final OutboundHttpClient http = new OutboundHttpClient() {
        public OutboundHttpStreamResponse exchangeStream(OutboundHttpRequest request) {
            exchanges++; sent = new String(request.body(), StandardCharsets.UTF_8);
            assertEquals("https://challenges.cloudflare.com/turnstile/v0/siteverify", request.uri().toString());
            assertEquals("POST", request.method());
            return new OutboundHttpStreamResponse(200, "OK", Map.of(), new ByteArrayInputStream(remote.getBytes(StandardCharsets.UTF_8)) {
                public void close() { closed++; }
            });
        }
        public void close() { }
    };
    @BeforeEach void setup() {
        values.put("captcha-example.altcha-secret", "test-only-altcha-key-with-at-least-32-characters");
        values.put("captcha-example.turnstile-secret", "test-private-credential");
        config.put("captcha-example.turnstile-sitekey", "production-sitekey");
        config.put("captcha-example.turnstile-hostname", "captcha.example");
        environment.getPropertySources().addFirst(new MapPropertySource("test", config));
    }
    private OperationVerificationRequest request(boolean response, String proof) {
        return new OperationVerificationRequest("unknown:submit", "subject", "{}", "attempt-test", "revision", "", response, proof);
    }
    @Test @DisplayName("未配置方案可声明稳定代际，实际执行仍拒绝且不影响已配置方案")
    void independentCredentials() {
        var providers = List.of(new AltchaVerification(credentials, false), new AltchaVerification(credentials, true),
                new TurnstileVerification(credentials, environment, http));
        var configured = Map.copyOf(values);
        for (String key : List.of("captcha-example.altcha-secret", "captcha-example.turnstile-secret", "neither")) {
            values.clear();
            if (configured.containsKey(key)) values.put(key, configured.get(key));
            for (var provider : providers) {
                String generation = provider.generation();
                assertFalse(generation.isBlank());
                assertEquals(generation, provider.generation());
                boolean enabled = provider.policyId().startsWith("captcha-altcha")
                        ? key.equals("captcha-example.altcha-secret") : key.equals("captcha-example.turnstile-secret");
                if (enabled) assertEquals(OperationVerificationResult.Status.CHALLENGE, provider.verify(request(false, "")).status());
                else for (boolean response : List.of(false, true)) {
                    assertEquals("CAPTCHA_CREDENTIAL_MISSING", assertThrows(IllegalStateException.class,
                            () -> provider.verify(request(response, "ticket"))).getMessage());
                }
            }
        }
        assertEquals(0, exchanges);
        for (var provider : providers) {
            String absent = provider.generation();
            values.putAll(configured);
            String present = provider.generation();
            assertNotEquals(absent, present);
            values.replaceAll((key, value) -> value + "-rotated");
            assertNotEquals(present, provider.generation());
            values.clear();
            assertEquals(absent, provider.generation());
        }
    }
    @Test @DisplayName("ALTCHA 拒绝错误签名、跨尝试、超限计算参数和过期证明")
    void altchaBoundaries() throws Exception {
        var provider = new AltchaVerification(credentials, false);
        var challenge = new JSONObject(provider.verify(request(false, "")).challenge());
        assertEquals("attempt-test", challenge.getJSONObject("parameters").getJSONObject("data").getString("attempt"));
        for (String invalid : List.of("", "not-base64", "a".repeat(8193))) {
            assertEquals(OperationVerificationResult.Status.REJECTED, provider.verify(request(true, invalid)).status());
        }
        // counter=0 使官方库生成一个已知可解的测试挑战，不替代浏览器求解验收。
        var known = Altcha.createChallenge(new Altcha.CreateChallengeOptions().algorithm("SHA-256").cost(1)
                .counter(0).keyLength(32).keyPrefixLength(64).deriveKey(Altcha.sha()).expiresInSeconds(60)
                .hmacSignatureSecret(values.get("captcha-example.altcha-secret")).data(Map.of("attempt", "attempt-test")));
        JSONObject payload = new JSONObject().put("challenge", new JSONObject(known.toJson()))
                .put("solution", new JSONObject().put("counter", 0).put("derivedKey", known.parameters().keyPrefix()).put("time", 0));
        assertEquals(OperationVerificationResult.Status.APPROVED, provider.verify(request(true, encoded(payload))).status());
        payload.getJSONObject("challenge").getJSONObject("parameters").getJSONObject("data").put("attempt", "another");
        assertEquals(OperationVerificationResult.Status.REJECTED, provider.verify(request(true, encoded(payload))).status());
        payload.getJSONObject("challenge").getJSONObject("parameters").getJSONObject("data").put("attempt", "attempt-test");
        payload.getJSONObject("challenge").getJSONObject("parameters").put("cost", 100000000);
        assertEquals(OperationVerificationResult.Status.REJECTED, provider.verify(request(true, encoded(payload))).status());
        payload.getJSONObject("challenge").getJSONObject("parameters").put("cost", 1).put("expiresAt", 1);
        assertEquals(OperationVerificationResult.Status.REJECTED, provider.verify(request(true, encoded(payload))).status());
        String generation = provider.generation();
        values.put("captcha-example.altcha-secret", "another-test-only-key-with-at-least-32-characters");
        assertNotEquals(generation, provider.generation());
    }
    private String encoded(JSONObject payload) {
        return Base64.getEncoder().encodeToString(payload.toString().getBytes(StandardCharsets.UTF_8));
    }
    @Test @DisplayName("第三个 PBKDF2 适配使用独立策略并拒绝 SHA 证明")
    void pbkdf2Adapter() throws Exception {
        var provider = new AltchaVerification(credentials, true);
        var challenge = new JSONObject(provider.verify(request(false, "")).challenge());
        assertEquals("PBKDF2/SHA-256", challenge.getJSONObject("parameters").getString("algorithm"));
        assertNotEquals(new AltchaVerification(credentials, false).policyId(), provider.policyId());
        var known = Altcha.createChallenge(new Altcha.CreateChallengeOptions().algorithm("PBKDF2/SHA-256").cost(1000)
                .counter(0).keyLength(32).keyPrefixLength(64).deriveKey(Altcha.pbkdf2()).expiresInSeconds(60)
                .hmacSignatureSecret(values.get("captcha-example.altcha-secret")).data(Map.of("attempt", "attempt-test")));
        JSONObject payload = new JSONObject().put("challenge", new JSONObject(known.toJson()))
                .put("solution", new JSONObject().put("counter", 0).put("derivedKey", known.parameters().keyPrefix()).put("time", 0));
        assertEquals(OperationVerificationResult.Status.APPROVED, provider.verify(request(true, encoded(payload))).status());
        assertEquals(OperationVerificationResult.Status.REJECTED,
                new AltchaVerification(credentials, false).verify(request(true, encoded(payload))).status());
    }
    @Test @DisplayName("Turnstile 正式配置强制检查主机、动作、当前尝试和远端结果")
    void productionChecks() {
        var provider = new TurnstileVerification(credentials, environment, http);
        JSONObject accepted = new JSONObject().put("success", true).put("hostname", "captcha.example").put("action", "operation").put("cdata", "attempt-test");
        remote = accepted.toString();
        assertEquals(OperationVerificationResult.Status.APPROVED, provider.verify(request(true, "ticket")).status());
        assertEquals(Set.of("secret", "response"), new JSONObject(sent).keySet());
        for (String field : List.of("hostname", "action", "cdata")) {
            remote = new JSONObject(accepted.toString()).put(field, "wrong").toString();
            assertEquals(OperationVerificationResult.Status.REJECTED, provider.verify(request(true, "ticket")).status());
        }
        remote = accepted.put("success", false).toString();
        assertEquals(OperationVerificationResult.Status.REJECTED, provider.verify(request(true, "ticket")).status());
        assertEquals(exchanges, closed);
        int before = exchanges;
        assertEquals(OperationVerificationResult.Status.REJECTED, provider.verify(request(true, "x".repeat(2049))).status());
        assertEquals(before, exchanges);
        remote = "x".repeat(8193);
        assertEquals("CAPTCHA_REMOTE_UNAVAILABLE", assertThrows(IllegalStateException.class,
                () -> provider.verify(request(true, "ticket"))).getMessage());
        assertEquals(exchanges, closed);
    }
    @Test @DisplayName("官方测试密钥必须显式启用，正式配置不能接受测试标记")
    void explicitTestMode() {
        var provider = new TurnstileVerification(credentials, environment, http);
        values.put("captcha-example.turnstile-secret", "1x0000000000000000000000000000000AA");
        config.put("captcha-example.turnstile-sitekey", "1x00000000000000000000AA");
        assertThrows(IllegalStateException.class, () -> provider.verify(request(false, "")));
        config.put("captcha-example.turnstile-test-mode", "true");
        config.put("captcha-example.turnstile-hostname", "example.com");
        remote = new JSONObject().put("success", true).put("hostname", "example.com")
                .put("metadata", new JSONObject().put("result_with_testing_key", true)).toString();
        assertEquals(OperationVerificationResult.Status.APPROVED, provider.verify(request(true, "XXXX.DUMMY.TOKEN.XXXX")).status());
        assertTrue(new JSONObject(provider.verify(request(false, "")).challenge()).getBoolean("test"));
    }
}
