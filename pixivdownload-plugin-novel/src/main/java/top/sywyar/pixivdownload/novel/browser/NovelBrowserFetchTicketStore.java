package top.sywyar.pixivdownload.novel.browser;

import top.sywyar.pixivdownload.novel.schedule.PixivNovelMetadata;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 保存浏览器或服务端预览已取得的小说元数据，并用短期一次性票据把抓取与下载请求关联起来。
 */
public final class NovelBrowserFetchTicketStore {

    public static final long TICKET_TTL_SECONDS = 120;
    private static final int MAX_ENTRIES = 256;
    private static final int TOKEN_BYTES = 32;

    private final Clock clock;
    private final SecureRandom random;
    private final Map<String, Instant> importTokens = new LinkedHashMap<>();
    private final Map<String, Ticket> fetchTickets = new LinkedHashMap<>();

    public NovelBrowserFetchTicketStore() {
        this(Clock.systemUTC(), new SecureRandom());
    }

    NovelBrowserFetchTicketStore(Clock clock, SecureRandom random) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
    }

    public synchronized String issueImportToken() {
        pruneExpired();
        evictOldest(importTokens);
        String token = newToken();
        importTokens.put(token, expiresAt());
        return token;
    }

    public synchronized boolean claimImportToken(String token) {
        pruneExpired();
        Instant expiry = importTokens.remove(normalize(token));
        return expiry != null && expiry.isAfter(clock.instant());
    }

    public synchronized String issueBrowserFetchTicket(
            long novelId, PixivNovelMetadata metadata, String rawMetaJson) {
        return issueFetchTicket(novelId, metadata, rawMetaJson, FetchOrigin.LOCAL_BROWSER_IMPORT, null);
    }

    public synchronized String issuePreviewFetchTicket(
            long novelId,
            PixivNovelMetadata metadata,
            String rawMetaJson,
            RequestOwnerIdentity ownerIdentity,
            String credential) {
        return issueFetchTicket(novelId, metadata, rawMetaJson, FetchOrigin.WEB_PREVIEW,
                RequestBinding.from(ownerIdentity, credential));
    }

    private String issueFetchTicket(
            long novelId,
            PixivNovelMetadata metadata,
            String rawMetaJson,
            FetchOrigin origin,
            RequestBinding binding) {
        if (novelId <= 0L || metadata == null || metadata.novelId() != novelId || rawMetaJson == null) {
            throw new IllegalArgumentException("invalid imported novel");
        }
        pruneExpired();
        evictOldest(fetchTickets);
        String token = newToken();
        fetchTickets.put(token, new Ticket(
                novelId, new ImportedNovel(metadata, rawMetaJson, origin), binding, expiresAt()));
        return token;
    }

    public synchronized Optional<ImportedNovel> consumeFetchTicket(
            String token,
            long novelId,
            RequestOwnerIdentity ownerIdentity,
            String credential,
            boolean allowBrowserImport) {
        pruneExpired();
        Ticket ticket = fetchTickets.remove(normalize(token));
        if (ticket == null || ticket.novelId() != novelId || !ticket.expiresAt().isAfter(clock.instant())) {
            return Optional.empty();
        }
        if (ticket.importedNovel().origin() == FetchOrigin.LOCAL_BROWSER_IMPORT) {
            return allowBrowserImport ? Optional.of(ticket.importedNovel()) : Optional.empty();
        }
        if (!ticket.binding().equals(RequestBinding.from(ownerIdentity, credential))) {
            return Optional.empty();
        }
        return Optional.of(ticket.importedNovel());
    }

    private Instant expiresAt() {
        return clock.instant().plusSeconds(TICKET_TTL_SECONDS);
    }

    private void pruneExpired() {
        Instant now = clock.instant();
        importTokens.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
        fetchTickets.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }

    private static <T> void evictOldest(Map<String, T> values) {
        if (values.size() >= MAX_ENTRIES) {
            values.remove(values.keySet().iterator().next());
        }
    }

    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        String token;
        do {
            random.nextBytes(bytes);
            token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } while (importTokens.containsKey(token) || fetchTickets.containsKey(token));
        return token;
    }

    private static String normalize(String token) {
        return token == null ? "" : token.trim();
    }

    private static String credentialDigest(String credential) {
        try {
            byte[] value = credential == null ? new byte[0] : credential.getBytes(StandardCharsets.UTF_8);
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public enum FetchOrigin {
        LOCAL_BROWSER_IMPORT,
        WEB_PREVIEW
    }

    public record ImportedNovel(PixivNovelMetadata metadata, String rawMetaJson, FetchOrigin origin) {
        public ImportedNovel {
            Objects.requireNonNull(metadata, "metadata");
            Objects.requireNonNull(rawMetaJson, "rawMetaJson");
            Objects.requireNonNull(origin, "origin");
        }
    }

    private record RequestBinding(RequestOwnerIdentity ownerIdentity, String credentialDigest) {
        private static RequestBinding from(RequestOwnerIdentity ownerIdentity, String credential) {
            return new RequestBinding(Objects.requireNonNull(ownerIdentity, "ownerIdentity"),
                    NovelBrowserFetchTicketStore.credentialDigest(credential));
        }
    }

    private record Ticket(
            long novelId, ImportedNovel importedNovel, RequestBinding binding, Instant expiresAt) {
    }
}
