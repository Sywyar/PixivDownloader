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
import top.sywyar.pixivdownload.download.db.PixivDatabase;

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

    private static final String PIXIV_REFERER = "https://www.pixiv.net/";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";
    private static final Duration RENAME_VALIDATE_COOLDOWN = Duration.ofMinutes(10);

    private final AuthorMapper authorMapper;
    private final PixivDatabase pixivDatabase;
    private final RestTemplate downloadRestTemplate;
    private final TaskScheduler taskScheduler;
    private final ConcurrentHashMap<Long, Long> lastRenameValidationAtMs = new ConcurrentHashMap<>();

    public AuthorService(AuthorMapper authorMapper,
                         PixivDatabase pixivDatabase,
                         @Qualifier("downloadRestTemplate") RestTemplate downloadRestTemplate,
                         TaskScheduler taskScheduler) {
        this.authorMapper = authorMapper;
        this.pixivDatabase = pixivDatabase;
        this.downloadRestTemplate = downloadRestTemplate;
        this.taskScheduler = taskScheduler;
    }

    @PostConstruct
    public void init() {
        authorMapper.createAuthorsTable();
    }

    public List<Author> getAllAuthors() {
        return authorMapper.findAll();
    }

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
            authorMapper.insertIfAbsent(authorId, initialName, nowSeconds());
            log.info("首次记录作者: authorId={}, name={}", authorId, initialName);
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
                log.warn("作者改名校验失败: authorId={}, response={}", authorId, root);
                return;
            }

            String actualName = normalizeHintName(root.path("body").path("name").asText(null));
            if (!StringUtils.hasText(actualName) || actualName.equals(existing.name())) {
                return;
            }

            authorMapper.updateName(authorId, actualName, nowSeconds());
            log.info("作者改名: {} -> {} (authorId={})", existing.name(), actualName, authorId);
        } catch (Exception e) {
            log.warn("作者改名校验失败: authorId={}", authorId, e);
        }
    }

    void lookupMissingAuthor(long artworkId, String cookie) {
        try {
            JsonNode root = fetchJson("https://www.pixiv.net/ajax/illust/" + artworkId, cookie);
            if (isErrorResponse(root)) {
                log.warn("补齐作者信息失败: artworkId={}, response={}", artworkId, root);
                return;
            }

            JsonNode body = root.path("body");
            long authorId = body.path("userId").asLong(0);
            if (authorId <= 0) {
                log.warn("补齐作者信息失败: artworkId={}, missing userId", artworkId);
                return;
            }

            String authorName = normalizeHintName(body.path("userName").asText(null));
            pixivDatabase.updateAuthorId(artworkId, authorId);
            observe(authorId, authorName);
        } catch (Exception e) {
            log.warn("补齐作者信息失败: artworkId={}", artworkId, e);
        }
    }

    private JsonNode fetchJson(String url, String cookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Referer", PIXIV_REFERER);
        headers.set("User-Agent", USER_AGENT);
        if (StringUtils.hasText(cookie)) {
            headers.set("Cookie", cookie);
        }

        ResponseEntity<JsonNode> response = downloadRestTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        return response.getBody();
    }

    private static boolean isErrorResponse(JsonNode root) {
        return root == null || root.path("error").asBoolean(false);
    }

    private static long nowSeconds() {
        return Instant.now().getEpochSecond();
    }

    private static String normalizeHintName(String hintName) {
        if (!StringUtils.hasText(hintName)) {
            return null;
        }
        return hintName.trim();
    }
}
