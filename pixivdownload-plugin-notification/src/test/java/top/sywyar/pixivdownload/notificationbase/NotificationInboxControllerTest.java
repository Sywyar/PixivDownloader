package top.sywyar.pixivdownload.notificationbase;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import top.sywyar.pixivdownload.notification.NotificationSeverity;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("管理员站内信接口")
class NotificationInboxControllerTest {

    @Test
    @DisplayName("消息响应禁止浏览器与共享代理缓存")
    void responsesArePrivateAndNotStored() {
        NotificationInboxServiceTest.MemoryMapper mapper = new NotificationInboxServiceTest.MemoryMapper();
        NotificationInboxService service = new NotificationInboxService(mapper);
        service.publish(NotificationCategory.ANNOUNCEMENT, NotificationSeverity.INFO, null,
                "Maintenance", "Tonight at 22:00", "/pixiv-batch.html");
        NotificationInboxController controller = new NotificationInboxController(service);

        ResponseEntity<NotificationInboxController.InboxSnapshot> response = controller.latest(null, false, 20);

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
        NotificationInboxController.InboxSnapshot snapshot = controller.latest("download", true, 20).getBody();

        assertThat(marked.getBody()).isEqualTo(1);
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.unreadCount()).isEqualTo(1);
        assertThat(snapshot.categoryUnreadCount()).isZero();
        assertThat(snapshot.messages()).isEmpty();
    }
}
