package top.sywyar.pixivdownload.notificationbase;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import top.sywyar.pixivdownload.notification.NotificationSeverity;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpClient;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpRequest;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpStreamResponse;
import top.sywyar.pixivdownload.plugin.signature.PluginSupplyChainVerifier;
import top.sywyar.pixivdownload.plugin.signature.PluginTrustStores;
import top.sywyar.pixivdownload.plugin.signature.SignatureMetadata;
import top.sywyar.pixivdownload.plugin.signature.TrustedPluginKey;
import top.sywyar.pixivdownload.plugin.signature.internal.envelope.EnvelopeV1Codec;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongUnaryOperator;

abstract class RemoteAnnouncementImporterTestSupport {

    protected static final String PUBLISHED = "2026-08-12T00:00:00Z";
    protected static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-14T00:00:00Z"), ZoneOffset.UTC);
    protected static final String GENERATED = "2026-08-13T00:00:00Z";
    protected static final String EXPIRES = "2026-09-12T00:00:00Z";

    protected static Harness harness(Locale locale) {
        return harness(new AtomicReference<>(locale));
    }

    protected static Harness harness(AtomicReference<Locale> locale) {
        return harness(locale, CLOCK, ignored -> 0);
    }

    protected static Harness harness(Locale locale, Clock clock, LongUnaryOperator randomLong) {
        return harness(new AtomicReference<>(locale), clock, randomLong);
    }

    protected static Harness harness(
            AtomicReference<Locale> locale,
            Clock clock,
            LongUnaryOperator randomLong
    ) {
        SigningFixture signing = SigningFixture.create();
        RecordingMapper mapper = new RecordingMapper();
        NotificationInboxService inbox = new NotificationInboxService(
                mapper, () -> 500, () -> 90, List::of,
                (namespace, requested, key) -> java.util.Optional.empty(), ignored -> locale.get());
        StubClient client = new StubClient(signing);
        return new Harness(
                mapper,
                inbox,
                client,
                new RemoteAnnouncementImporter(
                        client,
                        new ObjectMapper(),
                        inbox,
                        signing.verifier(),
                        clock,
                        randomLong),
                signing);
    }

    protected static Map<String, List<String>> jsonHeaders() {
        return Map.of("Content-Type", List.of("application/json; charset=utf-8"));
    }

    protected static Map<String, List<String>> htmlHeaders() {
        return Map.of("Content-Type", List.of("text/html; charset=utf-8"));
    }

    protected static String htmlBody() {
        return "<!doctype html><html><body><p>Stored locally</p></body></html>";
    }

    protected static String index(String... announcements) {
        return indexWithSequenceAndLocales(
                1, "[\"zh-CN\",\"en-US\"]", String.join(",", announcements));
    }

    protected static String indexWithLocales(String locales, String announcements) {
        return indexWithSequenceAndLocales(1, locales, announcements);
    }

    protected static String indexWithSequenceAndLocales(
            long sequence,
            String locales,
            String announcements
    ) {
        return indexWithMetadata(sequence, GENERATED, EXPIRES, locales, announcements);
    }

    protected static String indexWithMetadata(
            long sequence,
            String generatedAt,
            String expiresAt,
            String locales,
            String announcements
    ) {
        return """
                {
                  "schemaVersion": 1,
                  "sequence": %d,
                  "generatedAt": "%s",
                  "expiresAt": "%s",
                  "requiredLocales": %s,
                  "announcements": [%s]
                }
                """.formatted(sequence, generatedAt, expiresAt, locales, announcements);
    }

    protected static String item(String id, String publishedAt, String severity) {
        return itemWithTranslations(id, publishedAt, severity, """
                "zh-CN": {
                  "title": "中文 %s",
                  "summary": "中文摘要 %s",
                  "contentUrl": "%s%s/zh-CN.html",
                  "contentSha256": "%s"
                },
                "en-US": {
                  "title": "English %s",
                  "summary": "English summary %s",
                  "contentUrl": "%s%s/en-US.html",
                  "contentSha256": "%s"
                }
                """.formatted(
                id, id, contentBase(), id, htmlSha256(),
                id, id, contentBase(), id, htmlSha256()));
    }

    protected static String itemWithTranslations(
            String id,
            String publishedAt,
            String severity,
            String translations
    ) {
        return """
                {
                  "id": "%s",
                  "publishedAt": "%s",
                  "severity": "%s",
                  "locales": {%s}
                }
                """.formatted(id, publishedAt, severity, translations);
    }

