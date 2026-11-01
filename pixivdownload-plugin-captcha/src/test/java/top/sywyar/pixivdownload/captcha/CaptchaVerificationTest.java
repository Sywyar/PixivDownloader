package top.sywyar.pixivdownload.captcha;

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
    private final top.sywyar.pixivdownload.i18n.LocaleBundlePolicy locales = new top.sywyar.pixivdownload.i18n.LocaleBundlePolicy() {
        public Locale normalize(Locale locale) { return locale; }
        public Locale fallbackLocale() { return Locale.US; }
        public List<String> resourceSuffixChain(Locale locale) { return List.of("en", ""); }
    };
    private final top.sywyar.pixivdownload.i18n.MessageResolver messages =
            top.sywyar.pixivdownload.i18n.ResourceBundleMessageResolver.of(null, getClass().getClassLoader(), locales, "i18n.web.captcha");
    private final CaptchaDiagnostics diagnostics = new CaptchaDiagnostics(messages);
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
        values.put("captcha.altcha-secret", "test-only-altcha-key-with-at-least-32-characters");
        values.put("captcha.turnstile-secret", "test-private-credential");
        config.put("captcha.turnstile-sitekey", "production-sitekey");
        config.put("captcha.turnstile-hostname", "captcha.example");
        environment.getPropertySources().addFirst(new MapPropertySource("test", config));
    }
    private OperationVerificationRequest request(boolean response, String proof) {
        return new OperationVerificationRequest("unknown:submit", "subject", "{}", "attempt-test", "revision", "", response, proof);
    }
    @Test @DisplayName("验证码配置按方案下拉切换且保留独立字段和帮助文案")
    void configurationHelpBelongsToEachField(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) {
        var manager = new org.pf4j.DefaultPluginManager(root);
        var wrapper = new org.pf4j.PluginWrapper(manager, new org.pf4j.DefaultPluginDescriptor(), root, getClass().getClassLoader());
        var contribution = new CaptchaPlugin(wrapper).featurePlugin().guiConfigContributions().get(0);
        var fields = contribution.fields();
        assertEquals(Set.of("captcha.altcha-secret", "captcha.turnstile-sitekey", "captcha.turnstile-secret",
                "captcha.turnstile-hostname", "captcha.turnstile-test-mode", "captcha.recaptcha-sitekey",
                "captcha.recaptcha-secret", "captcha.recaptcha-hostname", "captcha.recaptcha-test-mode"),
                fields.stream().map(field -> field.key()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(fields.size(), fields.stream().map(field -> field.helpKey()).distinct().count());
        for (var field : fields) {
            assertFalse(field.helpKey().isBlank());
            assertNotEquals(field.helpKey(), messages.get(field.helpKey()));
        }
        assertEquals(1, contribution.sections().size());
        var section = contribution.sections().get(0);
        assertEquals(top.sywyar.pixivdownload.plugin.api.gui.GuiConfigSectionLayout.CARD_SWITCHER, section.layout());
        assertNotEquals(section.layoutLabelKey(), messages.get(section.layoutLabelKey()));
        assertNotEquals(section.layoutHelpKey(), messages.get(section.layoutHelpKey()));
        var cards = section.fieldLayouts().stream().collect(java.util.stream.Collectors.groupingBy(
                layout -> layout.cardId(),
                java.util.stream.Collectors.mapping(layout -> layout.fieldKey(), java.util.stream.Collectors.toSet())));
        assertEquals(Map.of(
                "altcha", Set.of("captcha.altcha-secret"),
                "turnstile", Set.of("captcha.turnstile-sitekey", "captcha.turnstile-secret", "captcha.turnstile-hostname", "captcha.turnstile-test-mode"),
                "recaptcha", Set.of("captcha.recaptcha-sitekey", "captcha.recaptcha-secret", "captcha.recaptcha-hostname", "captcha.recaptcha-test-mode")
        ), cards);
        assertEquals(fields.size(), section.fieldLayouts().size());
        section.fieldLayouts().forEach(layout -> assertNotEquals(layout.cardLabelKey(), messages.get(layout.cardLabelKey())));
        assertEquals(Set.of("captcha.altcha-secret", "captcha.turnstile-secret", "captcha.recaptcha-secret"),
                fields.stream().filter(field -> field.sensitive()).map(field -> field.key()).collect(java.util.stream.Collectors.toSet()));
        assertTrue(section.presets().isEmpty(), "切换配置页不应套用预设或修改验证策略");
    }
    @Test @DisplayName("非英文系统下诊断记录原因、字段和关联身份而不泄露秘密")
    void diagnosticsIdentifyConfigurationAndRemoteFailures() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(CaptchaDiagnostics.class);
        var capture = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        var locale = Locale.getDefault();
        capture.start(); logger.addAppender(capture);
        org.slf4j.MDC.put("operationVerificationId", "diagnostic-test");
        try {
            Locale.setDefault(Locale.JAPAN);
            var provider = new TurnstileVerification(credentials, environment, http, diagnostics);
            values.remove("captcha.turnstile-secret");
            assertThrows(IllegalStateException.class, () -> provider.verify(request(false, "")));
            values.put("captcha.turnstile-secret", "private-secret-sentinel");
            config.remove("captcha.turnstile-sitekey");
            assertThrows(IllegalStateException.class, () -> provider.verify(request(false, "")));
            config.put("captcha.turnstile-sitekey", "production-sitekey");
            remote = "private-response-sentinel";
            assertThrows(IllegalStateException.class, () -> provider.verify(request(true, "private-proof-sentinel")));
            remote = new JSONObject().put("success", true).put("hostname", "private-host-sentinel").toString();
            assertEquals(OperationVerificationResult.Status.REJECTED, provider.verify(request(true, "private-proof-sentinel")).status());
            var logs = capture.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList();
            assertEquals(4, logs.size());
            assertTrue(logs.stream().allMatch(log -> log.startsWith("CAPTCHA ") && log.contains("id=diagnostic-test") && log.contains("policy=captcha-turnstile")));
            assertTrue(logs.get(0).contains("reason=CAPTCHA_CREDENTIAL_MISSING") && logs.get(0).contains("field=captcha.turnstile-secret"));
            assertTrue(logs.get(1).contains("field=captcha.turnstile-sitekey"));
            assertTrue(logs.get(2).contains("reason=CAPTCHA_REMOTE_UNAVAILABLE"));
            assertTrue(logs.get(3).contains("reason=HOSTNAME_MISMATCH"));
            assertEquals(ch.qos.logback.classic.Level.WARN, capture.list.get(0).getLevel());
            assertTrue(capture.list.stream().allMatch(event -> event.getThrowableProxy() == null));
            assertFalse(String.join("\n", logs).contains("private-"));
            assertFalse(String.join("\n", logs).contains("attempt-test"));
        } finally {
            Locale.setDefault(locale); org.slf4j.MDC.remove("operationVerificationId");
            logger.detachAppender(capture); capture.stop();
        }
    }
    @Test @DisplayName("首次启用生成本地密钥，再次启用复用且不修改其它凭据")
    void initializesLocalKeyOnce() {
        values.remove("captcha.altcha-secret");
        new CaptchaPlugin.Wiring(credentials, messages, locales);
        String key = values.get("captcha.altcha-secret");
        assertEquals(32, HexFormat.of().parseHex(key).length);
        assertEquals("test-private-credential", values.get("captcha.turnstile-secret"));
        new CaptchaPlugin.Wiring(credentials, messages, locales);
        assertEquals(key, values.get("captcha.altcha-secret"));
        for (boolean pbkdf2 : List.of(false, true)) {
            assertEquals(OperationVerificationResult.Status.CHALLENGE,
                    new AltchaVerification(credentials, pbkdf2, diagnostics).verify(request(false, "")).status());
        }
        values.remove("captcha.altcha-secret");
        new CaptchaPlugin.Wiring(credentials, messages, locales);
        assertNotEquals(key, values.get("captcha.altcha-secret"));
    }
    @Test @DisplayName("已有无效密钥不被覆盖，离线验证拒绝执行")
    void preservesInvalidStoredKey() {
        values.put("captcha.altcha-secret", "invalid");
        new CaptchaPlugin.Wiring(credentials, messages, locales);
        assertEquals("invalid", values.get("captcha.altcha-secret"));
        assertEquals("CAPTCHA_CREDENTIAL_INVALID", assertThrows(IllegalStateException.class,
                () -> new AltchaVerification(credentials, false, diagnostics).verify(request(false, ""))).getMessage());
        assertEquals(OperationVerificationResult.Status.CHALLENGE,
                new TurnstileVerification(credentials, environment, http, diagnostics).verify(request(false, "")).status());
    }
    @Test @DisplayName("凭据读取或持久化失败时初始化失败，不使用临时密钥")
    void storageFailurePreventsInitialization() {
        for (boolean readFails : List.of(false, true)) {
            var unavailable = new PluginCredentials() {
                public Optional<String> read(String key) {
                    if (readFails) throw new IllegalStateException("READ_FAILED");
                    return Optional.empty();
                }
                public void update(Map<String, String> updates) { throw new IllegalStateException("WRITE_FAILED"); }
            };
            assertThrows(IllegalStateException.class, () -> new CaptchaPlugin.Wiring(unavailable, messages, locales));
        }
    }
    @Test @DisplayName("未配置方案可声明稳定代际，实际执行仍拒绝且不影响已配置方案")
    void independentCredentials() {
        var providers = List.of(new AltchaVerification(credentials, false, diagnostics), new AltchaVerification(credentials, true, diagnostics),
                new TurnstileVerification(credentials, environment, http, diagnostics));
        var configured = Map.copyOf(values);
        for (String key : List.of("captcha.altcha-secret", "captcha.turnstile-secret", "neither")) {
            values.clear();
            if (configured.containsKey(key)) values.put(key, configured.get(key));
            for (var provider : providers) {
                String generation = provider.generation();
                assertFalse(generation.isBlank());
                assertEquals(generation, provider.generation());
                boolean enabled = provider.policyId().startsWith("captcha-altcha")
                        ? key.equals("captcha.altcha-secret") : key.equals("captcha.turnstile-secret");
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
        var provider = new AltchaVerification(credentials, false, diagnostics);
        var challenge = new JSONObject(provider.verify(request(false, "")).challenge());
        assertEquals("attempt-test", challenge.getJSONObject("parameters").getJSONObject("data").getString("attempt"));
        for (String invalid : List.of("", "not-base64", "a".repeat(8193))) {
            assertEquals(OperationVerificationResult.Status.REJECTED, provider.verify(request(true, invalid)).status());
        }
        // counter=0 使官方库生成一个已知可解的测试挑战，不替代浏览器求解验收。
        var known = Altcha.createChallenge(new Altcha.CreateChallengeOptions().algorithm("SHA-256").cost(1)
                .counter(0).keyLength(32).keyPrefixLength(64).deriveKey(Altcha.sha()).expiresInSeconds(60)
                .hmacSignatureSecret(values.get("captcha.altcha-secret")).data(Map.of("attempt", "attempt-test")));
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
        values.put("captcha.altcha-secret", "another-test-only-key-with-at-least-32-characters");
        assertNotEquals(generation, provider.generation());
    }
    private String encoded(JSONObject payload) {
        return Base64.getEncoder().encodeToString(payload.toString().getBytes(StandardCharsets.UTF_8));
    }
    @Test @DisplayName("第三个 PBKDF2 适配使用独立策略并拒绝 SHA 证明")
    void pbkdf2Adapter() throws Exception {
        var provider = new AltchaVerification(credentials, true, diagnostics);
        var challenge = new JSONObject(provider.verify(request(false, "")).challenge());
        assertEquals("PBKDF2/SHA-256", challenge.getJSONObject("parameters").getString("algorithm"));
        assertNotEquals(new AltchaVerification(credentials, false, diagnostics).policyId(), provider.policyId());
        var known = Altcha.createChallenge(new Altcha.CreateChallengeOptions().algorithm("PBKDF2/SHA-256").cost(1000)
                .counter(0).keyLength(32).keyPrefixLength(64).deriveKey(Altcha.pbkdf2()).expiresInSeconds(60)
                .hmacSignatureSecret(values.get("captcha.altcha-secret")).data(Map.of("attempt", "attempt-test")));
        JSONObject payload = new JSONObject().put("challenge", new JSONObject(known.toJson()))
                .put("solution", new JSONObject().put("counter", 0).put("derivedKey", known.parameters().keyPrefix()).put("time", 0));
        assertEquals(OperationVerificationResult.Status.APPROVED, provider.verify(request(true, encoded(payload))).status());
        assertEquals(OperationVerificationResult.Status.REJECTED,
                new AltchaVerification(credentials, false, diagnostics).verify(request(true, encoded(payload))).status());
    }
    @Test @DisplayName("Turnstile 正式配置强制检查主机、动作、当前尝试和远端结果")
    void productionChecks() {
        var provider = new TurnstileVerification(credentials, environment, http, diagnostics);
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
        var provider = new TurnstileVerification(credentials, environment, http, diagnostics);
        values.put("captcha.turnstile-secret", "1x0000000000000000000000000000000AA");
        config.put("captcha.turnstile-sitekey", "1x00000000000000000000AA");
        assertThrows(IllegalStateException.class, () -> provider.verify(request(false, "")));
        config.put("captcha.turnstile-test-mode", "true");
        config.put("captcha.turnstile-hostname", "example.com");
        remote = new JSONObject().put("success", true).put("hostname", "example.com")
                .put("metadata", new JSONObject().put("result_with_testing_key", true)).toString();
        assertEquals(OperationVerificationResult.Status.APPROVED, provider.verify(request(true, "XXXX.DUMMY.TOKEN.XXXX")).status());
        assertTrue(new JSONObject(provider.verify(request(false, "")).challenge()).getBoolean("test"));
    }
}
