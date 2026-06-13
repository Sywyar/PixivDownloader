package top.sywyar.pixivdownload.author;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.common.PixivRequestHeaders;
import top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.util.TimestampUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@Slf4j
public class AuthorService {

    private static final Duration RENAME_VALIDATE_COOLDOWN = Duration.ofMinutes(10);

    private final AuthorMapper authorMapper;
    private final PixivDatabase pixivDatabase;
    private final RestTemplate downloadRestTemplate;
    private final TaskScheduler taskScheduler;
    private final AppMessages messages;
    /** 不直接使用：仅表达对 {@link DatabaseInitializer} 的初始化顺序依赖（{@link #init()} 要求表已建好）。 */
    @SuppressWarnings("unused")
    private final DatabaseInitializer databaseInitializer;
    private final ConcurrentHashMap<Long, Long> lastRenameValidationAtMs = new ConcurrentHashMap<>();

    public AuthorService(AuthorMapper authorMapper,
                         PixivDatabase pixivDatabase,
                         @Qualifier("downloadRestTemplate") RestTemplate downloadRestTemplate,
                         @Qualifier("taskScheduler") TaskScheduler taskScheduler,
                         AppMessages messages,
                         DatabaseInitializer databaseInitializer) {
        this.authorMapper = authorMapper;
        this.pixivDatabase = pixivDatabase;
        this.downloadRestTemplate = downloadRestTemplate;
        this.taskScheduler = taskScheduler;
        this.messages = messages;
        this.databaseInitializer = databaseInitializer;
    }

    /** 非 DDL 初始化：建表已统一由 {@link DatabaseInitializer} 执行，这里只保留幂等数据迁移。 */
    @PostConstruct
    public void init() {
        authorMapper.migrateAuthorTimestampsToMillis();
    }

    public List<Author> getAllAuthors() {
        return authorMapper.findAll();
    }

    public List<Author> getAllAuthors(java.util.Set<Long> filterIds) {
        if (filterIds == null) return getAllAuthors();
        if (filterIds.isEmpty()) return Collections.emptyList();
        return authorMapper.findAll().stream()
                .filter(a -> filterIds.contains(a.authorId()))
                .toList();
    }

    public PagedAuthors getPagedAuthorsWithArtworks(int page, int size, String search, String sort) {
        return getPagedAuthorsWithArtworks(page, size, search, sort, null);
    }

