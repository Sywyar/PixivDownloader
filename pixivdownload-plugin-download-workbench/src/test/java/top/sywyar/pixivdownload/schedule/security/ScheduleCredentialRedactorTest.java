package top.sywyar.pixivdownload.schedule.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("计划任务凭证文本脱敏")
class ScheduleCredentialRedactorTest {

    @Test
    @DisplayName("完整脱敏 Cookie、Basic、Bearer、token、credential 与签名参数")
    void redactsSupportedCredentialFormsWithoutLeavingHeaderValues() {
        String raw = "Cookie: session_id=cookie-secret; device=device-secret\n"
                + "Authorization: Basic basic-secret\n"
                + "Proxy-Authorization: Bearer proxy-secret\n"
                + "token: token-secret access_token=access-secret\n"
                + "{\"token\":\"json-token-secret\","
                + "\"tokenCount\":\"metadata-token-secret\"}\n"
                + "https://example.test/a?X-Amz-Credential=credential-secret"
                + "&X-Amz-Signature=signature-secret";

        String redacted = ScheduleCredentialRedactor.redact(raw);

        assertThat(redacted).contains("[redacted]")
                .doesNotContain(
                        "cookie-secret", "device-secret", "basic-secret", "proxy-secret",
                        "token-secret", "access-secret", "json-token-secret",
                        "metadata-token-secret", "credential-secret", "signature-secret");
        assertThat(ScheduleCredentialRedactor.containsCredentialMaterial(redacted)).isFalse();
        assertThat(ScheduleCredentialRedactor.redact(redacted)).isEqualTo(redacted);
    }

    @Test
    @DisplayName("安全凭证元数据保持可读而非法值被脱敏")
    void preservesSafeCredentialMetadataAndRedactsInvalidValues() {
        String safe = "tokenCount=2; cookiePresent=false; "
                + "tokenCount=\"3\"; {\"sidCount\":0,\"cookieBound\":true}";

        assertThat(ScheduleCredentialRedactor.redact(safe)).isEqualTo(safe);
        assertThat(ScheduleCredentialRedactor.redact(
                "tokenCount=opaque-token; {\"cookiePresent\":\"opaque-cookie\"}"))
                .doesNotContain("opaque-token", "opaque-cookie")
                .contains("[redacted]");
    }

    @Test
    @DisplayName("递归载荷检查所需的字段名和值识别不误报普通来源文本")
    void recognizesCredentialMaterialAndLeavesOrdinaryTextAlone() {
        assertThat(ScheduleCredentialRedactor.isSensitiveFieldName("refresh_token")).isTrue();
        assertThat(ScheduleCredentialRedactor.isSensitiveFieldName("x-amz-credential")).isTrue();
        assertThat(ScheduleCredentialRedactor.isSensitiveFieldName("session_key")).isTrue();
        assertThat(ScheduleCredentialRedactor.isSensitiveFieldName("tokenCountValue")).isTrue();
        assertThat(ScheduleCredentialRedactor.isSensitiveFieldName("cookiePresentValue")).isTrue();
        assertThat(ScheduleCredentialRedactor.isSensitiveFieldName("sidCountHeader")).isTrue();
        assertThat(ScheduleCredentialRedactor.isSensitiveFieldName("tokenPresentCount")).isTrue();
        assertThat(ScheduleCredentialRedactor.isSensitiveFieldName("cookieEnabledVersion")).isTrue();
        assertThat(ScheduleCredentialRedactor.isSensitiveFieldName("sidCountPresent")).isTrue();
        assertThat(ScheduleCredentialRedactor.isSensitiveFieldName("word")).isFalse();
        assertThat(ScheduleCredentialRedactor.containsCredentialMaterial(
                "Proxy-Authorization: Basic basic-secret")).isTrue();
        assertThat(ScheduleCredentialRedactor.containsCredentialMaterial(
                "https://example.test/a?sig=signature-secret")).isTrue();
        assertThat(ScheduleCredentialRedactor.containsCredentialMaterial(
                "tokenCount=opaque-token-value")).isTrue();
        assertThat(ScheduleCredentialRedactor.containsCredentialMaterial(
                "{\"cookiePresent\":\"opaque-cookie-value\"}")).isTrue();
        assertThat(ScheduleCredentialRedactor.containsCredentialMaterial(
                "tokenCount=2; cookiePresent=false")).isFalse();
        assertThat(ScheduleCredentialRedactor.containsCredentialMaterial("ordinary search text")).isFalse();
    }

    @Test
    @DisplayName("未知来源键值对不需要宿主识别字段名也会脱敏")
    void redactsUnknownSourceKeyValuePairWithoutSourceSpecificNames() {
        assertThat(ScheduleCredentialRedactor.redact(
                "vendor_identity=opaque-source-secret"))
                .isEqualTo("[redacted]");
    }
}
