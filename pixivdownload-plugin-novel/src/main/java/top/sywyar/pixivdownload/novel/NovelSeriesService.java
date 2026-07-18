package top.sywyar.pixivdownload.novel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import top.sywyar.pixivdownload.common.PixivCoverDownloader;
import top.sywyar.pixivdownload.core.pixiv.PixivDescriptionHtml;
import top.sywyar.pixivdownload.config.DownloadSettings;
import top.sywyar.pixivdownload.core.db.TagDto;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.novel.db.NovelSeries;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 下载流程中持久化 Pixiv 小说系列的标题/简介/封面到 {@code novel_series} 表，
 * 并把封面落盘到 {@code {rootFolder}/novel-series-{seriesId}/cover.{ext}}。
 * 与 {@link top.sywyar.pixivdownload.series.MangaSeriesService} 的漫画系列流程对称。
 */
@Service
@Slf4j
public class NovelSeriesService {

    private final NovelDatabase novelDatabase;
    private final DownloadSettings downloadConfig;
    private final PixivCoverDownloader coverDownloader;
    private final MessageResolver messages;

    public NovelSeriesService(NovelDatabase novelDatabase,
                              DownloadSettings downloadConfig,
                              PixivCoverDownloader coverDownloader,
                              MessageResolver messages) {
        this.novelDatabase = novelDatabase;
        this.downloadConfig = downloadConfig;
        this.coverDownloader = coverDownloader;
        this.messages = messages;
    }

    /**
     * 下载流程内的"系列元数据持久化"：与
     * {@link top.sywyar.pixivdownload.series.MangaSeriesService#observeWithMetadata} 对称，
     * 额外允许传入 series tags（非空则整体替换 {@code novel_series_tags}）。
     */
    public void observeWithMetadata(long seriesId, String title, Long authorId,
                                    String description, String coverUrl,
                                    List<TagDto> tags, String cookie) {
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
                String downloadedExt = coverDownloader.download(coverUrl, coverDir, "cover", cookie);
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
}
