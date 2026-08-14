package top.sywyar.pixivdownload.notificationbase;

import org.springframework.transaction.annotation.Transactional;
import top.sywyar.pixivdownload.i18n.LocaleBundlePolicy;
import top.sywyar.pixivdownload.notification.NotificationSeverity;
import top.sywyar.pixivdownload.i18n.NamespaceMessageResolver;
import top.sywyar.pixivdownload.plugin.api.notification.NotificationTemplateContribution;
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

    private static final int MAX_ACTION_URL_BYTES = 8 * 1_024;
    private static final int MAX_CONTENT_URL_BYTES = 2 * 1_024;
    private static final String CONTENT_HOST = "sywyar.github.io";
    private static final String REMOTE_ANNOUNCEMENT_ID_PREFIX = "remote-announcement:";
    private static final String PERSISTENT_SURVEY_TARGET = "notification.inbox";
    private static final Pattern SLOT_ID = Pattern.compile("[a-z0-9][a-z0-9.-]{0,127}");
    private static final Pattern I18N_TOKEN = Pattern.compile("[a-z0-9][a-z0-9.-]{0,159}");
    private static final Pattern REMOTE_ANNOUNCEMENT_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,79}");
    private static final Pattern REMOTE_LOCALE_TAG = Pattern.compile("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})+");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern CONTENT_PATH = Pattern.compile(
            "/PixivDownloader-Remote-Content/(?:[A-Za-z0-9][A-Za-z0-9._-]*/)*"
                    + "[A-Za-z0-9][A-Za-z0-9._-]*\\.html");

    private final NotificationInboxMapper mapper;
    private final IntSupplier maxMessages;
    private final IntSupplier retentionDays;
    private final WebUiSlotCatalog uiSlots;
    private final NamespaceMessageResolver messages;
    private final Function<Locale, Locale> normalizeLocale;
    private final Function<Locale, List<Locale>> localeFallbacks;
    private volatile List<PersistentSurvey> persistentSurveys;

    public NotificationInboxService(NotificationInboxMapper mapper) {
        this(mapper, () -> NotificationPlugin.DEFAULT_INBOX_MAX_MESSAGES,
                () -> NotificationPlugin.DEFAULT_INBOX_RETENTION_DAYS,
                List::of, (namespace, locale, key) -> Optional.empty(),
                locale -> locale == null ? Locale.getDefault() : locale);
    }

    NotificationInboxService(NotificationInboxMapper mapper,
                             IntSupplier maxMessages,
                             IntSupplier retentionDays) {
        this(mapper, maxMessages, retentionDays, List::of,
                (namespace, locale, key) -> Optional.empty(),
                locale -> locale == null ? Locale.getDefault() : locale);
    }

    NotificationInboxService(NotificationInboxMapper mapper,
                             IntSupplier maxMessages,
                             IntSupplier retentionDays,
                             WebUiSlotCatalog uiSlots,
                             NamespaceMessageResolver messages,
                             Function<Locale, Locale> normalizeLocale) {
        this(mapper, maxMessages, retentionDays, uiSlots, messages, normalizeLocale,
                requested -> List.of(normalizeLocale.apply(requested)));
    }

    NotificationInboxService(NotificationInboxMapper mapper,
                             IntSupplier maxMessages,
                             IntSupplier retentionDays,
                             WebUiSlotCatalog uiSlots,
                             NamespaceMessageResolver messages,
                             LocaleBundlePolicy localePolicy) {
        this(mapper, maxMessages, retentionDays, uiSlots, messages,
                localePolicy::normalize,
                requested -> localeFallbacks(localePolicy, requested));
    }

    private NotificationInboxService(NotificationInboxMapper mapper,
                                     IntSupplier maxMessages,
                                     IntSupplier retentionDays,
                                     WebUiSlotCatalog uiSlots,
                                     NamespaceMessageResolver messages,
                                     Function<Locale, Locale> normalizeLocale,
                                     Function<Locale, List<Locale>> localeFallbacks) {
        this.mapper = Objects.requireNonNull(mapper, "notification inbox mapper");
        this.maxMessages = Objects.requireNonNull(maxMessages, "notification inbox max messages");
        this.retentionDays = Objects.requireNonNull(retentionDays, "notification inbox retention days");
        this.uiSlots = Objects.requireNonNull(uiSlots, "web UI slot catalog");
        this.messages = Objects.requireNonNull(messages, "namespace message resolver");
        this.normalizeLocale = Objects.requireNonNull(normalizeLocale, "locale normalizer");
        this.localeFallbacks = Objects.requireNonNull(localeFallbacks, "locale fallback policy");
        synchronizePersistentSurveys();
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
        if (mapper.insert(message) == 1 && isRetentionPoolCategory(selectedCategory)) {
            pruneRetentionPool(now);
        }
        return message;
    }

    @Transactional
    public boolean storeRemoteAnnouncement(String remoteId,
                                           NotificationSeverity severity,
                                           List<RemoteAnnouncementTranslation> translations,
                                           long publishedAt) {
        String normalizedId = remoteAnnouncementId(remoteId);
        if (publishedAt < 0) {
            throw new IllegalArgumentException("remote announcement published time is invalid");
        }
        String messageId = REMOTE_ANNOUNCEMENT_ID_PREFIX + normalizedId;
        List<RemoteAnnouncementTranslation> validatedTranslations = validatedRemoteTranslations(translations, true);
        if (mapper.blocksRemoteAnnouncementImport(messageId)) {
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
        int changed = mapper.insert(message);
        changed += mapper.restoreRemoteAnnouncementHtml(
                message.id(), message.contentUrl(), message.contentHtml());
        for (RemoteAnnouncementTranslation translation : validatedTranslations) {
            changed += mapper.upsertRemoteAnnouncementTranslation(messageId, translation);
        }
        changed += mapper.deleteStaleRemoteAnnouncementTranslations(
                messageId, validatedTranslations.stream().map(RemoteAnnouncementTranslation::locale).toList());
        return changed > 0;
    }

    boolean needsRemoteAnnouncementImport(String remoteId,
                                          List<RemoteAnnouncementTranslation> translations) {
        String messageId = REMOTE_ANNOUNCEMENT_ID_PREFIX + remoteAnnouncementId(remoteId);
        if (mapper.blocksRemoteAnnouncementImport(messageId)) {
            return false;
        }
        List<RemoteAnnouncementTranslation> expected = validatedRemoteTranslations(translations, false);
        List<RemoteAnnouncementTranslation> stored = safeRemoteTranslations(
                mapper.findRemoteAnnouncementTranslations(messageId));
        return !sameTranslationMetadata(expected, stored);
    }

    @Transactional
    boolean acceptRemoteAnnouncementIndex(long sequence,
                                          String manifestSha256,
                                          long generatedTime,
                                          long expiresTime) {
        if (sequence <= 0
                || manifestSha256 == null
                || !SHA256.matcher(manifestSha256).matches()
                || generatedTime < 0
                || expiresTime <= generatedTime) {
            throw new IllegalArgumentException("remote announcement index state is invalid");
        }
        return mapper.acceptRemoteAnnouncementIndex(
                sequence, manifestSha256, generatedTime, expiresTime) == 1;
    }

    RemoteAnnouncementValidators remoteAnnouncementValidators(long now) {
        RemoteAnnouncementValidators validators = mapper.findRemoteAnnouncementValidators();
        return validators == null || validators.expiresTime() <= now ? null : validators;
    }

    @Transactional
    boolean saveRemoteAnnouncementValidators(String manifestSha256,
                                             String etag,
                                             String lastModified) {
        if (manifestSha256 == null || !SHA256.matcher(manifestSha256).matches()) {
            throw new IllegalArgumentException("remote announcement manifest SHA-256 is invalid");
        }
        return mapper.saveRemoteAnnouncementValidators(
                manifestSha256,
                optionalHeaderValue(etag),
                optionalHeaderValue(lastModified)) == 1;
    }

    public List<NotificationMessage> latest(NotificationCategory category, boolean unreadOnly, int limit) {
        return latest(category, unreadOnly, limit, null);
    }

    public List<NotificationMessage> latest(NotificationCategory category, boolean unreadOnly, int limit,
                                            String language) {
        return mapper.findLatest(categoryToken(category), unreadOnly, Math.max(1, Math.min(100, limit))).stream()
                .map(NotificationInboxService::safeStoredMessage)
                .map(message -> localize(message, locale(language)))
                .toList();
    }

    public long unreadCount() {
        return mapper.countUnread(null);
    }

    public long unreadCount(NotificationCategory category) {
        return mapper.countUnread(categoryToken(category));
    }

    public NotificationMessage find(String id) {
        return find(id, null);
    }

    public NotificationMessage find(String id, String language) {
        return localize(safeStoredMessage(mapper.findById(Objects.requireNonNull(id, "notification id"))),
                locale(language));
    }

    public NotificationHtmlContent htmlContent(String id) {
        return htmlContent(id, null);
    }

    public NotificationHtmlContent htmlContent(String id, String language) {
        String requiredId = Objects.requireNonNull(id, "notification id");
        NotificationMessage message = safeStoredMessage(mapper.findById(requiredId));
        NotificationHtmlContent content = null;
        if (remoteAnnouncement(message)) {
            RemoteAnnouncementTranslation translation = selectRemoteTranslation(
                    safeRemoteTranslations(mapper.findRemoteAnnouncementTranslations(requiredId)),
                    locale(language));
            if (translation != null) {
                content = mapper.findRemoteAnnouncementHtml(requiredId, translation.locale());
            }
        }
        if (content == null) {
            content = mapper.findHtmlContent(requiredId);
        }
        if (content == null) {
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
        mapper.markRead(Objects.requireNonNull(id, "notification id"), System.currentTimeMillis());
        return localize(safeStoredMessage(mapper.findById(id)), locale(language));
    }

    public int markAllRead(NotificationCategory category) {
        return mapper.markAllRead(categoryToken(category), System.currentTimeMillis());
    }

    @Transactional
    public boolean delete(String id) {
        String requiredId = Objects.requireNonNull(id, "notification id");
        NotificationMessage message = mapper.findById(requiredId);
        if (message == null) {
            return false;
        }
        if (message.persistentSurvey()) {
            return false;
        }
        if (NotificationCategory.ANNOUNCEMENT.token().equals(message.category())) {
            if (mapper.dismissAnnouncement(requiredId, System.currentTimeMillis()) != 1) {
                return false;
            }
            mapper.deleteRemoteAnnouncementTranslations(requiredId);
            return true;
        }
        return mapper.deleteNonAnnouncement(requiredId) == 1;
    }

    public boolean dismissUnavailableSurvey(String id) {
        String requiredId = Objects.requireNonNull(id, "notification id");
        if (!requiredId.startsWith(NotificationMessage.PERSISTENT_SURVEY_ID_PREFIX)) {
            return false;
        }
        return mapper.dismissPersistentSurvey(requiredId, System.currentTimeMillis()) == 1;
    }

    public synchronized void synchronizePersistentSurveys() {
        List<PersistentSurvey> active = uiSlots.uiSlots().stream()
                .filter(slot -> PERSISTENT_SURVEY_TARGET.equals(slot.target()))
                .map(NotificationInboxService::persistentSurvey)
                .filter(Objects::nonNull)
                .sorted(java.util.Comparator.comparing(PersistentSurvey::id))
                .toList();
        if (active.equals(persistentSurveys)) {
            return;
        }
        mapper.setActivePersistentSurveys(active.stream().map(PersistentSurvey::id).toList());
        long now = System.currentTimeMillis();
        for (PersistentSurvey survey : active) {
            mapper.insert(new NotificationMessage(
                    survey.id(), NotificationCategory.SURVEY.token(), NotificationSeverity.INFO.name(),
                    survey.slotId(), survey.titleKey(), survey.bodyKey(), null, null,
                    survey.embedUrl(), now, null));
        }
        persistentSurveys = active;
    }

    int pruneRetentionPool() {
        return pruneRetentionPool(System.currentTimeMillis());
    }

    int pruneRetentionPool(long now) {
        int days = positiveSetting(retentionDays, NotificationPlugin.DEFAULT_INBOX_RETENTION_DAYS);
        long maxAgeMillis = TimeUnit.DAYS.toMillis(days);
        long cutoffTime = now > maxAgeMillis ? now - maxAgeMillis : 0;
        return mapper.pruneRetentionPool(
                cutoffTime,
                positiveSetting(maxMessages, NotificationPlugin.DEFAULT_INBOX_MAX_MESSAGES));
    }

    private static String categoryToken(NotificationCategory category) {
        return category == null ? null : category.token();
    }

    private NotificationMessage localize(NotificationMessage message, Locale locale) {
        if (message == null) {
            return message;
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

    private static PersistentSurvey persistentSurvey(WebUiSlotContribution slot) {
        if (slot == null || !SLOT_ID.matcher(Objects.toString(slot.slotId(), "")).matches()) {
            return null;
        }
        Map<String, String> metadata = slot.metadata();
        if (!NotificationCategory.SURVEY.token().equals(metadata.get("notification.category"))) {
            return null;
        }
        String instanceKey = metadata.get("notification.instance-key");
        if (instanceKey == null || !SLOT_ID.matcher(instanceKey).matches()) {
            return null;
        }
        String namespace = metadata.get("notification.i18n-namespace");
        String titleKey = metadata.get("notification.title-key");
        String bodyKey = metadata.get("notification.body-key");
        if (!i18nToken(namespace) || !i18nToken(titleKey) || !i18nToken(bodyKey)) {
            return null;
        }
        String embedUrl;
        try {
            embedUrl = safeEmbeddedContentUrl(metadata.get("notification.embed-url"));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        return new PersistentSurvey(
                NotificationMessage.PERSISTENT_SURVEY_ID_PREFIX + slot.slotId() + ":" + instanceKey,
                slot.slotId(),
                embedUrl, namespace, titleKey, bodyKey);
    }

    private static boolean i18nToken(String value) {
        return value != null && I18N_TOKEN.matcher(value).matches();
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
