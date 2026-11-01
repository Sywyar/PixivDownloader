package com.example.captcha;

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
    TurnstileVerification(PluginCredentials credentials, Environment environment, OutboundHttpClient http) {
        this.credentials = credentials; this.environment = environment; this.http = http;
    }
    public String policyId() { return "captcha-turnstile"; }
    private String config(String name) { return environment.getProperty("captcha-example.turnstile-" + name, "").trim(); }
    public Policy policy() {
        return new Policy(AltchaVerification.fingerprint(config("sitekey") + "\n" + config("hostname") + "\n" + config("test-mode")),
                "", INTERACTION, List.of(new PageResourceProfile("turnstile", "1",
                        List.of(new PageResourceProfile.Source(PageResourceProfile.Kind.SCRIPT, ORIGIN),
                                new PageResourceProfile.Source(PageResourceProfile.Kind.FRAME, ORIGIN)))));
    }
    public String generation() {
        return credentials.read("captcha-example.turnstile-secret").map(AltchaVerification::fingerprint).orElse("unconfigured");
    }
    private String secret() {
        return credentials.read("captcha-example.turnstile-secret").filter(value -> !value.isBlank())
                .orElseThrow(() -> new IllegalStateException("CAPTCHA_CREDENTIAL_MISSING"));
    }
    public OperationVerificationResult verify(OperationVerificationRequest request) {
        String secret = secret(), sitekey = config("sitekey"), hostname = config("hostname");
        boolean test = "true".equals(config("test-mode"));
        if (sitekey.isEmpty() || hostname.isEmpty() || sitekey.length() > 128 || hostname.length() > 253)
            throw new IllegalStateException("CAPTCHA_CONFIG_INVALID");
        boolean testSecret = secret.matches("[123]x0{31}AA");
        if (test != testSecret || (test && !sitekey.matches("[123]x0{20}(AA|AB|BB|FF)")))
            throw new IllegalStateException("CAPTCHA_CONFIG_INVALID");
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
            boolean approved = result.optBoolean("success") && hostname.equals(result.optString("hostname"));
            if (test) {
                approved &= result.optJSONObject("metadata") != null
                        && result.getJSONObject("metadata").optBoolean("result_with_testing_key");
            } else {
                approved &= "operation".equals(result.optString("action")) && request.attemptId().equals(result.optString("cdata"))
                        && !result.optJSONObject("metadata", new JSONObject()).optBoolean("result_with_testing_key");
            }
            return new OperationVerificationResult(approved ? OperationVerificationResult.Status.APPROVED
                    : OperationVerificationResult.Status.REJECTED, "");
        } catch (Exception failure) {
            // 外部异常可能回显票据，只让宿主记录稳定失败码。
            throw new IllegalStateException("CAPTCHA_REMOTE_UNAVAILABLE");
        }
    }
}
