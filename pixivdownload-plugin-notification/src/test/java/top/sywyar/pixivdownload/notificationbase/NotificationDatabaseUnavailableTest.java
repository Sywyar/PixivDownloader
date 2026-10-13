package top.sywyar.pixivdownload.notificationbase;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.core.database.DatabaseUnavailableException;
import top.sywyar.pixivdownload.notification.NotificationSeverity;
import top.sywyar.pixivdownload.plugin.api.notification.SurveyInboxMessage;
import top.sywyar.pixivdownload.plugin.api.web.WebUiSlotContribution;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("通知数据库不可用语义")
class NotificationDatabaseUnavailableTest {
    @Test
    @DisplayName("缺库时构造和展示查询不访问 Mapper，列表明确标记数据库不可用")
    void initializesAndReadsWithoutDatabaseCalls() {
        AtomicInteger calls = new AtomicInteger();
        var inbox = new NotificationInboxService(rejectingMapper(calls), () -> false);

        inbox.synchronizePersistentSurveys();
        assertThat(inbox.latest(null, false, 20)).isEmpty();
        assertThat(inbox.unreadCount()).isZero();
        assertThat(inbox.unreadCount(NotificationCategory.SYSTEM)).isZero();
        assertThat(inbox.find("existing")).isNull();
        assertThat(inbox.htmlContent("existing")).isNull();
        var snapshot = new NotificationInboxController(inbox).latest(null, false, 20, "en-US").getBody();
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.databaseAvailable()).isFalse();
        assertThat(snapshot.messages()).isEmpty();
        assertThat(snapshot.unreadCount()).isZero();
        assertThat(snapshot.categoryUnreadCount()).isZero();
        assertThat(calls).hasValue(0);
    }

    @Test
    @DisplayName("缺库时写入、删除、维护和导入判断明确拒绝且没有 Mapper 副作用")
    void rejectsCommandsAndImportDecisions() {
        AtomicInteger calls = new AtomicInteger();
        var inbox = new NotificationInboxService(rejectingMapper(calls), () -> false);
        List<Runnable> commands = List.of(
                () -> inbox.publish(NotificationCategory.SYSTEM, NotificationSeverity.INFO,
                        null, "Title", "Body", null),
                () -> inbox.storeRemoteAnnouncement("example", NotificationSeverity.INFO, List.of(), 1),
                () -> inbox.needsRemoteAnnouncementImport("example", NotificationSeverity.INFO, 1, List.of()),
                () -> inbox.acceptRemoteAnnouncementIndex(1, "0".repeat(64), 1, 2),
                () -> inbox.remoteAnnouncementValidators(1),
                () -> inbox.saveRemoteAnnouncementValidators("0".repeat(64), null, null),
                () -> inbox.markRead("existing"),
                () -> inbox.markAllRead(null),
                () -> inbox.delete("existing"),
                () -> inbox.dismissUnavailableSurvey("persistent-survey:example:one"),
                inbox::pruneRetentionPool,
                () -> new NotificationInboxRetentionTask(inbox).execute(null));

        for (Runnable command : commands) {
            assertThatThrownBy(command::run).isInstanceOf(DatabaseUnavailableException.class)
                    .hasMessage("DATABASE_UNAVAILABLE");
        }
        assertThat(calls).hasValue(0);
    }

    @Test
    @DisplayName("失去数据库后保留调查活动状态、通知内容和已读事实")
    void keepsPersistentFactsWhenDatabaseDisappears() {
        AtomicBoolean available = new AtomicBoolean(true);
        WebUiSlotContribution survey = new SurveyInboxMessage(
                "example.survey", "one", "/example-survey.html", "example",
                "example.title", "example.body", 1).toUiSlotContribution();
        AtomicReference<List<WebUiSlotContribution>> slots = new AtomicReference<>(List.of(survey));
        var mapper = new NotificationInboxServiceTest.MemoryMapper();
        var inbox = new NotificationInboxService(mapper, available::get, () -> 500, () -> 90,
                slots::get, (namespace, locale, key) -> Optional.empty(), locale -> locale);
        NotificationMessage message = inbox.publish(NotificationCategory.SYSTEM, NotificationSeverity.INFO,
                null, "Existing", "Keep this message", null);
        NotificationMessage read = inbox.markRead(message.id());
        String surveyId = "persistent-survey:example.survey:one";
        NotificationMessage surveyRead = inbox.markRead(surveyId);
        available.set(false);
        slots.set(List.of());

        inbox.synchronizePersistentSurveys();
        assertThatThrownBy(() -> inbox.delete(message.id())).isInstanceOf(DatabaseUnavailableException.class);
        assertThatThrownBy(() -> inbox.dismissUnavailableSurvey(surveyId))
                .isInstanceOf(DatabaseUnavailableException.class);

        assertThat(mapper.findById(message.id())).isEqualTo(read);
        assertThat(mapper.findById(surveyId)).isEqualTo(surveyRead);
        assertThat(inbox.latest(null, false, 20)).isEmpty();
        assertThat(inbox.isDatabaseAvailable()).isFalse();
    }

    @Test
    @DisplayName("读取调查贡献时数据库失效，不把空贡献写成活动调查清单")
    void stopsBeforeSynchronizingAfterContributionInvalidates() {
        AtomicInteger calls = new AtomicInteger();
        AtomicBoolean available = new AtomicBoolean(false);
        var inbox = new NotificationInboxService(rejectingMapper(calls), available::get, () -> 500, () -> 90,
                () -> { available.set(false); return List.of(); },
                (namespace, locale, key) -> Optional.empty(), locale -> locale);
        available.set(true);

        assertThatThrownBy(inbox::synchronizePersistentSurveys).isInstanceOf(DatabaseUnavailableException.class);

        assertThat(calls).hasValue(0);
    }

    @Test
    @DisplayName("消息写入后数据库失效，不继续清理保留池且不伪报发布成功")
    void stopsRetentionAfterInsertInvalidates() {
        AtomicBoolean available = new AtomicBoolean(true);
        List<String> calls = new ArrayList<>();
        NotificationInboxMapper mapper = (NotificationInboxMapper) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{NotificationInboxMapper.class},
                (proxy, method, arguments) -> {
                    calls.add(method.getName());
                    if (method.getName().equals("setActivePersistentSurveys")) return 0;
                    if (method.getName().equals("insert")) {
                        available.set(false);
                        return 1;
                    }
                    throw new AssertionError("Unexpected mapper operation: " + method.getName());
                });
        var inbox = new NotificationInboxService(mapper, available::get);

        assertThatThrownBy(() -> inbox.publish(NotificationCategory.SYSTEM, NotificationSeverity.INFO,
                null, "Title", "Body", null)).isInstanceOf(DatabaseUnavailableException.class);

        assertThat(calls).containsExactly("setActivePersistentSurveys", "insert");
    }

    private static NotificationInboxMapper rejectingMapper(AtomicInteger calls) {
        return (NotificationInboxMapper) Proxy.newProxyInstance(
                NotificationDatabaseUnavailableTest.class.getClassLoader(),
                new Class<?>[]{NotificationInboxMapper.class},
                (proxy, method, arguments) -> {
                    calls.incrementAndGet();
                    throw new AssertionError("Unexpected mapper operation: " + method.getName());
                });
    }
}
