package top.sywyar.pixivdownload.notificationbase;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.notification.NotificationSeverity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("管理员站内信服务")
class NotificationInboxServiceTest {

    @Test
    @DisplayName("公告与调查共用受控分类和安全链接模型")
    void publishesAnnouncementAndSurveyWithSafeLinks() {
        MemoryMapper mapper = new MemoryMapper();
        NotificationInboxService service = new NotificationInboxService(mapper);

        NotificationMessage announcement = service.publish(
                NotificationCategory.ANNOUNCEMENT, NotificationSeverity.INFO, null,
                "Maintenance", "Tonight at 22:00", "/pixiv-batch.html?tab=schedule");
        NotificationMessage survey = service.publish(
                NotificationCategory.SURVEY, NotificationSeverity.INFO, null,
                "Survey", "Tell us what you think", "https://example.test/survey");

        assertThat(announcement.category()).isEqualTo("announcement");
        assertThat(announcement.actionUrl()).isEqualTo("/pixiv-batch.html?tab=schedule");
        assertThat(survey.category()).isEqualTo("survey");
        assertThat(survey.actionUrl()).isEqualTo("https://example.test/survey");
        assertThat(service.unreadCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("危险或非规范链接在持久化前被拒绝")
    void rejectsUnsafeActionUrls() {
        NotificationInboxService service = new NotificationInboxService(new MemoryMapper());

        for (String url : List.of("javascript:alert(1)", "//evil.example/path", "/a/../admin", "file:///tmp/x")) {
            assertThatThrownBy(() -> service.publish(
                    NotificationCategory.ANNOUNCEMENT, NotificationSeverity.INFO, null,
                    "Title", "Body", url))
                    .as(url)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("已读标记幂等并更新未读数量")
    void marksMessageReadIdempotently() {
        MemoryMapper mapper = new MemoryMapper();
        NotificationInboxService service = new NotificationInboxService(mapper);
        NotificationMessage created = service.publish(
                NotificationCategory.SYSTEM, NotificationSeverity.WARNING, null,
                "Notice", "Body", null);

        NotificationMessage first = service.markRead(created.id());
        NotificationMessage second = service.markRead(created.id());

        assertThat(first.readTime()).isNotNull();
        assertThat(second.readTime()).isEqualTo(first.readTime());
        assertThat(service.unreadCount()).isZero();
    }

    static final class MemoryMapper implements NotificationInboxMapper {
        private final List<NotificationMessage> messages = new ArrayList<>();

        @Override
        public int insert(NotificationMessage message) {
            messages.add(message);
            return 1;
        }

        @Override
        public List<NotificationMessage> findLatest(String category, boolean unreadOnly, int limit) {
            return messages.stream()
                    .filter(message -> category == null || category.equals(message.category()))
                    .filter(message -> !unreadOnly || message.readTime() == null)
                    .sorted(Comparator.comparingLong(NotificationMessage::createdTime).reversed())
                    .limit(limit)
                    .toList();
        }

        @Override
        public NotificationMessage findById(String id) {
            return messages.stream().filter(message -> message.id().equals(id)).findFirst().orElse(null);
        }

        @Override
        public long countUnread(String category) {
            return messages.stream()
                    .filter(message -> category == null || category.equals(message.category()))
                    .filter(message -> message.readTime() == null)
                    .count();
        }

        @Override
        public int markRead(String id, long readTime) {
            for (int index = 0; index < messages.size(); index++) {
                NotificationMessage message = messages.get(index);
                if (message.id().equals(id) && message.readTime() == null) {
                    messages.set(index, new NotificationMessage(
                            message.id(), message.category(), message.severity(), message.scenarioId(),
                            message.title(), message.body(), message.actionUrl(), message.createdTime(),
                            Math.max(message.createdTime(), readTime)));
                    return 1;
                }
            }
            return 0;
        }

        @Override
        public int markAllRead(String category, long readTime) {
            int updated = 0;
            for (NotificationMessage message : List.copyOf(messages)) {
                if ((category == null || category.equals(message.category())) && message.readTime() == null) {
                    updated += markRead(message.id(), readTime);
                }
            }
            return updated;
        }
    }
}
