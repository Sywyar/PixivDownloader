package top.sywyar.pixivdownload.notificationbase;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import top.sywyar.pixivdownload.core.database.DatabaseAvailability;
import top.sywyar.pixivdownload.i18n.LocaleBundlePolicy;
import top.sywyar.pixivdownload.notification.NotificationSeverity;
import top.sywyar.pixivdownload.i18n.NamespaceMessageResolver;
import top.sywyar.pixivdownload.plugin.api.notification.NotificationTemplateContribution;
import top.sywyar.pixivdownload.plugin.api.notification.SurveyInboxMessage;
import top.sywyar.pixivdownload.plugin.api.web.WebUiSlotCatalog;
import top.sywyar.pixivdownload.plugin.api.web.WebUiSlotContribution;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.regex.Pattern;

public class NotificationInboxService {

    private static final Logger LOG = LoggerFactory.getLogger(NotificationInboxService.class);
    private static final int MAX_ACTION_URL_BYTES = 8 * 1_024;
    private static final int MAX_CONTENT_URL_BYTES = 2 * 1_024;
    private static final String CONTENT_HOST = "sywyar.github.io";
    private static final String REMOTE_ANNOUNCEMENT_ID_PREFIX = "remote-announcement:";
    private static final Pattern REMOTE_ANNOUNCEMENT_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,79}");
    private static final Pattern REMOTE_LOCALE_TAG = Pattern.compile("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})+");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern CONTENT_PATH = Pattern.compile(
            "/PixivDownloader-Remote-Content/(?:[A-Za-z0-9][A-Za-z0-9._-]*/)*"
                    + "[A-Za-z0-9][A-Za-z0-9._-]*\\.html");

    private final NotificationInboxMapper mapper;
    private final DatabaseAvailability database;
    private final IntSupplier maxMessages;
    private final IntSupplier retentionDays;
    private final WebUiSlotCatalog uiSlots;
    private final NamespaceMessageResolver messages;
    private final Function<Locale, Locale> normalizeLocale;
    private final Function<Locale, List<Locale>> localeFallbacks;
    private volatile List<PersistentSurvey> persistentSurveys;

    public NotificationInboxService(NotificationInboxMapper mapper, DatabaseAvailability database) {
        this(mapper, database, () -> NotificationPlugin.DEFAULT_INBOX_MAX_MESSAGES,
                () -> NotificationPlugin.DEFAULT_INBOX_RETENTION_DAYS,
                List::of, (namespace, locale, key) -> Optional.empty(),
                locale -> locale == null ? Locale.getDefault() : locale);
    }

    NotificationInboxService(NotificationInboxMapper mapper,
                             DatabaseAvailability database,
                             IntSupplier maxMessages,
                             IntSupplier retentionDays) {
        this(mapper, database, maxMessages, retentionDays, List::of,
                (namespace, locale, key) -> Optional.empty(),
                locale -> locale == null ? Locale.getDefault() : locale);
    }

    NotificationInboxService(NotificationInboxMapper mapper,
                             DatabaseAvailability database,
                             IntSupplier maxMessages,
                             IntSupplier retentionDays,
                             WebUiSlotCatalog uiSlots,
                             NamespaceMessageResolver messages,
                             Function<Locale, Locale> normalizeLocale) {
        this(mapper, database, maxMessages, retentionDays, uiSlots, messages, normalizeLocale,
                requested -> List.of(normalizeLocale.apply(requested)));
    }

    NotificationInboxService(NotificationInboxMapper mapper,
                             DatabaseAvailability database,
                             IntSupplier maxMessages,
                             IntSupplier retentionDays,
                             WebUiSlotCatalog uiSlots,
                             NamespaceMessageResolver messages,
                             LocaleBundlePolicy localePolicy) {
        this(mapper, database, maxMessages, retentionDays, uiSlots, messages,
                localePolicy::normalize,
                requested -> localeFallbacks(localePolicy, requested));
    }

    private NotificationInboxService(NotificationInboxMapper mapper,
                                     DatabaseAvailability database,
                                     IntSupplier maxMessages,
                                     IntSupplier retentionDays,
                                     WebUiSlotCatalog uiSlots,
                                     NamespaceMessageResolver messages,
                                     Function<Locale, Locale> normalizeLocale,
                                     Function<Locale, List<Locale>> localeFallbacks) {
        this.mapper = Objects.requireNonNull(mapper, "notification inbox mapper");
        this.database = Objects.requireNonNull(database, "database availability");
        this.maxMessages = Objects.requireNonNull(maxMessages, "notification inbox max messages");
        this.retentionDays = Objects.requireNonNull(retentionDays, "notification inbox retention days");
        this.uiSlots = Objects.requireNonNull(uiSlots, "web UI slot catalog");
        this.messages = Objects.requireNonNull(messages, "namespace message resolver");
        this.normalizeLocale = Objects.requireNonNull(normalizeLocale, "locale normalizer");
        this.localeFallbacks = Objects.requireNonNull(localeFallbacks, "locale fallback policy");
        synchronizePersistentSurveys();
    }

    /** 展示查询的空值须结合此状态判断，不能视为有效空收件箱。 */
    public boolean isDatabaseAvailable() {
        return database.available();
    }

    void requireDatabaseAvailable() {
        database.requireAvailable();
    }

    public NotificationMessage publish(NotificationCategory category,
                                       NotificationSeverity severity,
                                       String scenarioId,
                                       String title,
                                       String body,
                                       String actionUrl) {
        return publish(category, severity, scenarioId, title, body, actionUrl, null);
    }

    public NotificationMessage publishHtml(NotificationCategory category,
                                           NotificationSeverity severity,
                                           String scenarioId,
                                           String title,
                                           String body,
                                           String actionUrl,
                                           NotificationHtmlContent htmlContent) {
        database.requireAvailable();
        return publish(category, severity, scenarioId, title, body, actionUrl,
                validatedHtmlContent(htmlContent));
    }

    private NotificationMessage publish(NotificationCategory category,
                                        NotificationSeverity severity,
                                        String scenarioId,
                                        String title,
                                        String body,
                                        String actionUrl,
                                        NotificationHtmlContent htmlContent) {
        database.requireAvailable();
        NotificationCategory selectedCategory = Objects.requireNonNull(category, "notification category");
        long now = System.currentTimeMillis();
        NotificationMessage message = new NotificationMessage(
                UUID.randomUUID().toString(),
                selectedCategory.token(),
                Objects.requireNonNull(severity, "notification severity").name(),
                optional(scenarioId),
                requiredText(title, NotificationTemplateContribution.MAX_TITLE_BYTES, "notification title"),
                requiredText(body, NotificationTemplateContribution.MAX_BODY_BYTES, "notification body"),
                htmlContent == null ? null : htmlContent.sourceUrl(),
                htmlContent == null ? null : htmlContent.html(),
                safeActionUrl(actionUrl),
                now,
                null);
        database.requireAvailable();
        if (mapper.insert(message) == 1 && isRetentionPoolCategory(selectedCategory)) {
            pruneRetentionPool(now);
        }
        database.requireAvailable();
        return message;
    }

    @Transactional
    public boolean storeRemoteAnnouncement(String remoteId,
                                           NotificationSeverity severity,
                                           List<RemoteAnnouncementTranslation> translations,
                                           long publishedAt) {
        database.requireAvailable();
        String normalizedId = remoteAnnouncementId(remoteId);
        if (publishedAt < 0) {
            throw new IllegalArgumentException("remote announcement published time is invalid");
        }
        String messageId = REMOTE_ANNOUNCEMENT_ID_PREFIX + normalizedId;
        List<RemoteAnnouncementTranslation> validatedTranslations = validatedRemoteTranslations(translations, true);
        boolean blocked = mapper.blocksRemoteAnnouncementImport(messageId);
        database.requireAvailable();
        if (blocked) {
            return false;
        }
        RemoteAnnouncementTranslation primary = validatedTranslations.get(0);
        NotificationMessage message = new NotificationMessage(
                messageId,
                NotificationCategory.ANNOUNCEMENT.token(),
                Objects.requireNonNull(severity, "notification severity").name(),
                null,
                primary.title(),
                primary.summary(),
                primary.contentUrl(),
                primary.contentHtml(),
                null,
                publishedAt,
                null);
        database.requireAvailable();
        int changed = mapper.insert(message);
        if (changed == 0) {
            NotificationMessage existing = mapper.findById(messageId);
            database.requireAvailable();
            if (existing == null) {
                return false;
            }
            requireSamePublishedAt(existing, publishedAt);
            database.requireAvailable();
            if (mapper.updateRemoteAnnouncement(message) != 1) {
                return false;
            }
            changed = 1;
        }
        for (RemoteAnnouncementTranslation translation : validatedTranslations) {
            database.requireAvailable();
            changed += mapper.upsertRemoteAnnouncementTranslation(messageId, translation);
        }
        database.requireAvailable();
        changed += mapper.deleteStaleRemoteAnnouncementTranslations(
                messageId, validatedTranslations.stream().map(RemoteAnnouncementTranslation::locale).toList());
        database.requireAvailable();
        return changed > 0;
    }

    boolean needsRemoteAnnouncementImport(String remoteId,
                                          NotificationSeverity severity,
                                          long publishedAt,
                                          List<RemoteAnnouncementTranslation> translations) {
        database.requireAvailable();
        String messageId = REMOTE_ANNOUNCEMENT_ID_PREFIX + remoteAnnouncementId(remoteId);
        boolean blocked = mapper.blocksRemoteAnnouncementImport(messageId);
        database.requireAvailable();
        if (blocked) {
            return false;
        }
        if (publishedAt < 0) {
            throw new IllegalArgumentException("remote announcement published time is invalid");
        }
        String severityName = Objects.requireNonNull(severity, "notification severity").name();
        List<RemoteAnnouncementTranslation> expected = validatedRemoteTranslations(translations, false);
        NotificationMessage existing = mapper.findById(messageId);
        database.requireAvailable();
        if (existing == null) {
            return true;
        }
        requireSamePublishedAt(existing, publishedAt);
        RemoteAnnouncementTranslation primary = expected.get(0);
        List<RemoteAnnouncementTranslation> stored = safeRemoteTranslations(
                mapper.findRemoteAnnouncementTranslations(messageId));
        database.requireAvailable();
        return !severityName.equals(existing.severity())
                || !primary.title().equals(existing.title())
                || !primary.summary().equals(existing.body())
                || !primary.contentUrl().equals(existing.contentUrl())
                || !sameTranslationMetadata(expected, stored);
    }

    @Transactional
    boolean acceptRemoteAnnouncementIndex(long sequence,
                                          String manifestSha256,
                                          long generatedTime,
                                          long expiresTime) {
        database.requireAvailable();
        if (sequence <= 0
                || manifestSha256 == null
                || !SHA256.matcher(manifestSha256).matches()
                || generatedTime < 0
                || expiresTime <= generatedTime) {
            throw new IllegalArgumentException("remote announcement index state is invalid");
        }
        int changed = mapper.acceptRemoteAnnouncementIndex(
                sequence, manifestSha256, generatedTime, expiresTime);
        database.requireAvailable();
        return changed == 1;
    }

    RemoteAnnouncementValidators remoteAnnouncementValidators(long now) {
        database.requireAvailable();
        RemoteAnnouncementValidators validators = mapper.findRemoteAnnouncementValidators();
        database.requireAvailable();
        return validators == null || validators.expiresTime() <= now ? null : validators;
    }

    @Transactional
    boolean saveRemoteAnnouncementValidators(String manifestSha256,
                                             String etag,
                                             String lastModified) {
        database.requireAvailable();
        if (manifestSha256 == null || !SHA256.matcher(manifestSha256).matches()) {
            throw new IllegalArgumentException("remote announcement manifest SHA-256 is invalid");
        }
        int changed = mapper.saveRemoteAnnouncementValidators(
                manifestSha256,
                optionalHeaderValue(etag),
                optionalHeaderValue(lastModified));
        database.requireAvailable();
        return changed == 1;
    }

    public List<NotificationMessage> latest(NotificationCategory category, boolean unreadOnly, int limit) {
        return latest(category, unreadOnly, limit, null);
    }

    public List<NotificationMessage> latest(NotificationCategory category, boolean unreadOnly, int limit,
                                            String language) {
        if (!database.available()) return List.of();
        List<NotificationMessage> result = mapper.findLatest(
                        categoryToken(category), unreadOnly, Math.max(1, Math.min(100, limit))).stream()
                .map(NotificationInboxService::safeStoredMessage)
                .map(message -> localize(message, locale(language)))
                .toList();
        return database.available() ? result : List.of();
    }

    public long unreadCount() {
        return unreadCount(null);
    }

    public long unreadCount(NotificationCategory category) {
        if (!database.available()) return 0;
        long count = mapper.countUnread(categoryToken(category));
        return database.available() ? count : 0;
    }

    public NotificationMessage find(String id) {
        return find(id, null);
    }

    public NotificationMessage find(String id, String language) {
        if (!database.available()) return null;
        NotificationMessage message = localize(
                safeStoredMessage(mapper.findById(Objects.requireNonNull(id, "notification id"))), locale(language));
        return database.available() ? message : null;
    }

    public NotificationHtmlContent htmlContent(String id) {
        return htmlContent(id, null);
    }

    public NotificationHtmlContent htmlContent(String id, String language) {
        if (!database.available()) return null;
        String requiredId = Objects.requireNonNull(id, "notification id");
        NotificationMessage message = safeStoredMessage(mapper.findById(requiredId));
        if (!database.available()) return null;
        NotificationHtmlContent content = null;
        if (remoteAnnouncement(message)) {
            RemoteAnnouncementTranslation translation = selectRemoteTranslation(
                    safeRemoteTranslations(mapper.findRemoteAnnouncementTranslations(requiredId)),
                    locale(language));
            if (translation != null) {
                if (!database.available()) return null;
                content = mapper.findRemoteAnnouncementHtml(requiredId, translation.locale());
            }
        }
        if (content == null) {
            if (!database.available()) return null;
            content = mapper.findHtmlContent(requiredId);
        }
        if (content == null || !database.available()) {
            return null;
        }
        try {
            return validatedHtmlContent(content);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public NotificationMessage markRead(String id) {
        return markRead(id, null);
    }

    public NotificationMessage markRead(String id, String language) {
        database.requireAvailable();
        mapper.markRead(Objects.requireNonNull(id, "notification id"), System.currentTimeMillis());
        database.requireAvailable();
        NotificationMessage message = localize(safeStoredMessage(mapper.findById(id)), locale(language));
        database.requireAvailable();
        return message;
    }

    public int markAllRead(NotificationCategory category) {
        database.requireAvailable();
        int changed = mapper.markAllRead(categoryToken(category), System.currentTimeMillis());
        database.requireAvailable();
        return changed;
    }

    @Transactional
    public boolean delete(String id) {
        database.requireAvailable();
        String requiredId = Objects.requireNonNull(id, "notification id");
        NotificationMessage message = mapper.findById(requiredId);
        database.requireAvailable();
        if (message == null) {
            return false;
        }
        if (message.persistentSurvey()) {
            return false;
        }
        database.requireAvailable();
        if (NotificationCategory.ANNOUNCEMENT.token().equals(message.category())) {
            if (mapper.dismissAnnouncement(requiredId, System.currentTimeMillis()) != 1) {
                return false;
            }
            database.requireAvailable();
            mapper.deleteRemoteAnnouncementTranslations(requiredId);
            database.requireAvailable();
            return true;
        }
        int changed = mapper.deleteNonAnnouncement(requiredId);
        database.requireAvailable();
        return changed == 1;
    }

    public boolean dismissUnavailableSurvey(String id) {
        database.requireAvailable();
        String requiredId = Objects.requireNonNull(id, "notification id");
        if (!requiredId.startsWith(NotificationMessage.PERSISTENT_SURVEY_ID_PREFIX)) {
            return false;
        }
        int changed = mapper.dismissPersistentSurvey(requiredId, System.currentTimeMillis());
        database.requireAvailable();
        return changed == 1;
    }

    public synchronized void synchronizePersistentSurveys() {
        if (!database.available()) return;
        List<PersistentSurvey> active = uiSlots.uiSlots().stream()
                .map(NotificationInboxService::surveyInboxMessage)
                .flatMap(Optional::stream)
                .map(NotificationInboxService::persistentSurvey)
                .sorted(java.util.Comparator.comparing(PersistentSurvey::id))
                .toList();
        if (active.equals(persistentSurveys)) {
            return;
        }
        database.requireAvailable();
        mapper.setActivePersistentSurveys(active.stream().map(PersistentSurvey::id).toList());
        long now = System.currentTimeMillis();
        for (PersistentSurvey survey : active) {
            database.requireAvailable();
            mapper.insert(new NotificationMessage(
                    survey.id(), NotificationCategory.SURVEY.token(), NotificationSeverity.INFO.name(),
                    survey.slotId(), survey.titleKey(), survey.bodyKey(), null, null,
                    survey.embedUrl(), now, null));
        }
        database.requireAvailable();
        persistentSurveys = active;
    }

    private static Optional<SurveyInboxMessage> surveyInboxMessage(WebUiSlotContribution slot) {
        try {
            return SurveyInboxMessage.fromUiSlotContribution(slot);
        } catch (IllegalArgumentException exception) {
            LOG.warn("[survey-inbox-contribution-invalid] Ignoring invalid survey inbox slot [{}]: {}",
                    slot == null ? "<null>" : Objects.toString(slot.slotId(), "<null>"),
                    exception.getMessage());
            return Optional.empty();
        }
    }

    int pruneRetentionPool() {
        return pruneRetentionPool(System.currentTimeMillis());
    }

    int pruneRetentionPool(long now) {
        database.requireAvailable();
        int days = positiveSetting(retentionDays, NotificationPlugin.DEFAULT_INBOX_RETENTION_DAYS);
        int maximum = positiveSetting(maxMessages, NotificationPlugin.DEFAULT_INBOX_MAX_MESSAGES);
        long maxAgeMillis = TimeUnit.DAYS.toMillis(days);
        long cutoffTime = now > maxAgeMillis ? now - maxAgeMillis : 0;
        database.requireAvailable();
        return mapper.pruneRetentionPool(cutoffTime, maximum);
    }

    private static String categoryToken(NotificationCategory category) {
        return category == null ? null : category.token();
    }

    private NotificationMessage localize(NotificationMessage message, Locale locale) {
        if (message == null || !database.available()) {
            return null;
        }
        if (remoteAnnouncement(message)) {
            RemoteAnnouncementTranslation translation = selectRemoteTranslation(
                    safeRemoteTranslations(mapper.findRemoteAnnouncementTranslations(message.id())), locale);
            if (translation == null) {
                return message;
            }
            return new NotificationMessage(
                    message.id(), message.category(), message.severity(), message.scenarioId(),
                    translation.title(), translation.summary(), translation.contentUrl(),
                    translation.contentHtml() == null ? null : "", message.actionUrl(),
                    message.createdTime(), message.readTime());
        }
        if (!message.persistentSurvey() || persistentSurveys == null) {
            return message;
        }
        PersistentSurvey survey = persistentSurveys.stream()
                .filter(candidate -> candidate.id().equals(message.id()))
                .findFirst()
                .orElse(null);
        if (survey == null) {
            return message;
        }
        String title = messages.resolve(survey.namespace(), locale, survey.titleKey()).orElse(message.title());
        String body = messages.resolve(survey.namespace(), locale, survey.bodyKey()).orElse(message.body());
        if (title.equals(message.title()) && body.equals(message.body())
                && survey.embedUrl().equals(message.actionUrl())) {
            return message;
        }
        return new NotificationMessage(
                message.id(), message.category(), message.severity(), message.scenarioId(), title, body,
                message.contentUrl(), message.contentHtml(), survey.embedUrl(),
                message.createdTime(), message.readTime());
    }

    private Locale locale(String language) {
        Locale requested = language == null || language.isBlank()
                ? Locale.getDefault()
                : Locale.forLanguageTag(language.trim().replace('_', '-'));
        return normalizeLocale.apply(requested);
    }

    private RemoteAnnouncementTranslation selectRemoteTranslation(
            List<RemoteAnnouncementTranslation> translations,
            Locale requested) {
        if (translations.isEmpty()) {
            return null;
        }
        LinkedHashSet<Locale> candidates = new LinkedHashSet<>();
        candidates.add(requested);
        try {
            candidates.addAll(localeFallbacks.apply(requested));
        } catch (RuntimeException ignored) {
            // 损坏的本地语言配置不应阻止回退到任一可用公告翻译。
        }
        for (Locale candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            String exact = candidate.toLanguageTag();
            for (RemoteAnnouncementTranslation translation : translations) {
                if (translation.locale().equals(exact)) {
                    return translation;
                }
            }
            String language = candidate.getLanguage();
            for (RemoteAnnouncementTranslation translation : translations) {
                if (!language.isBlank()
                        && Locale.forLanguageTag(translation.locale()).getLanguage().equals(language)) {
                    return translation;
                }
            }
        }
        return translations.get(0);
    }

    private static List<Locale> localeFallbacks(LocaleBundlePolicy policy, Locale requested) {
        Locale normalized = policy.normalize(requested);
        LinkedHashSet<Locale> candidates = new LinkedHashSet<>();
        candidates.add(normalized);
        for (String suffix : policy.resourceSuffixChain(normalized)) {
            for (Locale supported : policy.supportedLocales()) {
                List<String> supportedChain = policy.resourceSuffixChain(supported);
                if (!supportedChain.isEmpty() && suffix.equals(supportedChain.get(0))) {
                    candidates.add(supported);
                }
            }
        }
        return List.copyOf(candidates);
    }

    private static PersistentSurvey persistentSurvey(SurveyInboxMessage message) {
        return new PersistentSurvey(
                NotificationMessage.PERSISTENT_SURVEY_ID_PREFIX
                        + message.messageKey() + ":" + message.instanceKey(),
                message.messageKey(),
                message.contentUrl(), message.i18nNamespace(), message.titleKey(), message.bodyKey());
    }

    private static String safeEmbeddedContentUrl(String value) {
        String normalized = safeActionUrl(value);
        if (normalized == null || !normalized.startsWith("/") || normalized.startsWith("//")) {
            throw new IllegalArgumentException("embedded notification URL must be a same-origin absolute path");
        }
        return normalized;
    }

    private static boolean isRetentionPoolCategory(NotificationCategory category) {
        return category == NotificationCategory.DOWNLOAD || category == NotificationCategory.SYSTEM;
    }

    private static String remoteAnnouncementId(String value) {
        String normalized = Objects.requireNonNull(value, "remote announcement id");
        if (!REMOTE_ANNOUNCEMENT_ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException("remote announcement id is invalid");
        }
        return normalized;
    }

    private static boolean remoteAnnouncement(NotificationMessage message) {
        return message != null
                && NotificationCategory.ANNOUNCEMENT.token().equals(message.category())
                && message.id() != null
                && message.id().startsWith(REMOTE_ANNOUNCEMENT_ID_PREFIX);
    }

    private static List<RemoteAnnouncementTranslation> validatedRemoteTranslations(
            List<RemoteAnnouncementTranslation> translations,
            boolean requireHtml) {
        if (translations == null || translations.isEmpty() || translations.size() > 16) {
            throw new IllegalArgumentException("remote announcement translations are invalid");
        }
        LinkedHashMap<String, RemoteAnnouncementTranslation> validated = new LinkedHashMap<>();
        for (RemoteAnnouncementTranslation translation : translations) {
            RemoteAnnouncementTranslation current = validatedRemoteTranslation(translation, requireHtml);
            if (validated.putIfAbsent(current.locale(), current) != null) {
                throw new IllegalArgumentException("remote announcement translation locale is duplicated");
            }
        }
        return List.copyOf(validated.values());
    }

    private static RemoteAnnouncementTranslation validatedRemoteTranslation(
            RemoteAnnouncementTranslation translation,
            boolean requireHtml) {
        Objects.requireNonNull(translation, "remote announcement translation");
        String locale = Objects.requireNonNull(translation.locale(), "remote announcement locale");
        if (!REMOTE_LOCALE_TAG.matcher(locale).matches()
                || !Locale.forLanguageTag(locale).toLanguageTag().equals(locale)) {
            throw new IllegalArgumentException("remote announcement locale is invalid");
        }
        String title = requiredText(translation.title(), 160 * 4, "remote announcement title");
        String summary = requiredText(translation.summary(), 500 * 4, "remote announcement summary");
        String contentUrl = safeContentUrl(translation.contentUrl());
        String contentSha256 = Objects.requireNonNull(
                translation.contentSha256(), "remote announcement content SHA-256");
        if (!SHA256.matcher(contentSha256).matches()) {
            throw new IllegalArgumentException("remote announcement content SHA-256 is invalid");
        }
        String contentHtml = translation.contentHtml();
        if (requireHtml) {
            contentHtml = validatedHtmlContent(new NotificationHtmlContent(contentUrl, contentHtml)).html();
        } else if (contentHtml != null) {
            contentHtml = "";
        }
        return new RemoteAnnouncementTranslation(
                locale, title, summary, contentUrl, contentSha256, contentHtml);
    }

    private static List<RemoteAnnouncementTranslation> safeRemoteTranslations(
            List<RemoteAnnouncementTranslation> translations) {
        if (translations == null || translations.isEmpty()) {
            return List.of();
        }
        List<RemoteAnnouncementTranslation> safe = new ArrayList<>();
        for (RemoteAnnouncementTranslation translation : translations) {
            try {
                safe.add(validatedRemoteTranslation(translation, false));
            } catch (IllegalArgumentException | NullPointerException ignored) {
                // 损坏的单个本地翻译不应阻止其它公告内容回退。
            }
        }
        safe.sort(Comparator.comparing(RemoteAnnouncementTranslation::locale));
        return List.copyOf(safe);
    }

    private static boolean sameTranslationMetadata(List<RemoteAnnouncementTranslation> expected,
                                                   List<RemoteAnnouncementTranslation> stored) {
        if (expected.size() != stored.size()) {
            return false;
        }
        Map<String, RemoteAnnouncementTranslation> storedByLocale = stored.stream()
                .collect(java.util.stream.Collectors.toMap(
                        RemoteAnnouncementTranslation::locale,
                        translation -> translation,
                        (left, right) -> left,
                        LinkedHashMap::new));
        for (RemoteAnnouncementTranslation translation : expected) {
            RemoteAnnouncementTranslation current = storedByLocale.get(translation.locale());
            if (current == null
                    || !translation.title().equals(current.title())
                    || !translation.summary().equals(current.summary())
                    || !translation.contentUrl().equals(current.contentUrl())
                    || !translation.contentSha256().equals(current.contentSha256())) {
                return false;
            }
        }
        return true;
    }

    private static void requireSamePublishedAt(NotificationMessage existing, long publishedAt) {
        if (existing.createdTime() != publishedAt) {
            throw new IllegalArgumentException("remote announcement published time is immutable");
        }
    }

    private static int positiveSetting(IntSupplier supplier, int defaultValue) {
        try {
            int value = supplier.getAsInt();
            return value > 0 ? value : defaultValue;
        } catch (RuntimeException ignored) {
            return defaultValue;
        }
    }

    private static String requiredText(String value, int maxBytes, String field) {
        String text = Objects.requireNonNull(value, field);
        if (text.isBlank() || text.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(field + " must not be blank or contain NUL");
        }
        if (text.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new IllegalArgumentException(field + " exceeds size limit");
        }
        return text;
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String optionalHeaderValue(String value) {
        if (value == null) {
            return null;
        }
        if (value.isBlank() || value.length() > 512
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("remote announcement cache validator is invalid");
        }
        return value;
    }

    private static String safeActionUrl(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.getBytes(StandardCharsets.UTF_8).length > MAX_ACTION_URL_BYTES
                || normalized.indexOf('\0') >= 0 || normalized.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("notification action URL is invalid");
        }
        try {
            URI uri = new URI(normalized);
            if (normalized.startsWith("/") && !normalized.startsWith("//")
                    && !uri.isAbsolute() && uri.getRawAuthority() == null
                    && uri.normalize().toString().equals(normalized)) {
                return normalized;
            }
            String scheme = uri.getScheme();
            if (uri.isAbsolute() && uri.getHost() != null && uri.getUserInfo() == null
                    && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                return normalized;
            }
        } catch (URISyntaxException ignored) {
            // 统一在下方拒绝。
        }
        throw new IllegalArgumentException("notification action URL must be a safe path or HTTP(S) URL");
    }

    static String safeContentUrl(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.getBytes(StandardCharsets.UTF_8).length > MAX_CONTENT_URL_BYTES
                || hasForbiddenUrlCharacter(normalized)) {
            throw new IllegalArgumentException("notification content URL is invalid");
        }
        try {
            URI uri = new URI(normalized);
            String rawPath = uri.getRawPath();
            if ("https".equals(uri.getScheme())
                    && CONTENT_HOST.equals(uri.getHost())
                    && uri.getUserInfo() == null
                    && uri.getPort() == -1
                    && uri.getRawQuery() == null
                    && uri.getRawFragment() == null
                    && rawPath != null
                    && CONTENT_PATH.matcher(rawPath).matches()
                    && uri.normalize().equals(uri)
                    && uri.toASCIIString().equals(normalized)) {
                return normalized;
            }
        } catch (URISyntaxException ignored) {
            // 统一在下方拒绝。
        }
        throw new IllegalArgumentException("notification content URL is outside the trusted content source");
    }

    private static NotificationHtmlContent validatedHtmlContent(NotificationHtmlContent content) {
        if (content == null) {
            return null;
        }
        String sourceUrl = content.sourceUrl() == null ? null : safeContentUrl(content.sourceUrl());
        return new NotificationHtmlContent(sourceUrl, content.html());
    }

    private static boolean hasForbiddenUrlCharacter(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\\' || Character.isISOControl(character)) {
                return true;
            }
        }
        return false;
    }

    private static NotificationMessage safeStoredMessage(NotificationMessage message) {
        if (message == null) {
            return null;
        }
        String contentUrl;
        try {
            contentUrl = safeContentUrl(message.contentUrl());
        } catch (IllegalArgumentException ignored) {
            contentUrl = null;
        }
        String contentHtml = message.contentHtml() == null
                || (message.contentUrl() != null && contentUrl == null) ? null : "";
        String actionUrl;
        try {
            actionUrl = message.persistentSurvey()
                    ? safeEmbeddedContentUrl(message.actionUrl())
                    : safeActionUrl(message.actionUrl());
        } catch (IllegalArgumentException ignored) {
            actionUrl = null;
        }
        if (Objects.equals(contentUrl, message.contentUrl())
                && Objects.equals(contentHtml, message.contentHtml())
                && Objects.equals(actionUrl, message.actionUrl())) {
            return message;
        }
        return new NotificationMessage(
                message.id(), message.category(), message.severity(), message.scenarioId(),
                message.title(), message.body(), contentUrl, contentHtml, actionUrl,
                message.createdTime(), message.readTime());
    }

    private record PersistentSurvey(String id, String slotId, String embedUrl, String namespace,
                                    String titleKey, String bodyKey) {
    }
}
