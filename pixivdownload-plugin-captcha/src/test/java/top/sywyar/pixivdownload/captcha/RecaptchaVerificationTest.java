package top.sywyar.pixivdownload.captcha;

import java.io.ByteArrayInputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.JSONObject;
import org.junit.jupiter.api.*;
import org.springframework.core.env.*;
import top.sywyar.pixivdownload.plugin.api.credential.PluginCredentials;
import top.sywyar.pixivdownload.plugin.api.http.*;
import top.sywyar.pixivdownload.plugin.api.security.*;
import static org.junit.jupiter.api.Assertions.*;

class RecaptchaVerificationTest {
    private final Map<String, String> secrets = new HashMap<>();
    private final Map<String, Object> config = new HashMap<>();
    private final StandardEnvironment environment = new StandardEnvironment();
    private final PluginCredentials credentials = new PluginCredentials() {
        public Optional<String> read(String key) { return Optional.ofNullable(secrets.get(key)); }
        public void update(Map<String, String> updates) { secrets.putAll(updates); }
    };
    private String remote = "{\"success\":true,\"hostname\":\"captcha.example\"}", sent;
    private int calls, closed, status = 200;
    private final OutboundHttpClient http = new OutboundHttpClient() {
        public OutboundHttpStreamResponse exchangeStream(OutboundHttpRequest request) {
            calls++;
            assertEquals("https://www.google.com/recaptcha/api/siteverify", request.uri().toString());
            assertEquals("POST", request.method());
            assertTrue(request.headers().get("Content-Type").get(0).startsWith("application/x-www-form-urlencoded"));
            sent = new String(request.body(), StandardCharsets.UTF_8);
            return new OutboundHttpStreamResponse(status, "", Map.of(), new ByteArrayInputStream(remote.getBytes(StandardCharsets.UTF_8)) {
                public void close() { closed++; }
            });
        }
        public void close() { }
    };
    private final CaptchaDiagnostics diagnostics = new CaptchaDiagnostics(
            top.sywyar.pixivdownload.i18n.ResourceBundleMessageResolver.of(null, getClass().getClassLoader(),
                    new top.sywyar.pixivdownload.i18n.LocaleBundlePolicy() {
                        public Locale normalize(Locale value) { return Locale.US; }
                        public Locale fallbackLocale() { return Locale.US; }
                        public List<String> resourceSuffixChain(Locale value) { return List.of("en", ""); }
                    }, "i18n.web.captcha"));
    @BeforeEach void setup() {
        secrets.put("captcha.recaptcha-secret", "private-secret+&=中文");
        config.put("captcha.recaptcha-sitekey", "sitekey");
        config.put("captcha.recaptcha-hostname", "captcha.example");
        environment.getPropertySources().addFirst(new MapPropertySource("test", config));
    }
    private OperationVerificationRequest request(boolean submit, String proof) {
        return new OperationVerificationRequest("test:submit", "subject", "{}", "private-attempt", "revision", "", submit, proof);
    }
    private RecaptchaVerification provider() { return new RecaptchaVerification(credentials, environment, http, diagnostics); }

    @Test @DisplayName("v2 验签表单仅编码一次并强制检查主机和布尔结果")
    void remoteContract() {
        var provider = provider();
        assertEquals(OperationVerificationResult.Status.CHALLENGE, provider.verify(request(false, "")).status());
        assertEquals(0, calls);
        assertEquals(OperationVerificationResult.Status.APPROVED, provider.verify(request(true, "private-proof+&=中文")).status());
        var fields = new HashMap<String, String>();
        for (String field : sent.split("&")) {
            var pair = field.split("=", 2);
            fields.put(pair[0], URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
        }
        assertEquals(Map.of("secret", secrets.get("captcha.recaptcha-secret"), "response", "private-proof+&=中文"), fields);
        for (String invalid : List.of("{\"success\":\"true\",\"hostname\":\"captcha.example\"}",
                "{\"success\":false}", "{\"success\":true,\"hostname\":\"other.example\"}",
                "{\"success\":true,\"hostname\":\"captcha.example\",\"score\":0.9}",
                "{\"success\":true,\"hostname\":\"captcha.example\",\"action\":\"login\"}")) {
            remote = invalid;
            assertEquals(OperationVerificationResult.Status.REJECTED, provider.verify(request(true, "private-proof")).status());
        }
        int before = calls;
        for (String proof : List.of("", "x".repeat(16385)))
            assertEquals(OperationVerificationResult.Status.REJECTED, provider.verify(request(true, proof)).status());
        assertEquals(before, calls); assertEquals(calls, closed);
    }
    @Test @DisplayName("远端损坏与超限响应关闭连接，英文诊断不泄露凭据")
    void failuresAreBoundedAndRedacted() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(CaptchaDiagnostics.class);
        var capture = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        capture.start(); logger.addAppender(capture);
        Locale previous = Locale.getDefault();
        org.slf4j.MDC.put("operationVerificationId", "recaptcha-test");
        try {
            Locale.setDefault(Locale.KOREAN);
            var provider = provider();
            for (String invalid : List.of("private-response", "x".repeat(8193))) {
                remote = invalid;
                assertEquals("CAPTCHA_REMOTE_UNAVAILABLE", assertThrows(IllegalStateException.class,
                        () -> provider.verify(request(true, "private-proof"))).getMessage());
            }
            status = 503;
            assertThrows(IllegalStateException.class, () -> provider.verify(request(true, "private-proof")));
            assertEquals(calls, closed);
            assertEquals(3, capture.list.size());
            for (var event : capture.list) {
                assertEquals(ch.qos.logback.classic.Level.WARN, event.getLevel());
                assertNull(event.getThrowableProxy());
                assertTrue(event.getFormattedMessage().startsWith("CAPTCHA "));
                assertTrue(event.getFormattedMessage().contains("id=recaptcha-test"));
                assertFalse(event.getFormattedMessage().contains("private-"));
            }
        } finally { Locale.setDefault(previous); org.slf4j.MDC.remove("operationVerificationId"); logger.detachAppender(capture); capture.stop(); }
    }
    @Test @DisplayName("配置和密钥换代改变契约，官方测试密钥只能显式成对使用")
    void configurationAndTestKeys() {
        var provider = provider();
        String revision = provider.policy().revision(), generation = provider.generation();
        config.put("captcha.recaptcha-sitekey", "changed");
        assertNotEquals(revision, provider.policy().revision());
        secrets.put("captcha.recaptcha-secret", "rotated");
        assertNotEquals(generation, provider.generation());
        secrets.clear();
        assertThrows(IllegalStateException.class, () -> provider.verify(request(false, "")));
        secrets.put("captcha.recaptcha-secret", RecaptchaVerification.TEST_SECRET);
        config.put("captcha.recaptcha-sitekey", RecaptchaVerification.TEST_SITEKEY);
        assertThrows(IllegalStateException.class, () -> provider.verify(request(false, "")));
        config.put("captcha.recaptcha-test-mode", "true");
        assertTrue(new JSONObject(provider.verify(request(false, "")).challenge()).getBoolean("test"));
        remote = "{\"success\":true,\"hostname\":\"testkey.google.com\"}";
        assertEquals(OperationVerificationResult.Status.APPROVED, provider.verify(request(true, "test-ticket")).status());
        config.put("captcha.recaptcha-sitekey", "production");
        assertThrows(IllegalStateException.class, () -> provider.verify(request(false, "")));
    }
}
