package top.sywyar.pixivdownload.captcha;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;
import org.springframework.core.env.Environment;
import top.sywyar.pixivdownload.plugin.api.credential.PluginCredentials;
import top.sywyar.pixivdownload.plugin.api.http.*;
import top.sywyar.pixivdownload.plugin.api.security.*;
import top.sywyar.pixivdownload.plugin.api.web.page.PageResourceProfile;

final class TurnstileVerification implements OperationVerification {
    private static final String ORIGIN = "https://challenges.cloudflare.com";
    private final PluginCredentials credentials;
    private final Environment environment;
    private final OutboundHttpClient http;
    private final CaptchaDiagnostics diagnostics;
    TurnstileVerification(PluginCredentials credentials, Environment environment, OutboundHttpClient http, CaptchaDiagnostics diagnostics) {
        this.credentials = credentials; this.environment = environment; this.http = http; this.diagnostics = diagnostics;
    }
    public String policyId() { return "captcha-turnstile"; }
    private String config(String name) { return environment.getProperty("captcha.turnstile-" + name, "").trim(); }
    public Policy policy() {
        return new Policy(AltchaVerification.fingerprint(config("sitekey") + "\n" + config("hostname") + "\n" + config("test-mode")),
                "", INTERACTION, List.of(new PageResourceProfile("turnstile", "1",
                        List.of(new PageResourceProfile.Source(PageResourceProfile.Kind.SCRIPT, ORIGIN),
                                new PageResourceProfile.Source(PageResourceProfile.Kind.FRAME, ORIGIN)))));
    }
    public String generation() {
        return credentials.read("captcha.turnstile-secret").map(AltchaVerification::fingerprint).orElse("unconfigured");
    }
    private String secret() {
        return credentials.read("captcha.turnstile-secret").filter(value -> !value.isBlank())
                .orElseThrow(() -> diagnostics.unavailable(policyId(), "CAPTCHA_CREDENTIAL_MISSING", "captcha.turnstile-secret"));
    }
    public OperationVerificationResult verify(OperationVerificationRequest request) {
        String secret = secret(), sitekey = config("sitekey"), hostname = config("hostname");
        boolean test = "true".equals(config("test-mode"));
        if (sitekey.isEmpty() || sitekey.length() > 128)
            throw diagnostics.unavailable(policyId(), "CAPTCHA_CONFIG_INVALID", "captcha.turnstile-sitekey");
        if (hostname.isEmpty() || hostname.length() > 253)
            throw diagnostics.unavailable(policyId(), "CAPTCHA_CONFIG_INVALID", "captcha.turnstile-hostname");
        boolean testSecret = secret.matches("[123]x0{31}AA");
        if (test != testSecret || (test && !sitekey.matches("[123]x0{20}(AA|AB|BB|FF)")))
            throw diagnostics.unavailable(policyId(), "CAPTCHA_CONFIG_INVALID", "captcha.turnstile-test-mode");
        if (!request.response()) return new OperationVerificationResult(OperationVerificationResult.Status.CHALLENGE,
                new JSONObject().put("sitekey", sitekey).put("action", "operation").put("cdata", request.attemptId())
                        .put("test", test).toString());
        if (request.proof().isBlank() || request.proof().length() > 2048) return AltchaVerification.rejected();
        var body = new JSONObject().put("secret", secret).put("response", request.proof()).toString().getBytes(StandardCharsets.UTF_8);
        try (var response = http.exchangeStream(new OutboundHttpRequest(URI.create(ORIGIN + "/turnstile/v0/siteverify"),
                "POST", Map.of("Content-Type", List.of("application/json")), body))) {
            byte[] bytes = response.body().readNBytes(8193);
            if (response.statusCode() != 200 || bytes.length > 8192) throw new IllegalStateException("CAPTCHA_REMOTE_UNAVAILABLE");
            var result = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            String rejected = !result.optBoolean("success") ? "SITEVERIFY_REJECTED"
                    : !hostname.equals(result.optString("hostname")) ? "HOSTNAME_MISMATCH" : "";
            if (test) {
                if (rejected.isEmpty() && !result.optJSONObject("metadata", new JSONObject()).optBoolean("result_with_testing_key"))
                    rejected = "TEST_MARKER_MISSING";
            } else if (rejected.isEmpty()) {
                if (!"operation".equals(result.optString("action"))) rejected = "ACTION_MISMATCH";
                else if (!request.attemptId().equals(result.optString("cdata"))) rejected = "ATTEMPT_MISMATCH";
                else if (result.optJSONObject("metadata", new JSONObject()).optBoolean("result_with_testing_key")) rejected = "TEST_MARKER_UNEXPECTED";
            }
            if (!rejected.isEmpty()) diagnostics.rejected(policyId(), rejected);
            return new OperationVerificationResult(rejected.isEmpty() ? OperationVerificationResult.Status.APPROVED
                    : OperationVerificationResult.Status.REJECTED, "");
        } catch (Exception failure) {
            // 外部异常可能回显票据，只记录稳定失败码。
            throw diagnostics.unavailable(policyId(), "CAPTCHA_REMOTE_UNAVAILABLE", "siteverify");
        }
    }
}
