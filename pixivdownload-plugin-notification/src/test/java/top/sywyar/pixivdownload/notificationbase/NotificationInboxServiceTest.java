package top.sywyar.pixivdownload.notificationbase;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import top.sywyar.pixivdownload.notification.NotificationSeverity;
import top.sywyar.pixivdownload.plugin.api.notification.SurveyInboxMessage;
import top.sywyar.pixivdownload.plugin.api.web.WebUiSlotContribution;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("管理员站内信服务")
class NotificationInboxServiceTest {

    private static final String CONTENT_URL =
            "https://sywyar.github.io/PixivDownloader-Remote-Content/announcements/2026-08-12.html";

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
    @DisplayName("公告与调查复用本地 HTML 对象且远程来源受固定项目约束")
    void storesReusableHtmlContentFromControlledSources() {
        NotificationInboxService service = new NotificationInboxService(new MemoryMapper());

        NotificationMessage message = service.publishHtml(
                NotificationCategory.SURVEY, NotificationSeverity.INFO, null,
                "Title", "Body", null,
                new NotificationHtmlContent(CONTENT_URL, "<!doctype html><p>Survey</p>"));

        assertThat(message.contentUrl()).isEqualTo(CONTENT_URL);
        assertThat(message.hasHtmlContent()).isTrue();
        assertThat(service.htmlContent(message.id())).isEqualTo(
                new NotificationHtmlContent(CONTENT_URL, "<!doctype html><p>Survey</p>"));
        for (String url : List.of(
                "http://sywyar.github.io/PixivDownloader-Remote-Content/a.html",
                "https://sywyar.github.io.evil.example/PixivDownloader-Remote-Content/a.html",
                "https://evil.example/PixivDownloader-Remote-Content/a.html",
                "https://sywyar.github.io/another-project/a.html",
                "https://sywyar.github.io/PixivDownloader-Remote-Content/../a.html",
                "https://sywyar.github.io/PixivDownloader-Remote-Content/%2e%2e/a.html",
                "https://sywyar.github.io/PixivDownloader-Remote-Content/a.html?redirect=1",
                "https://sywyar.github.io/PixivDownloader-Remote-Content/a.html#section",
                "https://sywyar.github.io:443/PixivDownloader-Remote-Content/a.html",
                "https://user@sywyar.github.io/PixivDownloader-Remote-Content/a.html",
                "https://sywyar.github.io/PixivDownloader-Remote-Content/a.htm")) {
            assertThatThrownBy(() -> service.publishHtml(
                    NotificationCategory.SURVEY, NotificationSeverity.INFO, null,
                    "Title", "Body", null,
                    new NotificationHtmlContent(url, "<!doctype html><p>Survey</p>")))
                    .as(url)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("读取时隐藏数据库中被篡改的远程正文地址")
    void hidesTamperedStoredContentUrl() {
        MemoryMapper mapper = new MemoryMapper();
        NotificationInboxService service = new NotificationInboxService(mapper);
        mapper.insert(new NotificationMessage(
                "tampered", "announcement", "INFO", null, "Title", "Body",
                "https://evil.example/a.html", "<!doctype html><p>Tampered</p>", null, 1, null));

        assertThat(service.find("tampered").contentUrl()).isNull();
        assertThat(service.find("tampered").hasHtmlContent()).isFalse();
        assertThat(service.htmlContent("tampered")).isNull();
        assertThat(service.latest(NotificationCategory.ANNOUNCEMENT, false, 10))
                .singleElement()
                .extracting(NotificationMessage::contentUrl)
                .isNull();
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

    @Test
    @DisplayName("远程公告刷新主元数据和翻译时保留发布时间与已读状态")
    void refreshesRemoteAnnouncementWithoutResettingLocalState() {
        MemoryMapper mapper = new MemoryMapper();
        NotificationInboxService service = new NotificationInboxService(mapper);
        List<RemoteAnnouncementTranslation> initial = List.of(
                new RemoteAnnouncementTranslation(
                        "en-US", "Initial", "Initial body", CONTENT_URL,
                        "0".repeat(64), "<!doctype html><p>Initial</p>"),
                new RemoteAnnouncementTranslation(
                        "zh-CN", "初始", "初始正文", CONTENT_URL,
                        "1".repeat(64), "<!doctype html><p>初始</p>"));
        List<RemoteAnnouncementTranslation> refreshed = List.of(new RemoteAnnouncementTranslation(
                "en-US", "Refreshed", "Refreshed body", CONTENT_URL,
                "2".repeat(64), "<!doctype html><p>Refreshed</p>"));

        assertThat(service.storeRemoteAnnouncement(
                "stable", NotificationSeverity.INFO, initial, 100)).isTrue();
        Long readTime = service.markRead("remote-announcement:stable").readTime();
        assertThat(service.needsRemoteAnnouncementImport(
                "stable", NotificationSeverity.ERROR, 100, refreshed)).isTrue();

        assertThat(service.storeRemoteAnnouncement(
                "stable", NotificationSeverity.ERROR, refreshed, 100)).isTrue();

        assertThat(service.find("remote-announcement:stable", "en-US")).satisfies(message -> {
            assertThat(message.severity()).isEqualTo("ERROR");
            assertThat(message.title()).isEqualTo("Refreshed");
            assertThat(message.body()).isEqualTo("Refreshed body");
            assertThat(message.createdTime()).isEqualTo(100);
            assertThat(message.readTime()).isEqualTo(readTime);
        });
        assertThat(mapper.findRemoteAnnouncementTranslations("remote-announcement:stable"))
                .extracting(RemoteAnnouncementTranslation::locale)
                .containsExactly("en-US");
        assertThat(service.needsRemoteAnnouncementImport(
                "stable", NotificationSeverity.ERROR, 100, refreshed)).isFalse();
    }

    @Test
    @DisplayName("远程公告稳定编号不允许改变发布时间")
    void rejectsRemoteAnnouncementPublishedTimeChanges() {
        MemoryMapper mapper = new MemoryMapper();
        NotificationInboxService service = new NotificationInboxService(mapper);
        List<RemoteAnnouncementTranslation> translations = List.of(new RemoteAnnouncementTranslation(
                "en-US", "Title", "Body", CONTENT_URL,
                "0".repeat(64), "<!doctype html><p>Body</p>"));

        assertThat(service.storeRemoteAnnouncement(
                "stable", NotificationSeverity.INFO, translations, 100)).isTrue();

        assertThatThrownBy(() -> service.needsRemoteAnnouncementImport(
                "stable", NotificationSeverity.WARNING, 101, translations))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.storeRemoteAnnouncement(
                "stable", NotificationSeverity.WARNING, translations, 101))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(service.find("remote-announcement:stable", "en-US")).satisfies(message -> {
            assertThat(message.severity()).isEqualTo("INFO");
            assertThat(message.createdTime()).isEqualTo(100);
        });
    }

    @Test
    @DisplayName("显式删除远程公告后同一稳定编号不会被重新拉取复活")
    void keepsRemoteAnnouncementDismissedAcrossRefetch() {
        MemoryMapper mapper = new MemoryMapper();
        NotificationInboxService service = new NotificationInboxService(mapper);
        List<RemoteAnnouncementTranslation> firstTranslations = List.of(new RemoteAnnouncementTranslation(
                "en-US", "Title", "Body", CONTENT_URL,
                "0".repeat(64), "<!doctype html><p>First</p>"));

        assertThat(service.storeRemoteAnnouncement(
                "stable", NotificationSeverity.INFO, firstTranslations, 1)).isTrue();
        assertThat(service.delete("remote-announcement:stable")).isTrue();

        assertThat(service.find("remote-announcement:stable")).isNull();
        assertThat(service.htmlContent("remote-announcement:stable")).isNull();
        assertThat(service.storeRemoteAnnouncement(
                "stable", NotificationSeverity.WARNING,
                List.of(new RemoteAnnouncementTranslation(
                        "en-US", "Changed", "Changed", CONTENT_URL,
                        "1".repeat(64), "<!doctype html><p>Changed</p>")),
                2)).isFalse();
        assertThat(service.needsRemoteAnnouncementImport(
                "stable", NotificationSeverity.INFO, 1, firstTranslations)).isFalse();
    }

    @Test
    @DisplayName("活动调查槽位只入库一次并按请求语言解析发布者文案")
    void synchronizesPersistentSurveyOnceAndLocalizesAtReadTime() {
        MemoryMapper mapper = new MemoryMapper();
        AtomicReference<List<WebUiSlotContribution>> slots = new AtomicReference<>(List.of(surveySlot()));
        NotificationInboxService service = new NotificationInboxService(
                mapper, () -> 500, () -> 90, slots::get,
                (namespace, locale, key) -> java.util.Optional.of(
                        ("en".equals(locale.getLanguage()) ? "EN " : "ZH ") + key),
                locale -> locale);

        assertThat(service.latest(NotificationCategory.SURVEY, false, 10, "zh-CN"))
                .singleElement()
                .satisfies(message -> {
                    assertThat(message.id()).isEqualTo(
                            "persistent-survey:download-workbench.layout-survey:instance-a");
                    assertThat(message.title()).isEqualTo("ZH layout-feedback.inbox-title");
                    assertThat(message.embeddedContentUrl()).isEqualTo("/pixiv-layout-feedback/embed.html");
                    assertThat(message.deletable()).isFalse();
                });
        assertThat(service.find(
                "persistent-survey:download-workbench.layout-survey:instance-a", "en-US").title())
                .isEqualTo("EN layout-feedback.inbox-title");
        assertThat(mapper.insertCalls).isEqualTo(1);
        assertThat(mapper.activeSyncCalls).isEqualTo(1);

        service.synchronizePersistentSurveys();
        assertThat(mapper.insertCalls).isEqualTo(1);
        assertThat(mapper.activeSyncCalls).isEqualTo(1);

        slots.set(List.of());
        service.synchronizePersistentSurveys();
        assertThat(service.find(
                "persistent-survey:download-workbench.layout-survey:instance-a")).isNull();
    }

    @Test
    @DisplayName("调查插件停用后隐藏记录，重新启用时保留已读状态")
    void preservesPersistentSurveyStateWhilePluginIsInactive() {
        MemoryMapper mapper = new MemoryMapper();
        AtomicReference<List<WebUiSlotContribution>> slots = new AtomicReference<>(List.of(surveySlot()));
        NotificationInboxService service = new NotificationInboxService(
                mapper, () -> 500, () -> 90, slots::get,
                (namespace, locale, key) -> java.util.Optional.empty(), locale -> locale);
        String id = "persistent-survey:download-workbench.layout-survey:instance-a";

        NotificationMessage read = service.markRead(id);
        slots.set(List.of());
        service.synchronizePersistentSurveys();
        assertThat(service.find(id)).isNull();
        assertThat(mapper.messages).filteredOn(message -> message.id().equals(id))
                .singleElement().extracting(NotificationMessage::readTime).isEqualTo(read.readTime());

        slots.set(List.of(surveySlot()));
        service.synchronizePersistentSurveys();
        assertThat(service.find(id).readTime()).isEqualTo(read.readTime());
        assertThat(mapper.insertCalls).isEqualTo(2);
    }

    @Test
    @DisplayName("调查嵌入能力更新时沿用原消息状态并投影当前地址")
    void projectsCurrentEmbedUrlWithoutReplacingPersistentSurvey() {
        MemoryMapper mapper = new MemoryMapper();
        AtomicReference<List<WebUiSlotContribution>> slots = new AtomicReference<>(List.of(
                surveySlot("instance-a", "/pixiv-layout-feedback/embed.html?pixivBridgeGet=/api/old")));
        NotificationInboxService service = new NotificationInboxService(
                mapper, () -> 500, () -> 90, slots::get,
                (namespace, locale, key) -> java.util.Optional.empty(), locale -> locale);
        String id = "persistent-survey:download-workbench.layout-survey:instance-a";
        NotificationMessage read = service.markRead(id);

        String currentUrl = "/pixiv-layout-feedback/embed.html?pixivBridgeGet=/api/current";
        slots.set(List.of(surveySlot("instance-a", currentUrl)));
        service.synchronizePersistentSurveys();

        assertThat(service.find(id)).satisfies(message -> {
            assertThat(message.actionUrl()).isEqualTo(currentUrl);
            assertThat(message.readTime()).isEqualTo(read.readTime());
        });
        assertThat(mapper.messages).singleElement();
    }

    @Test
    @DisplayName("调查不可用写墓碑且普通删除和重复同步都不能使其复活")
    void tombstonesUnavailablePersistentSurvey() {
        MemoryMapper mapper = new MemoryMapper();
        NotificationInboxService service = new NotificationInboxService(
                mapper, () -> 500, () -> 90, () -> List.of(surveySlot()),
                (namespace, locale, key) -> java.util.Optional.empty(), locale -> locale);
        String id = "persistent-survey:download-workbench.layout-survey:instance-a";

        assertThat(service.delete(id)).isFalse();
        assertThat(service.dismissUnavailableSurvey(id)).isTrue();
        assertThat(service.find(id)).isNull();
        service.synchronizePersistentSurveys();
        assertThat(service.find(id)).isNull();

        new NotificationInboxService(
                mapper, () -> 500, () -> 90, () -> List.of(surveySlot()),
                (namespace, locale, key) -> java.util.Optional.empty(), locale -> locale);
        assertThat(mapper.findById(id)).isNull();
    }

    @Test
    @DisplayName("调查实例变化会清理旧墓碑并创建新的未读站内信")
    void replacesTombstonedSurveyWhenInstanceChanges() {
        MemoryMapper mapper = new MemoryMapper();
        AtomicReference<List<WebUiSlotContribution>> slots =
                new AtomicReference<>(List.of(surveySlot("instance-a")));
        NotificationInboxService service = new NotificationInboxService(
                mapper, () -> 500, () -> 90, slots::get,
                (namespace, locale, key) -> java.util.Optional.empty(), locale -> locale);
        String oldId = "persistent-survey:download-workbench.layout-survey:instance-a";
        String newId = "persistent-survey:download-workbench.layout-survey:instance-b";

        assertThat(service.dismissUnavailableSurvey(oldId)).isTrue();
        slots.set(List.of(surveySlot("instance-b")));
        service.synchronizePersistentSurveys();

        assertThat(mapper.findById(oldId)).isNull();
        assertThat(service.find(newId)).isNotNull().satisfies(message ->
                assertThat(message.readTime()).isNull());
    }

    @Test
    @DisplayName("站内信调查贡献拒绝外部嵌入地址")
    void ignoresSurveyContributionWithExternalEmbedUrl() {
        MemoryMapper mapper = new MemoryMapper();
        WebUiSlotContribution unsafe = new WebUiSlotContribution(
                "unsafe.survey", "notification.inbox", null, 10,
                java.util.Map.of(
                        "notification.category", "survey",
                        "notification.instance-key", "instance-a",
                        "notification.embed-url", "https://evil.example/survey",
                        "notification.i18n-namespace", "layout-feedback",
                        "notification.title-key", "layout-feedback.inbox-title",
                        "notification.body-key", "layout-feedback.inbox-body"));

        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger(NotificationInboxService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            new NotificationInboxService(
                    mapper, () -> 500, () -> 90, () -> List.of(unsafe),
                    (namespace, locale, key) -> java.util.Optional.empty(), locale -> locale);

            assertThat(mapper.messages).isEmpty();
            assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                    .anySatisfy(message -> assertThat(message)
                            .contains("survey-inbox-contribution-invalid", "unsafe.survey")
                            .doesNotContain("https://evil.example"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    @DisplayName("写入下载或系统消息后立即限制共享保留池")
    void prunesSharedPoolAfterPublish() {
        MemoryMapper mapper = new MemoryMapper();
        NotificationInboxService service = new NotificationInboxService(mapper, () -> 2, () -> 90);

        service.publish(NotificationCategory.ANNOUNCEMENT, NotificationSeverity.INFO, null,
                "Announcement", "Body", null);
        service.publish(NotificationCategory.DOWNLOAD, NotificationSeverity.INFO, null,
                "Download 1", "Body", null);
        service.publish(NotificationCategory.SYSTEM, NotificationSeverity.INFO, null,
                "System", "Body", null);
        service.publish(NotificationCategory.DOWNLOAD, NotificationSeverity.INFO, null,
                "Download 2", "Body", null);

        List<NotificationMessage> messages = service.latest(null, false, 100);
        assertThat(messages).filteredOn(message ->
                        NotificationCategory.ANNOUNCEMENT.token().equals(message.category()))
                .hasSize(1);
        assertThat(messages).filteredOn(message ->
                        NotificationCategory.DOWNLOAD.token().equals(message.category())
                                || NotificationCategory.SYSTEM.token().equals(message.category()))
                .hasSize(2);
    }

    private static WebUiSlotContribution surveySlot() {
        return surveySlot("instance-a");
    }

    private static WebUiSlotContribution surveySlot(String instanceKey) {
        return surveySlot(instanceKey, "/pixiv-layout-feedback/embed.html");
    }

    private static WebUiSlotContribution surveySlot(String instanceKey, String embedUrl) {
        return new SurveyInboxMessage(
                "download-workbench.layout-survey",
                instanceKey,
                embedUrl,
                "layout-feedback",
                "layout-feedback.inbox-title",
                "layout-feedback.inbox-body",
                10).toUiSlotContribution();
    }

    static final class MemoryMapper implements NotificationInboxMapper {
        private final List<NotificationMessage> messages = new ArrayList<>();
        private final Set<String> dismissedIds = new HashSet<>();
        private final Set<String> activePersistentIds = new HashSet<>();
        private final Map<String, List<RemoteAnnouncementTranslation>> remoteTranslations = new HashMap<>();
        private int insertCalls;
        private int activeSyncCalls;

        @Override
        public int insert(NotificationMessage message) {
            insertCalls++;
            if (dismissedIds.contains(message.id())
                    || messages.stream().anyMatch(existing -> existing.id().equals(message.id()))) {
                return 0;
            }
            messages.add(message);
            return 1;
        }

        @Override
        public List<NotificationMessage> findLatest(String category, boolean unreadOnly, int limit) {
            return messages.stream()
                    .filter(this::visible)
                    .filter(message -> category == null || category.equals(message.category()))
                    .filter(message -> !unreadOnly || message.readTime() == null)
                    .sorted(Comparator.comparingLong(NotificationMessage::createdTime).reversed())
                    .limit(limit)
                    .toList();
        }

        @Override
        public NotificationMessage findById(String id) {
            return messages.stream().filter(this::visible)
                    .filter(message -> message.id().equals(id)).findFirst().orElse(null);
        }

        @Override
        public NotificationHtmlContent findHtmlContent(String id) {
            NotificationMessage message = findById(id);
            return message == null || message.contentHtml() == null
                    ? null
                    : new NotificationHtmlContent(message.contentUrl(), message.contentHtml());
        }

        @Override
        public boolean blocksRemoteAnnouncementImport(String id) {
            NotificationMessage message = messages.stream()
                    .filter(candidate -> candidate.id().equals(id))
                    .findFirst()
                    .orElse(null);
            return dismissedIds.contains(id)
                    || message != null && !NotificationCategory.ANNOUNCEMENT.token().equals(message.category());
        }

        @Override
        public int updateRemoteAnnouncement(NotificationMessage replacement) {
            String id = replacement.id();
            NotificationMessage current = findById(id);
            if (current == null || current.createdTime() != replacement.createdTime()
                    || !NotificationCategory.ANNOUNCEMENT.token().equals(current.category())) {
                return 0;
            }
            messages.remove(current);
            messages.add(new NotificationMessage(
                    current.id(), current.category(), replacement.severity(), current.scenarioId(),
                    replacement.title(), replacement.body(), replacement.contentUrl(), replacement.contentHtml(),
                    current.actionUrl(),
                    current.createdTime(), current.readTime()));
            return 1;
        }

        @Override
        public List<RemoteAnnouncementTranslation> findRemoteAnnouncementTranslations(String announcementId) {
            if (findById(announcementId) == null) {
                return List.of();
            }
            return remoteTranslations.getOrDefault(announcementId, List.of()).stream()
                    .map(translation -> new RemoteAnnouncementTranslation(
                            translation.locale(), translation.title(), translation.summary(),
                            translation.contentUrl(), translation.contentSha256(), ""))
                    .sorted(Comparator.comparing(RemoteAnnouncementTranslation::locale))
                    .toList();
        }

        @Override
        public NotificationHtmlContent findRemoteAnnouncementHtml(String announcementId, String locale) {
            if (findById(announcementId) == null) {
                return null;
            }
            return remoteTranslations.getOrDefault(announcementId, List.of()).stream()
                    .filter(translation -> translation.locale().equals(locale))
                    .findFirst()
                    .map(translation -> new NotificationHtmlContent(
                            translation.contentUrl(), translation.contentHtml()))
                    .orElse(null);
        }

        @Override
        public int upsertRemoteAnnouncementTranslation(
                String announcementId,
                RemoteAnnouncementTranslation translation) {
            List<RemoteAnnouncementTranslation> current = new ArrayList<>(
                    remoteTranslations.getOrDefault(announcementId, List.of()));
            current.removeIf(existing -> existing.locale().equals(translation.locale()));
            current.add(translation);
            remoteTranslations.put(announcementId, List.copyOf(current));
            return 1;
        }

        @Override
        public int deleteStaleRemoteAnnouncementTranslations(String announcementId, List<String> locales) {
            List<RemoteAnnouncementTranslation> current = new ArrayList<>(
                    remoteTranslations.getOrDefault(announcementId, List.of()));
            int before = current.size();
            current.removeIf(translation -> !locales.contains(translation.locale()));
            remoteTranslations.put(announcementId, List.copyOf(current));
            return before - current.size();
        }

        @Override
        public int deleteRemoteAnnouncementTranslations(String announcementId) {
            List<RemoteAnnouncementTranslation> removed = remoteTranslations.remove(announcementId);
            return removed == null ? 0 : removed.size();
        }

        @Override
        public int acceptRemoteAnnouncementIndex(long sequence, String manifestSha256,
                                                 long generatedTime, long expiresTime) {
            return 1;
        }

        @Override
        public RemoteAnnouncementValidators findRemoteAnnouncementValidators() {
            return null;
        }

        @Override
        public int saveRemoteAnnouncementValidators(
                String manifestSha256, String etag, String lastModified) {
            return 0;
        }

        @Override
        public long countUnread(String category) {
            return messages.stream()
                    .filter(this::visible)
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
                            message.title(), message.body(), message.contentUrl(), message.contentHtml(),
                            message.actionUrl(),
                            message.createdTime(), Math.max(message.createdTime(), readTime)));
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

        @Override
        public int dismissAnnouncement(String id, long deletedTime) {
            NotificationMessage message = findById(id);
            if (message == null || !NotificationCategory.ANNOUNCEMENT.token().equals(message.category())) {
                return 0;
            }
            messages.remove(message);
            dismissedIds.add(id);
            return 1;
        }

        @Override
        public int dismissPersistentSurvey(String id, long deletedTime) {
            NotificationMessage message = findById(id);
            if (message == null || !message.persistentSurvey()
                    || !NotificationCategory.SURVEY.token().equals(message.category())) {
                return 0;
            }
            messages.remove(message);
            dismissedIds.add(id);
            return 1;
        }

        @Override
        public int setActivePersistentSurveys(List<String> activeIds) {
            activeSyncCalls++;
            Set<String> active = Set.copyOf(activeIds);
            int changed = (int) messages.stream().filter(NotificationMessage::persistentSurvey)
                    .filter(message -> active.contains(message.id()) != activePersistentIds.contains(message.id()))
                    .count();
            activePersistentIds.clear();
            activePersistentIds.addAll(active);
            return changed;
        }

        private boolean visible(NotificationMessage message) {
            return !message.persistentSurvey() || activePersistentIds.contains(message.id());
        }

        @Override
        public int deleteNonAnnouncement(String id) {
            NotificationMessage message = findById(id);
            if (message == null || NotificationCategory.ANNOUNCEMENT.token().equals(message.category())) {
                return 0;
            }
            messages.remove(message);
            return 1;
        }

        @Override
        public int pruneRetentionPool(long cutoffTime, int maxMessages) {
            Set<String> retainedIds = messages.stream()
                    .filter(message -> message.category().equals(NotificationCategory.DOWNLOAD.token())
                            || message.category().equals(NotificationCategory.SYSTEM.token()))
                    .filter(message -> message.createdTime() >= cutoffTime)
                    .sorted(Comparator.comparingLong(NotificationMessage::createdTime).reversed()
                            .thenComparing(NotificationMessage::id, Comparator.reverseOrder()))
                    .limit(maxMessages)
                    .map(NotificationMessage::id)
                    .collect(java.util.stream.Collectors.toSet());
            int before = messages.size();
            messages.removeIf(message -> (message.category().equals(NotificationCategory.DOWNLOAD.token())
                    || message.category().equals(NotificationCategory.SYSTEM.token()))
                    && !retainedIds.contains(message.id()));
            return before - messages.size();
        }
    }
}
