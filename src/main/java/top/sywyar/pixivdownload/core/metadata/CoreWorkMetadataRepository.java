package top.sywyar.pixivdownload.core.metadata;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.author.AuthorService;
import top.sywyar.pixivdownload.core.db.ArtworkRecord;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.core.db.TagDto;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.novel.db.NovelRecord;
import top.sywyar.pixivdownload.novel.db.NovelSeries;
import top.sywyar.pixivdownload.plugin.api.WorkMetadata;
import top.sywyar.pixivdownload.plugin.api.WorkMetadataRepository;
import top.sywyar.pixivdownload.plugin.api.WorkTag;
import top.sywyar.pixivdownload.plugin.api.WorkType;
import top.sywyar.pixivdownload.series.MangaSeries;
import top.sywyar.pixivdownload.series.MangaSeriesService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@link WorkMetadataRepository} 的核心实现：插画侧代理 {@link PixivDatabase}（行 / 标签 /
 * 文件名模板）+ {@link AuthorService}（作者名）+ {@link MangaSeriesService}（系列标题），
 * 小说侧代理 {@link NovelDatabase}；字段补全语义与画廊页既有装配逐字段一致。
 *
 * <p>批量契约：{@link #findAll} 对行读取与各关联补全各发一次批量查询（禁止 N+1），
 * 返回顺序与传入 id 顺序一致；软删除行与未知 id 直接跳过。
 *
 * <p>{@link WorkType#NOVEL} 的 {@link #findAll} 尚未接入（小说侧无批量行查询可代理），
 * 待小说画廊改走核心接口时接入并翻转契约单测；过渡期本类对 novel.db 包的 import
 * 待小说侧仓库收编进核心数据层后消除。
 */
@Component
@RequiredArgsConstructor
public class CoreWorkMetadataRepository implements WorkMetadataRepository {

    /** 插画侧「模板 id 缺省取 1」的既有规则（与画廊页装配一致）。 */
    private static final long DEFAULT_FILE_NAME_TEMPLATE_ID = 1L;

    private final PixivDatabase pixivDatabase;
    private final NovelDatabase novelDatabase;
    private final AuthorService authorService;
    private final MangaSeriesService mangaSeriesService;

    @Override
    public Optional<WorkMetadata> find(WorkType workType, long workId) {
        return switch (workType) {
            case ARTWORK -> {
                List<WorkMetadata> hydrated = findAll(workType, List.of(workId));
                yield hydrated.isEmpty() ? Optional.empty() : Optional.of(hydrated.get(0));
            }
            case NOVEL -> findNovel(workId);
        };
    }

    @Override
    public List<WorkMetadata> findAll(WorkType workType, List<Long> workIds) {
        if (workType != WorkType.ARTWORK) {
            throw new UnsupportedOperationException(
                    "WorkMetadataRepository 尚未接入 " + workType + " 的批量查询：小说侧无批量行查询可代理，"
                            + "待小说画廊改走核心接口时接入");
        }
        if (workIds == null || workIds.isEmpty()) {
            return List.of();
        }
        List<ArtworkRecord> fetched = pixivDatabase.getArtworks(workIds);
        Map<Long, ArtworkRecord> byId = new HashMap<>(fetched.size());
        for (ArtworkRecord rec : fetched) {
            if (!rec.deleted()) {
                byId.put(rec.artworkId(), rec);
            }
        }
        List<ArtworkRecord> records = new ArrayList<>(workIds.size());
        for (Long id : workIds) {
            ArtworkRecord rec = byId.get(id);
            if (rec != null) {
                records.add(rec);
            }
        }
        if (records.isEmpty()) {
            return List.of();
        }

        Map<Long, String> authorNames = resolveAuthorNames(records);
        Map<Long, String> seriesTitles = resolveSeriesTitles(records);
        List<Long> artworkIds = records.stream().map(ArtworkRecord::artworkId).toList();
        Map<Long, List<TagDto>> tagsByArtwork = pixivDatabase.getArtworkTags(artworkIds);
        Set<Long> templateIds = new HashSet<>();
        for (ArtworkRecord rec : records) {
            templateIds.add(rec.fileName() == null ? DEFAULT_FILE_NAME_TEMPLATE_ID : rec.fileName());
        }
        Map<Long, String> templates = pixivDatabase.getFileNameTemplates(templateIds);

        List<WorkMetadata> out = new ArrayList<>(records.size());
        for (ArtworkRecord rec : records) {
            Long templateId = rec.fileName() == null ? DEFAULT_FILE_NAME_TEMPLATE_ID : rec.fileName();
            Long seriesId = rec.seriesId();
            out.add(new WorkMetadata(
                    WorkType.ARTWORK,
                    rec.artworkId(),
                    rec.title(),
                    rec.description(),
                    rec.xRestrict(),
                    rec.isAi(),
                    rec.authorId(),
                    rec.authorId() == null ? null : authorNames.get(rec.authorId()),
                    seriesId,
                    rec.seriesOrder(),
                    seriesId != null && seriesId > 0 ? seriesTitles.get(seriesId) : null,
                    toWorkTags(tagsByArtwork.getOrDefault(rec.artworkId(), List.of())),
                    rec.time(),
                    rec.count(),
                    rec.extensions(),
                    rec.folder(),
                    rec.moved(),
                    rec.moveFolder(),
                    rec.moveTime(),
                    rec.fileName(),
                    templates.get(templateId),
                    rec.fileAuthorNameId()));
        }
        return out;
    }

    private Optional<WorkMetadata> findNovel(long novelId) {
        NovelRecord rec = novelDatabase.getNovel(novelId);
        if (rec == null || rec.deleted()) {
            return Optional.empty();
        }
        Map<Long, String> authorNames = rec.authorId() == null
                ? Map.of()
                : authorService.getAuthorNames(Set.of(rec.authorId()));
        String seriesTitle = null;
        if (rec.seriesId() != null && rec.seriesId() > 0) {
            NovelSeries series = novelDatabase.getSeries(rec.seriesId());
            seriesTitle = series == null ? null : series.title();
        }
        return Optional.of(new WorkMetadata(
                WorkType.NOVEL,
                rec.novelId(),
                rec.title(),
                rec.description(),
                rec.xRestrict(),
                rec.isAi(),
                rec.authorId(),
                rec.authorId() == null ? null : authorNames.get(rec.authorId()),
                rec.seriesId(),
                rec.seriesOrder(),
                seriesTitle,
                toWorkTags(novelDatabase.getNovelTags(novelId)),
                rec.time(),
                rec.count(),
                rec.extensions(),
                rec.folder(),
                false,
                null,
                null,
                rec.fileName(),
                rec.fileName() == null ? null : pixivDatabase.getFileNameTemplate(rec.fileName()),
                rec.fileAuthorNameId()));
    }

    private Map<Long, String> resolveAuthorNames(List<ArtworkRecord> records) {
        Set<Long> authorIds = new HashSet<>();
        for (ArtworkRecord rec : records) {
            if (rec.authorId() != null) {
                authorIds.add(rec.authorId());
            }
        }
        return authorService.getAuthorNames(authorIds);
    }

    private Map<Long, String> resolveSeriesTitles(List<ArtworkRecord> records) {
        Set<Long> seriesIds = new HashSet<>();
        for (ArtworkRecord rec : records) {
            if (rec.seriesId() != null && rec.seriesId() > 0) {
                seriesIds.add(rec.seriesId());
            }
        }
        if (seriesIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, String> out = new HashMap<>(seriesIds.size());
        for (MangaSeries series : mangaSeriesService.getSeriesByIds(seriesIds)) {
            out.put(series.seriesId(), series.title());
        }
        return out;
    }

    private static List<WorkTag> toWorkTags(List<TagDto> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        List<WorkTag> out = new ArrayList<>(tags.size());
        for (TagDto tag : tags) {
            if (tag == null) {
                continue;
            }
            out.add(new WorkTag(tag.getTagId(), tag.getName(), tag.getTranslatedName()));
        }
        return out;
    }
}
