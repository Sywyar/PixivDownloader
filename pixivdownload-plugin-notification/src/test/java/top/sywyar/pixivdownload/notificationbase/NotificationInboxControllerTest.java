package top.sywyar.pixivdownload.notificationbase;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import top.sywyar.pixivdownload.notification.NotificationSeverity;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("管理员站内信接口")
class NotificationInboxControllerTest {

    private static final String CONTENT_URL =
            "https://sywyar.github.io/PixivDownloader-Remote-Content/announcements/2026-08-12.html";

    @Test
    @DisplayName("消息响应禁止浏览器与共享代理缓存")
    void responsesArePrivateAndNotStored() {
        NotificationInboxServiceTest.MemoryMapper mapper = new NotificationInboxServiceTest.MemoryMapper();
        NotificationInboxService service = new NotificationInboxService(mapper);
        service.publish(NotificationCategory.ANNOUNCEMENT, NotificationSeverity.INFO, null,
                "Maintenance", "Tonight at 22:00", "/pixiv-batch.html");
        NotificationInboxController controller = new NotificationInboxController(service);

        ResponseEntity<NotificationInboxController.InboxSnapshot> response = controller.latest(null, false, 20, null);

        assertThat(response.getHeaders().getCacheControl()).contains("no-store", "private");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().messages()).hasSize(1);
        assertThat(response.getBody().categoryUnreadCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("分类未读筛选与当前分类全部已读互不影响其它分类")
    void filtersAndMarksCurrentCategoryRead() {
        NotificationInboxServiceTest.MemoryMapper mapper = new NotificationInboxServiceTest.MemoryMapper();
        NotificationInboxService service = new NotificationInboxService(mapper);
        service.publish(NotificationCategory.DOWNLOAD, NotificationSeverity.INFO, null, "Download", "Body", null);
        service.publish(NotificationCategory.SYSTEM, NotificationSeverity.WARNING, null, "System", "Body", null);
        NotificationInboxController controller = new NotificationInboxController(service);

        ResponseEntity<Integer> marked = controller.markAllRead("download");
        NotificationInboxController.InboxSnapshot snapshot = controller.latest("download", true, 20, null).getBody();

        assertThat(marked.getBody()).isEqualTo(1);
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.unreadCount()).isEqualTo(1);
        assertThat(snapshot.categoryUnreadCount()).isZero();
        assertThat(snapshot.messages()).isEmpty();
    }

    @Test
    @DisplayName("显式删除消息后接口不再返回该消息")
    void deletesMessage() {
        NotificationInboxServiceTest.MemoryMapper mapper = new NotificationInboxServiceTest.MemoryMapper();
        NotificationInboxService service = new NotificationInboxService(mapper);
        NotificationMessage message = service.publish(
                NotificationCategory.ANNOUNCEMENT,
                NotificationSeverity.INFO,
                null,
                "Announcement",
                "Body",
                null);
        NotificationInboxController controller = new NotificationInboxController(service);

        ResponseEntity<Void> response = controller.delete(message.id());

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        assertThat(response.getHeaders().getCacheControl()).contains("no-store", "private");
        assertThat(service.find(message.id())).isNull();
    }

    @Test
    @DisplayName("任意分类的 HTML 正文通过本地隔离端点返回")
    void servesStoredHtmlForAnyCategory() {
        NotificationInboxServiceTest.MemoryMapper mapper = new NotificationInboxServiceTest.MemoryMapper();
        NotificationInboxService service = new NotificationInboxService(mapper);
        NotificationMessage message = service.publishHtml(
                NotificationCategory.SURVEY,
                NotificationSeverity.INFO,
                null,
                "Survey",
                "Summary",
                null,
                new NotificationHtmlContent(CONTENT_URL, "<!doctype html><p>Survey body</p>"));
        NotificationInboxController controller = new NotificationInboxController(service);

        ResponseEntity<String> response = controller.htmlContent(message.id(), null);

        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("text/html;charset=UTF-8");
        assertThat(response.getHeaders().getCacheControl()).contains("no-store", "private");
        String csp = response.getHeaders().getFirst("Content-Security-Policy");
        Matcher nonceMatcher = Pattern.compile("script-src 'nonce-([a-f0-9]{32})'").matcher(csp);
        assertThat(nonceMatcher.find()).isTrue();
        String nonce = nonceMatcher.group(1);
        assertThat(csp).contains("default-src 'none'", "sandbox allow-scripts")
                .doesNotContain("allow-same-origin", "script-src 'none'");
        assertThat(response.getBody())
                .startsWith("<!doctype html><script nonce=\"" + nonce + "\" data-source=\"" + CONTENT_URL + "\">")
                .contains("parent.postMessage({", "type: 'pixiv-external-link'",
                        "type: 'pixiv-content-height'", "heightObserver = new ResizeObserver(reportHeight)",
                        "<p>Survey body</p>")
                .endsWith("<p>Survey body</p>");
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    @Test
    @DisplayName("远程公告正文端点按请求语言返回同一逻辑公告的翻译")
    void servesLocalizedRemoteAnnouncementHtml() {
        NotificationInboxServiceTest.MemoryMapper mapper = new NotificationInboxServiceTest.MemoryMapper();
        NotificationInboxService service = new NotificationInboxService(mapper);
        String base = "https://sywyar.github.io/PixivDownloader-Remote-Content/announcements/localized/";
        assertThat(service.storeRemoteAnnouncement(
                "localized", NotificationSeverity.INFO,
                List.of(
                        new RemoteAnnouncementTranslation(
                                "zh-CN", "中文", "中文摘要", base + "zh-CN.html", "<p>中文正文</p>"),
                        new RemoteAnnouncementTranslation(
                                "en-US", "English", "English summary", base + "en-US.html",
                                "<p>English body</p>")),
                1)).isTrue();
        NotificationInboxController controller = new NotificationInboxController(service);

        ResponseEntity<String> chinese = controller.htmlContent("remote-announcement:localized", "zh-CN");
        ResponseEntity<String> english = controller.htmlContent("remote-announcement:localized", "en-US");

        assertThat(chinese.getBody()).contains("<p>中文正文</p>").doesNotContain("<p>English body</p>");
        assertThat(english.getBody()).contains("<p>English body</p>").doesNotContain("<p>中文正文</p>");
    }

    @Test
    @DisplayName("消息 JSON 只公开 HTML 可用标记，不内联正文")
    void keepsHtmlOutOfMessageJson() throws JsonProcessingException {
        NotificationInboxService service = new NotificationInboxService(
                new NotificationInboxServiceTest.MemoryMapper());
        NotificationMessage message = service.publishHtml(
                NotificationCategory.SURVEY,
                NotificationSeverity.INFO,
                null,
                "Survey",
                "Summary",
                null,
                new NotificationHtmlContent(null, "<p>private HTML</p>"));

        String json = new ObjectMapper().writeValueAsString(message);

        assertThat(json).contains("\"hasHtmlContent\":true")
                .doesNotContain("contentHtml", "private HTML");
    }
}
