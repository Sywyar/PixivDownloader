package top.sywyar.pixivdownload.captcha;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.json.JSONObject;
import org.springframework.core.env.Environment;
import top.sywyar.pixivdownload.plugin.api.credential.PluginCredentials;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpClient;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpRequest;
import top.sywyar.pixivdownload.plugin.api.security.OperationVerification;
import top.sywyar.pixivdownload.plugin.api.security.OperationVerificationRequest;
import top.sywyar.pixivdownload.plugin.api.security.OperationVerificationResult;
import top.sywyar.pixivdownload.plugin.api.web.page.PageResourceProfile;

final class RecaptchaVerification implements OperationVerification {
    static final String TEST_SITEKEY = "6LeIxAcTAAAAAJcZVRqyHh71UMIEGNQ_MXjiZKhI";
    static final String TEST_SECRET = "6LeIxAcTAAAAAGG-vFI1TnRWxMZNFuojJ4WifJWe";
    private static final String ORIGIN = "https://www.google.com";
    private final PluginCredentials credentials;
    private final Environment environment;
    private final OutboundHttpClient http;
    private final CaptchaDiagnostics diagnostics;

    RecaptchaVerification(PluginCredentials credentials, Environment environment, OutboundHttpClient http, CaptchaDiagnostics diagnostics) {
        this.credentials = credentials;
        this.environment = environment;
        this.http = http;
        this.diagnostics = diagnostics;
    }

    public String policyId() {
        return "captcha-recaptcha";
    }

    private String config(String name) {
        return environment.getProperty("captcha.recaptcha-" + name, "").trim();
    }

    public Policy policy() {
        return new Policy(AltchaVerification.fingerprint(config("sitekey") + "\n" + config("hostname") + "\n" + config("test-mode")),
                "", INTERACTION, List.of(new PageResourceProfile("recaptcha", "1", List.of(
                new PageResourceProfile.Source(PageResourceProfile.Kind.SCRIPT, ORIGIN),
                new PageResourceProfile.Source(PageResourceProfile.Kind.SCRIPT, "https://www.gstatic.com"),
                new PageResourceProfile.Source(PageResourceProfile.Kind.FRAME, ORIGIN),
                new PageResourceProfile.Source(PageResourceProfile.Kind.FRAME, "https://recaptcha.google.com"),
                new PageResourceProfile.Source(PageResourceProfile.Kind.CONNECT, ORIGIN)))));
    }

    public String generation() {
        return credentials.read("captcha.recaptcha-secret").map(AltchaVerification::fingerprint).orElse("unconfigured");
    }

    public OperationVerificationResult verify(OperationVerificationRequest request) {
        String secret = credentials.read("captcha.recaptcha-secret").filter(value -> !value.isBlank())
                .orElseThrow(() -> diagnostics.unavailable(policyId(), "CAPTCHA_CREDENTIAL_MISSING", "captcha.recaptcha-secret"));
        String sitekey = config("sitekey"), hostname = config("hostname");
        boolean test = "true".equals(config("test-mode"));
        if (sitekey.isEmpty() || sitekey.length() > 128)
            throw diagnostics.unavailable(policyId(), "CAPTCHA_CONFIG_INVALID", "captcha.recaptcha-sitekey");
        if (!test && (hostname.isEmpty() || hostname.length() > 253))
            throw diagnostics.unavailable(policyId(), "CAPTCHA_CONFIG_INVALID", "captcha.recaptcha-hostname");
        if (test ? !TEST_SECRET.equals(secret) || !TEST_SITEKEY.equals(sitekey)
                : TEST_SECRET.equals(secret) || TEST_SITEKEY.equals(sitekey))
            throw diagnostics.unavailable(policyId(), "CAPTCHA_CONFIG_INVALID", "captcha.recaptcha-test-mode");
        if (!request.response()) return new OperationVerificationResult(OperationVerificationResult.Status.CHALLENGE,
                new JSONObject().put("sitekey", sitekey).put("test", test).toString());
        if (request.proof().isBlank() || request.proof().length() > 16384) return AltchaVerification.rejected();
        String form = "secret=" + URLEncoder.encode(secret, StandardCharsets.UTF_8)
                + "&response=" + URLEncoder.encode(request.proof(), StandardCharsets.UTF_8);
        try (var response = http.exchangeStream(new OutboundHttpRequest(URI.create(ORIGIN + "/recaptcha/api/siteverify"),
                "POST", Map.of("Content-Type", List.of("application/x-www-form-urlencoded; charset=UTF-8")),
                form.getBytes(StandardCharsets.UTF_8)))) {
            byte[] bytes = response.body().readNBytes(8193);
            if (response.statusCode() != 200 || bytes.length > 8192)
                throw new IllegalStateException("CAPTCHA_REMOTE_UNAVAILABLE");
            var result = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            String reason = !Boolean.TRUE.equals(result.opt("success")) ? "SITEVERIFY_REJECTED"
                    : !(test ? "testkey.google.com" : hostname).equals(result.optString("hostname")) ? "HOSTNAME_MISMATCH"
                    : result.has("score") || result.has("action") ? "RECAPTCHA_VERSION_MISMATCH" : "";
            if (!reason.isEmpty()) diagnostics.rejected(policyId(), reason);
            return new OperationVerificationResult(reason.isEmpty() ? OperationVerificationResult.Status.APPROVED
                    : OperationVerificationResult.Status.REJECTED, "");
        } catch (Exception failure) {
            // 厂商异常可能包含密钥或票据，日志只保留固定原因。
            throw diagnostics.unavailable(policyId(), "CAPTCHA_REMOTE_UNAVAILABLE", "siteverify");
        }
    }
}
