package top.sywyar.pixivdownload.novel.db;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import top.sywyar.pixivdownload.download.db.PathPrefixCodec;
import top.sywyar.pixivdownload.download.db.PixivDatabase;
import top.sywyar.pixivdownload.download.db.TagDto;
import top.sywyar.pixivdownload.util.TimestampUtils;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Repository
@RequiredArgsConstructor
public class NovelDatabase {

    private final NovelMapper novelMapper;
    private final PixivDatabase pixivDatabase;
    private final PathPrefixCodec pathPrefixCodec;

    /**
     * 进程内已分配但可能尚未持久化的最大 novel time。
     * 与 {@link PixivDatabase#getUniqueTime} 同样的原因 —— 防止并发下载时多个 worker
     * 拿到相同时间戳后被 {@code INSERT OR REPLACE} 互相覆盖导致已写入的行丢失。
     */
    private final AtomicLong lastIssuedTime = new AtomicLong(0);

    @PostConstruct
    public void init() {
        novelMapper.createNovelsTable();
        novelMapper.createNovelSeriesTable();
        novelMapper.createNovelTagsTable();
        novelMapper.createNovelTagsTagIndex();
        novelMapper.createNovelSeriesTagsTable();
        novelMapper.createNovelSeriesTagsTagIndex();
        novelMapper.createNovelCollectionsTable();
        novelMapper.createNovelCollectionsNovelIndex();
        novelMapper.createNovelImagesTable();
        // 幂等迁移：旧库 novels 表补 cover_ext 列；列已存在抛异常吞掉
        try { novelMapper.addCoverExtColumn(); } catch (Exception ignored) {}
        try { novelMapper.addReadingTimeSecondsColumn(); } catch (Exception ignored) {}
        // 幂等迁移：novel_series 表补 description/cover_ext/cover_folder 列；列已存在抛异常吞掉
        try { novelMapper.addNovelSeriesDescriptionColumn(); } catch (Exception ignored) {}
        try { novelMapper.addNovelSeriesCoverExtColumn(); } catch (Exception ignored) {}
        try { novelMapper.addNovelSeriesCoverFolderColumn(); } catch (Exception ignored) {}
        novelMapper.migrateNovelTimestampsToMillis();
        novelMapper.migrateNovelCollectionTimestampsToMillis();
        novelMapper.migrateNovelSeriesTimestampsToMillis();
        Long maxTime = novelMapper.findMaxTime();
        if (maxTime != null) {
            lastIssuedTime.set(maxTime);
        }
    }

    public long getUniqueTime() {
        return getUniqueTime(TimestampUtils.nowMillis());
    }

    public long getUniqueTime(long preferredTime) {
        long normalizedPreferred = TimestampUtils.toMillis(preferredTime);
        long base = normalizedPreferred > 0 ? normalizedPreferred : TimestampUtils.nowMillis();
        long candidate;
        while (true) {
            long last = lastIssuedTime.get();
            candidate = Math.max(base, last + 1);
            if (lastIssuedTime.compareAndSet(last, candidate)) {
                break;
            }
        }
        while (novelMapper.countByTime(candidate) > 0) {
            candidate = lastIssuedTime.incrementAndGet();
        }
        return candidate;
    }

    public void insertNovel(long novelId, String title, String folder, int count,
                            String extensions, long time, Integer xRestrict, Boolean isAi,
                            Long authorId, String description,
                            long fileName, Long fileAuthorNameId,
                            Long seriesId, Long seriesOrder,
                            Integer wordCount, Integer textLength, Integer readingTimeSeconds,
                            Integer pageCount, Boolean isOriginal, String xLanguage,
                            String rawContent, String coverExt) {
        novelMapper.insertOrReplace(novelId, title, pathPrefixCodec.encode(stripTrailingSlash(folder)),
                count, extensions, time, xRestrict, isAi, authorId, description,
                fileName, fileAuthorNameId, seriesId, seriesOrder,
                wordCount, textLength, readingTimeSeconds, pageCount, isOriginal, xLanguage, rawContent, coverExt);
    }

    public void updateCoverExt(long novelId, String coverExt) {
        novelMapper.updateCoverExt(novelId, coverExt);
    }

    public boolean hasNovel(long novelId) {
        return novelMapper.countById(novelId) > 0;
    }

    public NovelRecord getNovel(long novelId) {
        return resolveNovel(novelMapper.findById(novelId));
    }

    private NovelRecord resolveNovel(NovelRecord record) {
        if (record == null) return null;
        String resolvedFolder = pathPrefixCodec.resolve(record.folder());
        if (java.util.Objects.equals(resolvedFolder, record.folder())) return record;
        return new NovelRecord(
                record.novelId(),
                record.title(),
                resolvedFolder,
                record.count(),
                record.extensions(),
                record.time(),
                record.xRestrict(),
                record.isAi(),
                record.authorId(),
                record.description(),
                record.fileName(),
                record.fileAuthorNameId(),
                record.seriesId(),
                record.seriesOrder(),
                record.wordCount(),
                record.textLength(),
                record.readingTimeSeconds(),
                record.pageCount(),
                record.isOriginal(),
                record.xLanguage(),
                record.rawContent(),
                record.coverExt()
        );
    }

