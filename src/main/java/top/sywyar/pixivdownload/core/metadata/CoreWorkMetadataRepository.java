package top.sywyar.pixivdownload.core.metadata;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.author.AuthorService;
import top.sywyar.pixivdownload.core.db.ArtworkRecord;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.core.db.TagDto;
import top.sywyar.pixivdownload.plugin.api.work.model.NovelWorkDetails;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkMetadata;
import top.sywyar.pixivdownload.plugin.api.work.service.WorkMetadataRepository;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkTag;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkType;
import top.sywyar.pixivdownload.series.MangaSeries;
import top.sywyar.pixivdownload.series.MangaSeriesService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@link WorkMetadataRepository} 的核心实现：插画侧代理 {@link PixivDatabase}（行 / 标签 /
 * 文件名模板）+ {@link AuthorService}（作者名）+ {@link MangaSeriesService}（系列标题），
 * 小说侧代理 {@link NovelMetadataRepository}（行 / 标签 / 系列标题 / 内嵌图片 / 译文语言）；
 * 字段补全语义与画廊页既有装配逐字段一致。
 *
 * <p>批量契约：{@link #findAll} 对行读取与各关联补全各发一次批量查询（禁止 N+1），
 * 返回顺序与传入 id 顺序一致；软删除行与未知 id 直接跳过。单条 {@link #find} 统一
 * 委托批量路径，保持「id → 行」单一来源。
 */
@Component
@RequiredArgsConstructor
public class CoreWorkMetadataRepository implements WorkMetadataRepository {

    /** 插画侧「模板 id 缺省取 1」的既有规则（与画廊页装配一致）。 */
    private static final long DEFAULT_FILE_NAME_TEMPLATE_ID = 1L;

    private final PixivDatabase pixivDatabase;
    private final NovelMetadataRepository novelMetadataRepository;
    private final AuthorService authorService;
    private final MangaSeriesService mangaSeriesService;

    @Override
    public Optional<WorkMetadata> find(WorkType workType, long workId) {
        List<WorkMetadata> hydrated = findAll(workType, List.of(workId));
        return hydrated.isEmpty() ? Optional.empty() : Optional.of(hydrated.get(0));
    }

    @Override
    public List<WorkMetadata> findAll(WorkType workType, List<Long> workIds) {
        if (workIds == null || workIds.isEmpty()) {
            return List.of();
        }
        return switch (workType) {
            case ARTWORK -> findAllArtworks(workIds);
            case NOVEL -> findAllNovels(workIds);
        };
    }

    private List<WorkMetadata> findAllArtworks(List<Long> workIds) {
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

        Map<Long, String> authorNames = resolveArtworkAuthorNames(records);
        Map<Long, String> seriesTitles = resolveArtworkSeriesTitles(records);
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
                    rec.fileAuthorNameId(),
                    null));
        }
        return out;
    }

    private List<WorkMetadata> findAllNovels(List<Long> workIds) {
        List<NovelRecord> fetched = novelMetadataRepository.getNovels(workIds);
        Map<Long, NovelRecord> byId = new HashMap<>(fetched.size());
        for (NovelRecord rec : fetched) {
            if (!rec.deleted()) {
                byId.put(rec.novelId(), rec);
            }
        }
        List<NovelRecord> records = new ArrayList<>(workIds.size());
        for (Long id : workIds) {
            NovelRecord rec = byId.get(id);
            if (rec != null) {
                records.add(rec);
            }
        }
        if (records.isEmpty()) {
            return List.of();
        }

        Set<Long> authorIds = new LinkedHashSet<>();
        Set<Long> seriesIds = new LinkedHashSet<>();
        Set<Long> templateIds = new HashSet<>();
        for (NovelRecord rec : records) {
            if (rec.authorId() != null) {
                authorIds.add(rec.authorId());
            }
            if (rec.seriesId() != null && rec.seriesId() > 0) {
                seriesIds.add(rec.seriesId());
            }
            if (rec.fileName() != null) {
                templateIds.add(rec.fileName());
            }
        }
        Map<Long, String> authorNames = authorService.getAuthorNames(authorIds);
        Map<Long, String> seriesTitles = resolveNovelSeriesTitles(seriesIds);
        List<Long> novelIds = records.stream().map(NovelRecord::novelId).toList();
        Map<Long, List<TagDto>> tagsByNovel = novelMetadataRepository.getNovelTagsBatch(novelIds);
        Map<Long, List<String>> imagesByNovel = novelMetadataRepository.getNovelImageIdsBatch(novelIds);
        Map<Long, List<String>> langsByNovel = novelMetadataRepository.getTranslationLangsBatch(novelIds);
        // 小说侧没有「模板 id 缺省取 1」规则：仅 fileName 非空时补模板内容（与原画廊装配一致）
        Map<Long, String> templates = templateIds.isEmpty()
                ? Map.of()
                : pixivDatabase.getFileNameTemplates(templateIds);

        List<WorkMetadata> out = new ArrayList<>(records.size());
        for (NovelRecord rec : records) {
            out.add(new WorkMetadata(
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
                    rec.seriesId() != null && rec.seriesId() > 0 ? seriesTitles.get(rec.seriesId()) : null,
                    toWorkTags(tagsByNovel.getOrDefault(rec.novelId(), List.of())),
                    rec.time(),
                    rec.count(),
                    rec.extensions(),
                    rec.folder(),
                    false,
                    null,
                    null,
                    rec.fileName(),
                    rec.fileName() == null ? null : templates.get(rec.fileName()),
                    rec.fileAuthorNameId(),
                    new NovelWorkDetails(
                            rec.wordCount(),
                            rec.textLength(),
                            rec.readingTimeSeconds(),
                            rec.pageCount(),
                            rec.isOriginal(),
                            rec.xLanguage(),
                            rec.coverExt(),
                            imagesByNovel.getOrDefault(rec.novelId(), List.of()),
                            langsByNovel.getOrDefault(rec.novelId(), List.of()))));
        }
        return out;
    }

    private Map<Long, String> resolveArtworkAuthorNames(List<ArtworkRecord> records) {
        Set<Long> authorIds = new HashSet<>();
        for (ArtworkRecord rec : records) {
            if (rec.authorId() != null) {
                authorIds.add(rec.authorId());
            }
        }
        return authorService.getAuthorNames(authorIds);
    }

    private Map<Long, String> resolveArtworkSeriesTitles(List<ArtworkRecord> records) {
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

    private Map<Long, String> resolveNovelSeriesTitles(Set<Long> seriesIds) {
        if (seriesIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, String> out = new HashMap<>(seriesIds.size());
        for (NovelSeries series : novelMetadataRepository.getSeriesByIds(seriesIds)) {
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