    public PagedAuthors getPagedAuthorsWithArtworks(int page, int size, String search, String sort,
                                                    java.util.Set<Long> filterIds) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 200);
        String normalizedSearch = StringUtils.hasText(search) ? "%" + search.trim() + "%" : "%";
        String normalizedSort = "artworks".equals(sort) || "authorId".equals(sort) ? sort : "name";
        if (filterIds == null) {
            long total = authorMapper.countAuthorsWithArtworks(normalizedSearch);
            List<AuthorSummary> rows = total == 0
                    ? Collections.emptyList()
                    : authorMapper.findAuthorsWithArtworks(
                            normalizedSearch, normalizedSort, safeSize, safePage * safeSize);
            int totalPages = (int) Math.ceil((double) total / safeSize);
            return new PagedAuthors(rows, total, safePage, safeSize, totalPages);
        }
        if (filterIds.isEmpty()) {
            return new PagedAuthors(Collections.emptyList(), 0, safePage, safeSize, 0);
        }
        // 简化：拉全量后内存过滤再分页（作者总量通常远小于作品量）。
        List<AuthorSummary> all = authorMapper.findAuthorsWithArtworks(
                normalizedSearch, normalizedSort, Integer.MAX_VALUE, 0);
        List<AuthorSummary> filtered = all.stream()
                .filter(s -> filterIds.contains(s.authorId()))
                .toList();
        long total = filtered.size();
        int totalPages = (int) Math.ceil((double) total / safeSize);
        int from = Math.min(safePage * safeSize, filtered.size());
        int to = Math.min(from + safeSize, filtered.size());
        return new PagedAuthors(filtered.subList(from, to), total, safePage, safeSize, totalPages);
    }

    public record PagedAuthors(List<AuthorSummary> content, long totalElements,
                               int page, int size, int totalPages) {}

    public Map<Long, String> getAuthorNames(Collection<Long> authorIds) {
        if (authorIds == null || authorIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return authorMapper.findByIds(authorIds).stream()
                .collect(Collectors.toMap(Author::authorId, Author::name));
    }

    public void observe(long authorId, String hintName) {
        Author existing = authorMapper.findById(authorId);
        String normalizedHint = normalizeHintName(hintName);

        if (existing == null) {
            String initialName = StringUtils.hasText(normalizedHint) ? normalizedHint : String.valueOf(authorId);
            authorMapper.insertIfAbsent(authorId, initialName, TimestampUtils.nowMillis());
            log.info(message(
                    "author.log.observe.first-record",
                    authorId,
                    initialName
            ));
            return;
        }

        if (!StringUtils.hasText(normalizedHint) || normalizedHint.equals(existing.name())) {
            return;
        }

        asyncValidateRename(authorId);
    }

    public void asyncValidateRename(long authorId) {
        long nowMs = System.currentTimeMillis();
        Long lastMs = lastRenameValidationAtMs.get(authorId);
        if (lastMs != null && nowMs - lastMs < RENAME_VALIDATE_COOLDOWN.toMillis()) {
            return;
        }
        lastRenameValidationAtMs.put(authorId, nowMs);
        taskScheduler.schedule(() -> validateRename(authorId), Instant.now());
    }

    public void asyncLookupMissing(long artworkId, String cookie) {
        taskScheduler.schedule(() -> lookupMissingAuthor(artworkId, cookie), Instant.now());
    }

    void validateRename(long authorId) {
        try {
            Author existing = authorMapper.findById(authorId);
            if (existing == null) {
                return;
            }

            JsonNode root = fetchJson("https://www.pixiv.net/ajax/user/" + authorId + "?full=0", null);
            if (isErrorResponse(root)) {
                log.warn(message(
                        "author.log.rename.validation.failed.response",
                        authorId,
                        root
                ));
                return;
            }

            String actualName = normalizeHintName(root.path("body").path("name").asText(null));
            if (!StringUtils.hasText(actualName) || actualName.equals(existing.name())) {
                return;
            }

            authorMapper.updateName(authorId, actualName, TimestampUtils.nowMillis());
            log.info(message(
                    "author.log.rename.updated",
                    existing.name(),
                    actualName,
                    authorId
            ));
        } catch (Exception e) {
            log.warn(message(
                    "author.log.rename.validation.failed.exception",
                    authorId
            ), e);
        }
    }

    void lookupMissingAuthor(long artworkId, String cookie) {
        try {
            JsonNode root = fetchJson("https://www.pixiv.net/ajax/illust/" + artworkId, cookie);
            if (isErrorResponse(root)) {
                log.warn(message(
                        "author.log.lookup.failed.response",
                        artworkId,
                        root
                ));
                return;
            }

            JsonNode body = root.path("body");
            long authorId = body.path("userId").asLong(0);
            if (authorId <= 0) {
                log.warn(message(
                        "author.log.lookup.failed.missing-user-id",
                        artworkId
                ));
                return;
            }

            String authorName = normalizeHintName(body.path("userName").asText(null));
            pixivDatabase.updateAuthorId(artworkId, authorId);
            observe(authorId, authorName);
        } catch (Exception e) {
            log.warn(message(
                    "author.log.lookup.failed.exception",
                    artworkId
            ), e);
        }
    }

    private JsonNode fetchJson(String url, String cookie) {
        HttpHeaders headers = PixivRequestHeaders.ajax(cookie);

        ResponseEntity<JsonNode> response = downloadRestTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        return response.getBody();
    }

    private static boolean isErrorResponse(JsonNode root) {
        return root == null || root.path("error").asBoolean(false);
    }

    private static String normalizeHintName(String hintName) {
        if (!StringUtils.hasText(hintName)) {
            return null;
        }
        return hintName.trim();
    }

    private String message(String code, Object... args) {
        return messages.getForLog(code, args);
    }
}
