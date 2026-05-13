package top.sywyar.pixivdownload.novel;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.common.PixivCoverDownloader;
import top.sywyar.pixivdownload.common.PixivDescriptionHtml;
import top.sywyar.pixivdownload.download.config.DownloadConfig;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.novel.db.NovelSeries;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 拉取 Pixiv 小说系列的标题/简介/封面，持久化到 {@code novel_series} 表，
 * 并把封面落盘到 {@code {rootFolder}/novel-series-{seriesId}/cover.{ext}}。
 * 与 {@link top.sywyar.pixivdownload.series.MangaSeriesService} 的漫画系列刷新流程对称。
 */
@Service
@Slf4j
public class NovelSeriesService {

    private static final String PIXIV_REFERER = "https://www.pixiv.net/";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";

    private final NovelDatabase novelDatabase;
    private final RestTemplate downloadRestTemplate;
    private final DownloadConfig downloadConfig;
    private final PixivCoverDownloader coverDownloader;
    private final AppMessages messages;

    public NovelSeriesService(NovelDatabase novelDatabase,
                              @Qualifier("downloadRestTemplate") RestTemplate downloadRestTemplate,
                              DownloadConfig downloadConfig,
                              PixivCoverDownloader coverDownloader,
                              AppMessages messages) {
        this.novelDatabase = novelDatabase;
        this.downloadRestTemplate = downloadRestTemplate;
        this.downloadConfig = downloadConfig;
        this.coverDownloader = coverDownloader;
        this.messages = messages;
    }

    public NovelSeries refreshFromPixiv(long seriesId, String cookie) {
        if (seriesId <= 0) return null;
        try {
            JsonNode root = fetchJson("https://www.pixiv.net/ajax/novel/series/" + seriesId + "?lang=zh", cookie);
            if (root == null || root.path("error").asBoolean(false)) {
                log.warn(messages.getForLog("novel.series.log.refresh.failed.response", seriesId, root));
                return novelDatabase.getSeries(seriesId);
            }
            JsonNode body = root.path("body");
            String title = body.path("title").asText("").trim();
            String caption = body.path("caption").asText("");
            Long authorId = parsePositiveLong(body.path("userId").asText(null));
            String coverUrl = extractCoverUrl(body);

            // 优先 upsert 基础字段（标题/作者），保留并发 observe 的语义。
            novelDatabase.observeSeries(seriesId, StringUtils.hasText(title) ? title : null, authorId);

            String normalizedDescription = PixivDescriptionHtml.normalizeLinks(caption);
            String coverExt = null;
            String coverFolder = null;
            if (coverUrl != null && !coverUrl.isBlank()) {
                Path coverDir = resolveCoverDir(seriesId);
                coverExt = coverDownloader.download(coverUrl, coverDir, "cover", cookie);
                if (coverExt != null) {
                    coverFolder = coverDir.toString();
                }
            }
            novelDatabase.updateSeriesMetadata(seriesId, normalizedDescription, coverExt, coverFolder);
            return novelDatabase.getSeries(seriesId);
        } catch (Exception e) {
            log.warn(messages.getForLog("novel.series.log.refresh.failed.exception", seriesId), e);
            return novelDatabase.getSeries(seriesId);
        }
    }

    /**
     * 小说系列封面磁盘目录：{@code {rootFolder}/novel-series-{seriesId}}。
     * 始终返回绝对路径，方便落盘后存入 {@code novel_series.cover_folder}。
     */
    public Path resolveCoverDir(long seriesId) {
        return Paths.get(downloadConfig.getRootFolder(), "novel-series-" + seriesId)
                .toAbsolutePath().normalize();
    }

    private static String extractCoverUrl(JsonNode body) {
        JsonNode urls = body.path("cover").path("urls");
        if (urls.isObject()) {
            for (String key : List.of("original", "1200x1200", "720x720", "480mw", "240mw")) {
                String value = urls.path(key).asText("");
                if (!value.isBlank()) return value;
            }
        }
        for (String key : List.of("coverImageUrl", "coverImage", "thumbnailUrl")) {
            String value = body.path(key).asText("");
            if (!value.isBlank()) return value;
        }
        return null;
    }

    private static Long parsePositiveLong(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
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
}