    protected static String contentBase() {
        return "https://sywyar.github.io/PixivDownloader-Remote-Content/announcements/";
    }

    protected static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    protected static String htmlSha256() {
        return sha256Hex(bytes(htmlBody()));
    }

    private static String sha256Hex(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    protected static final class Harness {
        final RecordingMapper mapper;
        final NotificationInboxService inbox;
        final StubClient client;
        final RemoteAnnouncementImporter importer;
        final SigningFixture signing;

        Harness(
                RecordingMapper mapper,
                NotificationInboxService inbox,
                StubClient client,
                RemoteAnnouncementImporter importer,
                SigningFixture signing
        ) {
            this.mapper = mapper;
            this.inbox = inbox;
            this.client = client;
            this.importer = importer;
            this.signing = signing;
        }

        int importIndex(byte[] bytes) {
            return importer.importIndex(bytes, signing.signatureBytes(bytes));
        }
    }

    protected record ResponsePlan(
            int status,
            Map<String, List<String>> headers,
            byte[] body,
            RuntimeException failure
    ) {
        static ResponsePlan failure(RuntimeException failure) {
            return new ResponsePlan(200, jsonHeaders(), new byte[0], failure);
        }
    }

    protected record SigningFixture(
            KeyPair keyPair,
            PluginSupplyChainVerifier verifier,
            String keyId
    ) {
        static SigningFixture create() {
            try {
                KeyPair pair = KeyPairGenerator.getInstance(SignatureMetadata.ED25519).generateKeyPair();
                String keyId = "remote-announcement-test-key";
                TrustedPluginKey key = new TrustedPluginKey(
                        keyId,
                        SignatureMetadata.ED25519,
                        Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()),
                        TrustedPluginKey.State.ACTIVE,
                        "Test Publisher",
                        "Test Root",
                        true);
                return new SigningFixture(
                        pair,
                        new PluginSupplyChainVerifier(PluginTrustStores.of(List.of(key))),
                        keyId);
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }

        byte[] signatureBytes(byte[] manifest) {
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(manifest);
                byte[] envelope = EnvelopeV1Codec.manifestMessage(
                        RemoteAnnouncementImporter.REPOSITORY_ID, manifest.length, digest);
                Signature signer = Signature.getInstance(SignatureMetadata.ED25519);
                signer.initSign(keyPair.getPrivate());
                signer.update(envelope);
                String value = Base64.getEncoder().encodeToString(signer.sign());
                return bytes("""
                        {"formatVersion":1,"algorithm":"Ed25519","keyId":"%s","value":"%s"}
                        """.formatted(keyId, value));
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }
    }

    protected static final class StubClient implements OutboundHttpClient {
        final CountDownLatch requested = new CountDownLatch(1);
        final CountDownLatch contentRequested = new CountDownLatch(2);
        final AtomicInteger requests = new AtomicInteger();
        final AtomicInteger contentRequests = new AtomicInteger();
        private final SigningFixture signing;
        volatile ResponsePlan plan;
        volatile ResponsePlan signaturePlan;
        volatile ResponsePlan contentPlan = new ResponsePlan(
                200, htmlHeaders(), bytes(htmlBody()), null);
        volatile OutboundHttpRequest lastIndexRequest;
        volatile OutboundHttpRequest lastSignatureRequest;
        volatile OutboundHttpRequest lastContentRequest;
        volatile boolean closed;

        StubClient(SigningFixture signing) {
            this.signing = signing;
            respond(200, jsonHeaders(), index(item("scheduled", PUBLISHED, "info")));
        }

        void respond(int status, Map<String, List<String>> headers, String body) {
            plan = new ResponsePlan(status, headers, bytes(body), null);
            signaturePlan = new ResponsePlan(
                    200, jsonHeaders(), signing.signatureBytes(bytes(body)), null);
        }

        @Override
        public OutboundHttpStreamResponse exchangeStream(OutboundHttpRequest request) {
            requests.incrementAndGet();
            requested.countDown();
            boolean indexRequest = RemoteAnnouncementImporter.INDEX_URI.equals(request.uri());
            boolean signatureRequest = RemoteAnnouncementImporter.SIGNATURE_URI.equals(request.uri());
            if (indexRequest) {
                lastIndexRequest = request;
            } else if (signatureRequest) {
                lastSignatureRequest = request;
            } else {
                lastContentRequest = request;
                contentRequests.incrementAndGet();
                contentRequested.countDown();
            }
            ResponsePlan current = indexRequest ? plan : signatureRequest ? signaturePlan : contentPlan;
            if (current.failure() != null) {
                throw current.failure();
            }
            return new OutboundHttpStreamResponse(
                    current.status(),
                    "",
                    current.headers(),
                    new ByteArrayInputStream(current.body()));
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    protected static final class RecordingMapper implements NotificationInboxMapper {
        private final List<NotificationMessage> messages = new CopyOnWriteArrayList<>();
        private final java.util.Set<String> dismissedIds = ConcurrentHashMap.newKeySet();
        private final Map<String, List<RemoteAnnouncementTranslation>> remoteTranslations =
                new ConcurrentHashMap<>();
        private long acceptedSequence;
        private String acceptedDigest;
        private long acceptedExpiresTime;
        private String etag;
        private String lastModified;

        @Override
        public int insert(NotificationMessage message) {
            if (dismissedIds.contains(message.id()) || findById(message.id()) != null) {
                return 0;
            }
            messages.add(message);
            return 1;
        }

        @Override
        public List<NotificationMessage> findLatest(String category, boolean unreadOnly, int limit) {
            return messages.stream()
                    .filter(message -> category == null || category.equals(message.category()))
                    .filter(message -> !unreadOnly || message.readTime() == null)
                    .sorted(Comparator.comparingLong(NotificationMessage::createdTime).reversed()
                            .thenComparing(NotificationMessage::id, Comparator.reverseOrder()))
                    .limit(limit)
                    .toList();
        }

        @Override
        public NotificationMessage findById(String id) {
            return messages.stream()
                    .filter(message -> message.id().equals(id))
                    .findFirst()
                    .orElse(null);
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
                    || message != null
                    && !NotificationCategory.ANNOUNCEMENT.token().equals(message.category());
        }

        @Override
        public int updateRemoteAnnouncement(NotificationMessage replacement) {
            String id = replacement.id();
            synchronized (messages) {
                NotificationMessage current = findById(id);
                if (current == null || current.createdTime() != replacement.createdTime()
                        || !NotificationCategory.ANNOUNCEMENT.token().equals(current.category())) {
                    return 0;
                }
                messages.remove(current);
                messages.add(new NotificationMessage(
                        current.id(), current.category(), replacement.severity(), current.scenarioId(),
                        replacement.title(), replacement.body(), replacement.contentUrl(),
                        replacement.contentHtml(), current.actionUrl(), current.createdTime(),
                        current.readTime()));
                return 1;
            }
        }

        @Override
        public List<RemoteAnnouncementTranslation> findRemoteAnnouncementTranslations(
                String announcementId
        ) {
            if (findById(announcementId) == null) {
                return List.of();
            }
            return remoteTranslations.getOrDefault(announcementId, List.of()).stream()
                    .map(translation -> new RemoteAnnouncementTranslation(
                            translation.locale(),
                            translation.title(),
                            translation.summary(),
                            translation.contentUrl(),
                            translation.contentSha256(),
                            ""))
                    .sorted(Comparator.comparing(RemoteAnnouncementTranslation::locale))
                    .toList();
        }

        @Override
        public NotificationHtmlContent findRemoteAnnouncementHtml(
                String announcementId,
                String locale
        ) {
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
                RemoteAnnouncementTranslation translation
        ) {
            remoteTranslations.compute(announcementId, (ignored, existing) -> {
                List<RemoteAnnouncementTranslation> updated = new ArrayList<>(
                        existing == null ? List.of() : existing);
                updated.removeIf(current -> current.locale().equals(translation.locale()));
                updated.add(translation);
                return List.copyOf(updated);
            });
            return 1;
        }

        @Override
        public int deleteStaleRemoteAnnouncementTranslations(
                String announcementId,
                List<String> locales
        ) {
            AtomicInteger removed = new AtomicInteger();
            remoteTranslations.computeIfPresent(announcementId, (ignored, existing) -> {
                List<RemoteAnnouncementTranslation> updated = new ArrayList<>(existing);
                int before = updated.size();
                updated.removeIf(translation -> !locales.contains(translation.locale()));
                removed.set(before - updated.size());
                return List.copyOf(updated);
            });
            return removed.get();
        }

        @Override
        public int deleteRemoteAnnouncementTranslations(String announcementId) {
            List<RemoteAnnouncementTranslation> removed = remoteTranslations.remove(announcementId);
            return removed == null ? 0 : removed.size();
        }

        @Override
        public synchronized int acceptRemoteAnnouncementIndex(
                long sequence,
                String manifestSha256,
                long generatedTime,
                long expiresTime
        ) {
            if (sequence < acceptedSequence
                    || sequence == acceptedSequence
                    && !Objects.equals(manifestSha256, acceptedDigest)) {
                return 0;
            }
            if (!Objects.equals(manifestSha256, acceptedDigest)) {
                etag = null;
                lastModified = null;
            }
            acceptedSequence = sequence;
            acceptedDigest = manifestSha256;
            acceptedExpiresTime = expiresTime;
            return 1;
        }

        @Override
        public synchronized RemoteAnnouncementValidators findRemoteAnnouncementValidators() {
            return acceptedDigest == null
                    ? null
                    : new RemoteAnnouncementValidators(
                            acceptedDigest,
                            acceptedExpiresTime,
                            etag,
                            lastModified);
        }

        @Override
        public synchronized int saveRemoteAnnouncementValidators(
                String manifestSha256,
                String etag,
                String lastModified
        ) {
            if (!Objects.equals(manifestSha256, acceptedDigest)) {
                return 0;
            }
            this.etag = etag;
            this.lastModified = lastModified;
            return 1;
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
            synchronized (messages) {
                NotificationMessage current = findById(id);
                if (current == null || current.readTime() != null) {
                    return 0;
                }
                messages.remove(current);
                messages.add(new NotificationMessage(
                        current.id(), current.category(), current.severity(), current.scenarioId(),
                        current.title(), current.body(), current.contentUrl(), current.contentHtml(),
                        current.actionUrl(), current.createdTime(),
                        Math.max(current.createdTime(), readTime)));
                return 1;
            }
        }

        @Override
        public int markAllRead(String category, long readTime) {
            int updated = 0;
            for (NotificationMessage message : List.copyOf(messages)) {
                if (category == null || category.equals(message.category())) {
                    updated += markRead(message.id(), readTime);
                }
            }
            return updated;
        }

        @Override
        public int dismissAnnouncement(String id, long deletedTime) {
            NotificationMessage message = findById(id);
            if (message == null
                    || !NotificationCategory.ANNOUNCEMENT.token().equals(message.category())) {
                return 0;
            }
            messages.remove(message);
            dismissedIds.add(id);
            return 1;
        }

        @Override
        public int dismissPersistentSurvey(String id, long deletedTime) {
            return 0;
        }

        @Override
        public int setActivePersistentSurveys(List<String> activeIds) {
            return 0;
        }

        @Override
        public int deleteNonAnnouncement(String id) {
            NotificationMessage message = findById(id);
            if (message == null
                    || NotificationCategory.ANNOUNCEMENT.token().equals(message.category())) {
                return 0;
            }
            return messages.remove(message) ? 1 : 0;
        }

        @Override
        public int pruneRetentionPool(long cutoffTime, int maxMessages) {
            return 0;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    protected static class LifecycleConfiguration {

        @Bean(name = "notificationAnnouncementTaskScheduler", destroyMethod = "shutdown")
        ThreadPoolTaskScheduler scheduler() {
            return new NotificationPluginConfiguration().notificationAnnouncementTaskScheduler();
        }

        @Bean(destroyMethod = "close")
        StubClient client(SigningFixture signing) {
            return new StubClient(signing);
        }

        @Bean
        SigningFixture signing() {
            return SigningFixture.create();
        }

        @Bean
        RecordingMapper mapper() {
            return new RecordingMapper();
        }

        @Bean
        NotificationInboxService inbox(RecordingMapper mapper) {
            return new NotificationInboxService(mapper);
        }

        @Bean
        RemoteAnnouncementImporter importer(
                StubClient client,
                NotificationInboxService inbox,
                SigningFixture signing
        ) {
            return new RemoteAnnouncementImporter(
                    client,
                    new ObjectMapper(),
                    inbox,
                    signing.verifier(),
                    CLOCK,
                    ignored -> 0);
        }
    }

    protected static final class MutableClock extends Clock {
        private final AtomicReference<Instant> current;

        MutableClock(Instant initial) {
            current = new AtomicReference<>(initial);
        }

        void advance(Duration duration) {
            current.updateAndGet(value -> value.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current.get();
        }
    }
}
