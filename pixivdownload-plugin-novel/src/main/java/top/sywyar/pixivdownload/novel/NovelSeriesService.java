package top.sywyar.pixivdownload.novel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import top.sywyar.pixivdownload.core.pixiv.PixivDescriptionHtml;
import top.sywyar.pixivdownload.core.pixiv.PixivImageDownloader;
import top.sywyar.pixivdownload.core.pixiv.PixivImageTransferObserver;
import top.sywyar.pixivdownload.config.DownloadSettings;
import top.sywyar.pixivdownload.core.work.model.WorkTag;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.novel.db.NovelSeries;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 下载流程中持久化 Pixiv 小说系列的标题/简介/封面到 {@code novel_series} 表，
 * 并把封面落盘到 {@code {rootFolder}/novel-series-{seriesId}/cover.{ext}}。
 * 该流程与其它作品系列的宿主侧观察流程保持同一组持久化时机。
 */
@Service
@Slf4j
public class NovelSeriesService {

    private static final Set<String> COVER_EXT_WHITELIST = Set.of("jpg", "jpeg", "png", "webp");

    private final NovelDatabase novelDatabase;
    private final DownloadSettings downloadConfig;
    private final PixivImageDownloader imageDownloader;
    private final MessageResolver messages;

    public NovelSeriesService(NovelDatabase novelDatabase,
                              DownloadSettings downloadConfig,
                              PixivImageDownloader imageDownloader,
                              MessageResolver messages) {
        this.novelDatabase = novelDatabase;
        this.downloadConfig = downloadConfig;
        this.imageDownloader = imageDownloader;
        this.messages = messages;
    }

    /**
     * 下载流程内的系列元数据持久化，额外允许传入 series tags
     * （非空则整体替换 {@code novel_series_tags}）。
     */
    public void observeWithMetadata(long seriesId, String title, Long authorId,
                                    String description, String coverUrl,
                                    List<WorkTag> tags, String cookie) {
        if (seriesId <= 0) return;
        try {
            novelDatabase.observeSeries(seriesId, StringUtils.hasText(title) ? title : null, authorId);
            NovelSeries existing = novelDatabase.getSeries(seriesId);
            if (existing == null) return;

            String desiredDescription = description != null && !description.isBlank()
                    ? PixivDescriptionHtml.normalizeLinks(description)
                    : existing.description();
            String coverExt = existing.coverExt();
            String coverFolder = existing.coverFolder();
            if ((coverExt == null || coverExt.isBlank())
                    && coverUrl != null && !coverUrl.isBlank()) {
                Path coverDir = resolveCoverDir(seriesId);
                String downloadedExt = downloadCover(seriesId, coverUrl, coverDir, cookie);
                if (downloadedExt != null) {
                    coverExt = downloadedExt;
                    coverFolder = coverDir.toString();
                }
            }
            boolean descChanged = !java.util.Objects.equals(desiredDescription, existing.description());
            boolean coverChanged = !java.util.Objects.equals(coverExt, existing.coverExt())
                    || !java.util.Objects.equals(coverFolder, existing.coverFolder());
            if (descChanged || coverChanged) {
                novelDatabase.updateSeriesMetadata(seriesId, desiredDescription, coverExt, coverFolder);
            }

            if (tags != null && !tags.isEmpty()) {
                novelDatabase.clearNovelSeriesTags(seriesId);
                novelDatabase.saveNovelSeriesTags(seriesId, tags);
            }
        } catch (Exception e) {
            log.warn(messages.getForLog("novel.series.log.refresh.failed.exception", seriesId), e);
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

    private String downloadCover(long seriesId, String coverUrl, Path coverDir, String cookie) {
        try {
            URI source = URI.create(coverUrl.trim());
            String extension = inferCoverExtension(source.getPath());
            boolean downloaded = imageDownloader.download(
                    source,
                    URI.create("https://www.pixiv.net/novel/series/" + seriesId),
                    coverDir.resolve("cover." + extension),
                    cookie,
                    new PixivImageTransferObserver() {
                    });
            return downloaded ? extension : null;
        } catch (Exception e) {
            log.warn(messages.getForLog("novel.series.log.refresh.failed.exception", seriesId), e);
            return null;
        }
    }

    private static String inferCoverExtension(String path) {
        if (path == null) {
            return "jpg";
        }
        int slash = path.lastIndexOf('/');
        String fileName = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "jpg";
        }
        String candidate = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return COVER_EXT_WHITELIST.contains(candidate) ? candidate : "jpg";
    }
}