    private NovelSeries resolveSeries(NovelSeries series) {
        if (series == null) return null;
        String resolvedCover = pathPrefixCodec.resolve(series.coverFolder());
        if (java.util.Objects.equals(resolvedCover, series.coverFolder())) return series;
        return new NovelSeries(
                series.seriesId(),
                series.title(),
                series.authorId(),
                series.updatedTime(),
                series.description(),
                series.coverExt(),
                resolvedCover
        );
    }

    public void deleteNovel(long novelId) {
        novelMapper.deleteNovelTags(novelId);
        novelMapper.deleteAllNovelCollections(novelId);
        novelMapper.deleteNovelImages(novelId);
        novelMapper.deleteById(novelId);
    }

    // ── Embedded images ────────────────────────────────────────────────────────

    public void saveNovelImage(long novelId, String imageId, String ext) {
        if (imageId == null || imageId.isBlank() || ext == null || ext.isBlank()) return;
        novelMapper.insertNovelImage(novelId, imageId, ext);
    }

    public String getNovelImageExt(long novelId, String imageId) {
        if (imageId == null || imageId.isBlank()) return null;
        return novelMapper.findNovelImageExt(novelId, imageId);
    }

    public List<String> getNovelImageIds(long novelId) {
        List<String> ids = novelMapper.findNovelImageIds(novelId);
        return ids == null ? Collections.emptyList() : ids;
    }

    public void clearNovelImages(long novelId) {
        novelMapper.deleteNovelImages(novelId);
    }

    public long countNovels() {
        return novelMapper.countAll();
    }

    public List<Long> getAllNovelIdsSortedByTimeDesc() {
        return novelMapper.findAllIdsSortedByTimeDesc();
    }

    public void updateExtensions(long novelId, String extensions) {
        novelMapper.updateExtensions(novelId, extensions);
    }

    public void updateSeriesInfo(long novelId, Long seriesId, Long seriesOrder) {
        novelMapper.updateSeriesInfo(novelId, seriesId, seriesOrder);
    }

    public List<NovelRecord> getNovelsBySeriesId(long seriesId) {
        return novelMapper.findBySeriesId(seriesId).stream()
                .map(this::resolveNovel)
                .toList();
    }

    public List<Long> getNovelIdsMissingSeries() {
        return novelMapper.findIdsMissingSeries();
    }

    public List<Long> getNovelIdsMissingAuthor() {
        return novelMapper.findIdsMissingAuthor();
    }

    public void updateAuthorId(long novelId, long authorId) {
        novelMapper.updateAuthorId(novelId, authorId);
    }

    /**
     * Reuse the shared {@code tags} pool from {@link PixivDatabase} so illustration tags and
     * novel tags share the same name → tag_id mapping.
     */
    public void saveNovelTags(long novelId, List<TagDto> tags) {
        if (tags == null || tags.isEmpty()) return;
        for (TagDto t : tags) {
            if (t == null) continue;
            String name = t.getName();
            if (name == null || name.isBlank()) continue;
            Long tagId = pixivDatabase.upsertTagAndGetId(name, t.getTranslatedName());
            if (tagId != null) {
                novelMapper.insertNovelTag(novelId, tagId);
            }
        }
    }

    public List<TagDto> getNovelTags(long novelId) {
        return novelMapper.findTagsByNovelId(novelId);
    }

    public boolean hasNovelTags(long novelId) {
        return novelMapper.existsTagsForNovel(novelId) != null;
    }

    public void clearNovelTags(long novelId) {
        novelMapper.deleteNovelTags(novelId);
    }

    public List<TagDto> getNovelSeriesTags(long seriesId) {
        if (seriesId <= 0) return Collections.emptyList();
        return novelMapper.findTagsByNovelSeriesId(seriesId);
    }

    public java.util.Map<Long, List<TagDto>> getNovelSeriesTagsBatch(Collection<Long> seriesIds) {
        if (seriesIds == null || seriesIds.isEmpty()) return Collections.emptyMap();
        List<java.util.Map<String, Object>> rows = novelMapper.findTagsByNovelSeriesIds(seriesIds);
        java.util.Map<Long, List<TagDto>> out = new java.util.LinkedHashMap<>();
        for (java.util.Map<String, Object> row : rows) {
            Long sid = ((Number) row.get("seriesId")).longValue();
            Number tagIdNum = (Number) row.get("tagId");
            TagDto tag = new TagDto(
                    (String) row.get("name"),
                    (String) row.get("translatedName"));
            tag.setTagId(tagIdNum == null ? null : tagIdNum.longValue());
            out.computeIfAbsent(sid, k -> new java.util.ArrayList<>()).add(tag);
        }
        return out;
    }

