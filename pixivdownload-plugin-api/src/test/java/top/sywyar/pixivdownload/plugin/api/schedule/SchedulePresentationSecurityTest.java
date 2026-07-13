package top.sywyar.pixivdownload.plugin.api.schedule;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
                "作品", null, Map.of("excerpt", "PHPSESSID=secret")))
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
        for (String cookie : List.of(
                "wordpress_logged_in_abcd=opaque-value",
                ".ASPXAUTH=opaque-value",
                "FedAuth=opaque-value",
                "rtFa=opaque-value",
                "remember_me=opaque-value",
                "auth=opaque-value",
                "cf_clearance=opaque-value")) {
            assertThatThrownBy(() -> new ScheduledTaskPresentation(
                    "作品", null, Map.of("excerpt", cookie)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (String fieldName : List.of(
                "wordpress_logged_in_abcd", "FedAuth", "rtFa",
                "remember_me", "auth", "cf_clearance",
                "sid", "connect.sid", "connectSID", "sid_guard", "sid_guard_value",
                "sid_tt", "sid_tt_header", "ttwid",
                "odin_tt", "uid_tt", "s_v_web_id", "sessionid", "sessionid_ss",
                "msToken", "passport_csrf_token")) {
            assertThatThrownBy(() -> new ScheduledTaskPresentation(
                    "作品", null, Map.of(fieldName, "opaque-value")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new ScheduledTaskPresentation(
                "Authorization: Bearer secret", null, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
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
                        "sidCount", "0"));
        ScheduledWorkPresentation work = new ScheduledWorkPresentation(
                "作品", "作者", "https://example.invalid/thumbnail.jpg",
                Map.of(
                        "kind", "illust",
                        "signatureAlgorithm", "SHA-256",
                        "layout", "width=100; height=200; quality=90"));

        assertThat(task.attributes()).containsEntry("kind", "illust");
        assertThat(task.attributes()).containsEntry("tokenCount", "2");
        assertThat(task.attributes()).containsEntry("seriesId", "42");
        assertThat(task.attributes()).containsEntry("userId", "7");
        assertThat(task.attributes()).containsEntry("sidCount", "0");
        assertThat(work.attributes()).containsEntry("kind", "illust");
        assertThat(work.attributes()).containsEntry("signatureAlgorithm", "SHA-256");
        assertThatThrownBy(() -> task.attributes().put("extra", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> work.attributes().put("extra", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
