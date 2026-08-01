package top.sywyar.pixivdownload.plugin.api.schedule;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.schedule.security.ScheduledCredentialText;
import top.sywyar.pixivdownload.plugin.api.schedule.security.ScheduledSensitiveFieldNames;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskPresentation;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkPresentation;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("计划任务展示快照安全契约")
class SchedulePresentationSecurityTest {

    @Test
    @DisplayName("任务展示字段和值都拒绝凭证材料")
    void taskPresentationRejectsCredentialMaterial() {
        assertThatThrownBy(() -> new ScheduledTaskPresentation(
                "作品", null, Map.of("cookie", "hidden")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScheduledTaskPresentation(
                "作品", null, Map.of("excerpt", "JSESSIONID=opaque-value")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScheduledTaskPresentation(
                "作品", null, Map.of("excerpt", "session_key=opaque-value")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScheduledTaskPresentation(
                "作品", null, Map.of("excerpt", "laravel_session=opaque-value")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScheduledTaskPresentation(
                "作品", null, Map.of("excerpt", "connect.sid=opaque-value")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScheduledTaskPresentation(
                "作品", null, Map.of("cookieValue", "opaque-value")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScheduledTaskPresentation(
                "作品", null, Map.of("sessionKeyValue", "opaque-value")))
                .isInstanceOf(IllegalArgumentException.class);
        for (String credential : List.of(
                ".ASPXAUTH=opaque-value",
                "FedAuth=opaque-value",
                "remember_me=opaque-value",
                "auth=opaque-value")) {
            assertThatThrownBy(() -> new ScheduledTaskPresentation(
                    "作品", null, Map.of("excerpt", credential)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (String fieldName : List.of(
                "FedAuth", "remember_me", "auth",
                "sid", "connect.sid", "connectSID",
                "sessionid",
                "msToken", "passport_csrf_token")) {
            assertThatThrownBy(() -> new ScheduledTaskPresentation(
                    "作品", null, Map.of(fieldName, "opaque-value")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new ScheduledTaskPresentation(
                "Authorization: Bearer secret", null, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScheduledTaskPresentation(
                "{\"token\":\"opaque-token-value\"}", null, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScheduledTaskPresentation(
                "作品", "{\"authorization\":\"Bearer opaque-value\"}", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScheduledTaskPresentation(
                "作品", null,
                Map.of("excerpt", "{\"cookie\":\"opaque-cookie-value\"}")))
                .isInstanceOf(IllegalArgumentException.class);
        for (String invalidMetadataAssignment : List.of(
                "tokenCount=opaque-token-value",
                "tokenCount!=opaque-token-value",
                "cookiePresent: opaque-cookie-value",
                "{\"sidCount\":\"opaque-session-value\"}",
                "{\"token Count\":\"opaque-token-value\"}")) {
            assertThatThrownBy(() -> new ScheduledTaskPresentation(
                    "作品", null, Map.of("excerpt", invalidMetadataAssignment)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("带框架前缀的 sessid 拼写由通用会话语义覆盖")
    void genericSessionSemanticCoversPrefixedSessidSpelling() {
        assertThat(ScheduledSensitiveFieldNames.isSensitiveFieldName("PHPSESSID")).isTrue();
        assertThat(ScheduledCredentialText.containsCredentialMaterial(
                "PHPSESSID=opaque-value")).isTrue();
        assertThatThrownBy(() -> new ScheduledTaskPresentation(
                "作品", null, Map.of("excerpt", "PHPSESSID=opaque-value")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("来源专属凭据名由所属插件校验而不进入稳定通用启发式")
    void sourceSpecificCredentialNamesRemainOwnerResponsibility() {
        for (String fieldName : List.of(
                "wordpress_logged_in_abcd", "rtFa", "cf_clearance",
                "sessionid_ss", "sid_guard", "sid_tt",
                "ttwid", "odin_tt", "uid_tt", "s_v_web_id")) {
            ScheduledTaskPresentation presentation = new ScheduledTaskPresentation(
                    "作品", null, Map.of(fieldName, "opaque-value"));

            assertThat(presentation.attributes()).containsEntry(fieldName, "opaque-value");
        }
        for (String assignment : List.of(
                "wordpress_logged_in_abcd=opaque-value",
                "rtFa=opaque-value",
                "cf_clearance=opaque-value",
                "sessionid_ss=opaque-value",
                "sid_guard=opaque-value",
                "sid_tt=opaque-value",
                "ttwid=opaque-value",
                "odin_tt=opaque-value",
                "uid_tt=opaque-value",
                "s_v_web_id=opaque-value")) {
            ScheduledTaskPresentation presentation = new ScheduledTaskPresentation(
                    "作品", null, Map.of("excerpt", assignment));

            assertThat(presentation.attributes()).containsEntry("excerpt", assignment);
        }
    }

    @Test
    @DisplayName("作品展示字段和值及临时签名引用都拒绝凭证材料")
    void workPresentationRejectsCredentialMaterial() {
        assertThatThrownBy(() -> new ScheduledWorkPresentation(
                "作品", "作者", null, Map.of("accessToken", "hidden")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScheduledWorkPresentation(
                "作品", "作者", null, Map.of("connect.sid", "opaque-value")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScheduledWorkPresentation(
                "作品", "作者", null, Map.of("excerpt", "Bearer abc.def")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScheduledWorkPresentation(
                "作品", "作者", "https://example.invalid/a?signature=secret", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("安全展示属性保持不可变快照")
    void safePresentationRemainsImmutable() {
        ScheduledTaskPresentation task = new ScheduledTaskPresentation(
                "Bearer of Light", "Bearer shares",
                Map.of(
                        "kind", "illust",
                        "seriesId", "42",
                        "userId", "7",
                        "tokenCount", "2",
                        "sidCount", "0",
                        "cookiePresent", "false"));
        ScheduledWorkPresentation work = new ScheduledWorkPresentation(
                "作品", "作者", "https://example.invalid/thumbnail.jpg",
                Map.of(
                        "kind", "illust",
                        "digestAlgorithm", "SHA-256",
                        "layout", "width=100; height=200; quality=90"));

        assertThat(task.attributes()).containsEntry("kind", "illust");
        assertThat(task.attributes()).containsEntry("tokenCount", "2");
        assertThat(task.attributes()).containsEntry("seriesId", "42");
        assertThat(task.attributes()).containsEntry("userId", "7");
        assertThat(task.attributes()).containsEntry("sidCount", "0");
        assertThat(task.attributes()).containsEntry("cookiePresent", "false");
        assertThat(work.attributes()).containsEntry("kind", "illust");
        assertThat(work.attributes()).containsEntry("digestAlgorithm", "SHA-256");
        assertThatThrownBy(() -> task.attributes().put("extra", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> work.attributes().put("extra", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new ScheduledTaskPresentation(
                "作品", null, Map.of("tokenCount", "opaque-token-value")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScheduledWorkPresentation(
                "作品", "作者", null, Map.of("cookiePresent", "opaque-cookie-value")))
                .isInstanceOf(IllegalArgumentException.class);
        for (String chainedFieldName : List.of(
                "tokenCountValue",
                "cookiePresentValue",
                "sidCountHeader",
                "tokenCountValuePresent",
                "cookiePresentValueCount",
                "sidCountHeaderBound",
                "tokenPresentCount",
                "cookieEnabledVersion",
                "sidCountPresent")) {
            assertThatThrownBy(() -> new ScheduledTaskPresentation(
                    "作品", null, Map.of(chainedFieldName, "opaque-credential-value")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new ScheduledWorkPresentation(
                    "作品", "作者", null,
                    Map.of(chainedFieldName, "opaque-credential-value")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (String sensitiveFieldName : List.of(
                "tokenCountValue",
                "cookiePresentValue",
                "sidCountHeader")) {
            for (String metadataSuffix : List.of(
                    "Required", "Present", "Bound", "Dependent", "Enabled",
                    "Count", "Algorithm", "Mode", "Type", "Version")) {
                String chainedFieldName = sensitiveFieldName + metadataSuffix;
                assertThat(ScheduledSensitiveFieldNames.isSensitiveFieldName(
                        chainedFieldName)).isTrue();
                assertThat(ScheduledSensitiveFieldNames
                        .isSensitiveMetadataFieldName(chainedFieldName)).isFalse();
            }
        }
        ScheduledTaskPresentation safeMetadataText = new ScheduledTaskPresentation(
                "作品", "tokenCount=2; cookiePresent: false", Map.of());
        assertThat(safeMetadataText.summary())
                .isEqualTo("tokenCount=2; cookiePresent: false");
    }
}