    /**
     * Reuse the shared {@code tags} pool so series tags and novel tags share the same name → tag_id mapping.
     */
    public void saveNovelSeriesTags(long seriesId, List<TagDto> tags) {
        if (seriesId <= 0 || tags == null || tags.isEmpty()) return;
        for (TagDto t : tags) {
            if (t == null) continue;
            String name = t.getName();
            if (name == null || name.isBlank()) continue;
            Long tagId = pixivDatabase.upsertTagAndGetId(name, t.getTranslatedName());
            if (tagId != null) {
                novelMapper.insertNovelSeriesTag(seriesId, tagId);
            }
        }
    }

    public void clearNovelSeriesTags(long seriesId) {
        if (seriesId <= 0) return;
        novelMapper.deleteNovelSeriesTags(seriesId);
    }

    // ── Series ─────────────────────────────────────────────────────────────────

    public NovelSeries getSeries(long seriesId) {
        return resolveSeries(novelMapper.findSeriesById(seriesId));
    }

    public List<NovelSeries> getAllSeries() {
        return novelMapper.findAllSeries().stream().map(this::resolveSeries).toList();
    }

    public List<NovelSeries> getSeriesByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return Collections.emptyList();
        return novelMapper.findSeriesByIds(ids).stream().map(this::resolveSeries).toList();
    }

    public void updateSeriesMetadata(long seriesId, String description, String coverExt, String coverFolder) {
        if (seriesId <= 0) return;
        novelMapper.updateNovelSeriesMetadata(seriesId, description, coverExt,
                pathPrefixCodec.encode(stripTrailingSlash(coverFolder)));
    }

    public void observeSeries(long seriesId, String title, Long authorId) {
        if (seriesId <= 0) return;
        long now = TimestampUtils.nowMillis();
        String safeTitle = (title == null || title.isBlank()) ? String.valueOf(seriesId) : title.trim();
        int inserted = novelMapper.insertSeriesIfAbsent(seriesId, safeTitle, authorId, now);
        if (inserted > 0) return;

        NovelSeries existing = novelMapper.findSeriesById(seriesId);
        if (existing == null) return;
        String desiredTitle = (title != null && !title.isBlank()) ? title.trim() : existing.title();
        Long desiredAuthorId = (authorId != null && authorId > 0) ? authorId : existing.authorId();
        if (!desiredTitle.equals(existing.title())
                || (desiredAuthorId != null && !desiredAuthorId.equals(existing.authorId()))) {
            novelMapper.updateSeries(seriesId, desiredTitle, desiredAuthorId, now);
        }
    }

    // ── Collections ────────────────────────────────────────────────────────────

    public boolean addToCollection(long collectionId, long novelId) {
        return novelMapper.insertNovelCollection(collectionId, novelId, TimestampUtils.nowMillis()) > 0;
    }

    public boolean removeFromCollection(long collectionId, long novelId) {
        return novelMapper.deleteNovelCollection(collectionId, novelId) > 0;
    }

    public List<Long> getCollectionIdsForNovel(long novelId) {
        return novelMapper.findCollectionIdsByNovelId(novelId);
    }

    public List<java.util.Map<String, Object>> findCollectionLinksByNovels(java.util.Collection<Long> novelIds) {
        return novelMapper.findCollectionLinksByNovels(novelIds);
    }

    public List<Long> getNovelIdsInCollection(long collectionId) {
        return novelMapper.findNovelIdsByCollectionId(collectionId);
    }

    public long countNovelsInCollection(long collectionId) {
        return novelMapper.countNovelsByCollectionId(collectionId);
    }

    // ── Aggregations ───────────────────────────────────────────────────────────

    public long countAuthorsWithNovels(String search) {
        return novelMapper.countAuthorsWithNovels(search);
    }

    public List<NovelAuthorSummary> findAuthorsWithNovels(String search, String sort, int limit, int offset) {
        return novelMapper.findAuthorsWithNovels(search, sort, limit, offset);
    }

    public long countSeriesWithNovels(String search) {
        return novelMapper.countSeriesWithNovels(search);
    }

    public List<NovelSeriesSummary> findSeriesWithNovels(String search, String sort, int limit, int offset) {
        return novelMapper.findSeriesWithNovels(search, sort, limit, offset);
    }

    public List<NovelTagOption> findTagsForNovels(String search, int limit) {
        return novelMapper.findTagsForNovels(search, limit);
    }

    private static String stripTrailingSlash(String path) {
        return path == null ? null : path.replaceAll("[/\\\\]+$", "");
    }
}
