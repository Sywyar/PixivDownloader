package com.example.captcha;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.altcha.altcha.v2.Altcha;
import top.sywyar.pixivdownload.plugin.api.credential.PluginCredentials;
import top.sywyar.pixivdownload.plugin.api.security.*;
import top.sywyar.pixivdownload.plugin.api.web.page.PageResourceProfile;

final class AltchaVerification implements OperationVerification {
    private final PluginCredentials credentials;
    private final boolean pbkdf2;
    AltchaVerification(PluginCredentials credentials, boolean pbkdf2) { this.credentials = credentials; this.pbkdf2 = pbkdf2; }
    private String algorithm() { return pbkdf2 ? "PBKDF2/SHA-256" : "SHA-256"; }
    private int cost() { return pbkdf2 ? 1000 : 1; }
    private Altcha.KeyDerivationFunction derivation() { return pbkdf2 ? Altcha.pbkdf2() : Altcha.sha(); }
    public String policyId() { return pbkdf2 ? "captcha-altcha-pbkdf2" : "captcha-altcha"; }
    public Policy policy() {
        return new Policy(pbkdf2 ? "pbkdf2-sha256-cost1000-prefix2-expiry120-v1" : "sha256-cost1-prefix4-expiry120-v1", "", INTERACTION,
                List.of(new PageResourceProfile("local-worker", "1",
                        List.of(new PageResourceProfile.Source(PageResourceProfile.Kind.WORKER, "self")))));
    }
    public String generation() {
        return credentials.read("captcha-example.altcha-secret").map(AltchaVerification::fingerprint).orElse("unconfigured");
    }
    private String secret() {
        String value = credentials.read("captcha-example.altcha-secret").orElseThrow(() -> new IllegalStateException("CAPTCHA_CREDENTIAL_MISSING"));
        if (value.length() < 32) throw new IllegalStateException("CAPTCHA_CREDENTIAL_INVALID");
        return value;
    }
    public OperationVerificationResult verify(OperationVerificationRequest request) {
        try {
            String secret = secret();
            if (!request.response()) {
                var options = new Altcha.CreateChallengeOptions().algorithm(algorithm()).cost(cost())
                        .keyLength(32).keyPrefixLength(pbkdf2 ? 2 : 4).expiresInSeconds(120).deriveKey(derivation())
                        .hmacSignatureSecret(secret).data(Map.of("attempt", request.attemptId()));
                return new OperationVerificationResult(OperationVerificationResult.Status.CHALLENGE,
                        Altcha.createChallenge(options).toJson());
            }
            if (request.proof().length() > 8192) return rejected();
            var payload = Altcha.parsePayload(request.proof());
            var parameters = payload.challenge().parameters();
            // 先限制不可信计算参数，签名和工作量仍由官方库验证。
            if (!algorithm().equals(parameters.algorithm()) || parameters.cost() != cost()
                    || parameters.keyLength() != 32 || !Map.of("attempt", request.attemptId()).equals(parameters.data()))
                return rejected();
            boolean approved = Altcha.verifySolution(request.proof(), secret, derivation()).verified();
            return new OperationVerificationResult(approved ? OperationVerificationResult.Status.APPROVED
                    : OperationVerificationResult.Status.REJECTED, "");
        } catch (IllegalStateException failure) {
            throw failure;
        } catch (Exception invalid) {
            return rejected();
        }
    }
    static OperationVerificationResult rejected() {
        return new OperationVerificationResult(OperationVerificationResult.Status.REJECTED, "");
    }
    static String fingerprint(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("CAPTCHA_DIGEST_UNAVAILABLE"); }
    }
}
