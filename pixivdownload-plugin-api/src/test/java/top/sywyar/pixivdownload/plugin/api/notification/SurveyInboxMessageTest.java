package top.sywyar.pixivdownload.plugin.api.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.web.WebUiSlotContribution;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("问卷站内信封装契约")
class SurveyInboxMessageTest {

    @Test
    @DisplayName("标题摘要与 HTML 正文地址通过现有 UI 槽位稳定往返")
    void roundTripsThroughExistingUiSlotContribution() {
        SurveyInboxMessage message = new SurveyInboxMessage(
                "example.survey",
                "campaign-v1",
                "/example-survey/embed.html?pixivBridgeRead=pixiv_theme",
                "example-survey",
                "inbox-title",
                "inbox-body",
                20);

        WebUiSlotContribution slot = message.toUiSlotContribution();

        assertThat(slot.slotId()).isEqualTo("example.survey");
        assertThat(slot.target()).isEqualTo("notification.inbox");
        assertThat(slot.moduleUrl()).isNull();
        assertThat(slot.order()).isEqualTo(20);
        assertThat(slot.metadata()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "notification.category", "survey",
                "notification.instance-key", "campaign-v1",
                "notification.embed-url", "/example-survey/embed.html?pixivBridgeRead=pixiv_theme",
                "notification.i18n-namespace", "example-survey",
                "notification.title-key", "inbox-title",
                "notification.body-key", "inbox-body"));
        assertThat(SurveyInboxMessage.fromUiSlotContribution(slot)).contains(message);
    }

    @Test
    @DisplayName("封装与解码都拒绝外部 HTML 正文地址")
    void rejectsExternalContentUrl() {
        assertThatThrownBy(() -> new SurveyInboxMessage(
                "example.survey", "campaign-v1", "https://evil.example/survey",
                "example-survey", "inbox-title", "inbox-body", 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same-origin");

        WebUiSlotContribution unsafe = new WebUiSlotContribution(
                "example.survey", "notification.inbox", null, 20,
                Map.of(
                        "notification.category", "survey",
                        "notification.instance-key", "campaign-v1",
                        "notification.embed-url", "https://evil.example/survey",
                        "notification.i18n-namespace", "example-survey",
                        "notification.title-key", "inbox-title",
                        "notification.body-key", "inbox-body"));

        assertThatThrownBy(() -> SurveyInboxMessage.fromUiSlotContribution(unsafe))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same-origin");

        WebUiSlotContribution incomplete = new WebUiSlotContribution(
                "example.survey", "notification.inbox", null, 20,
                Map.of(
                        "notification.category", "survey",
                        "notification.instance-key", "campaign-v1",
                        "notification.embed-url", "/example-survey/embed.html",
                        "notification.i18n-namespace", "example-survey",
                        "notification.title-key", "inbox-title"));

        assertThatThrownBy(() -> SurveyInboxMessage.fromUiSlotContribution(incomplete))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("incomplete");
    }

    @Test
    @DisplayName("消息标识和 i18n 键必须使用规范 token")
    void rejectsNonCanonicalTokens() {
        assertThatThrownBy(() -> new SurveyInboxMessage(
                "example.survey", " campaign-v1 ", "/example-survey/embed.html",
                "example-survey", "inbox-title", "inbox-body", 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("instance key");
    }
}
