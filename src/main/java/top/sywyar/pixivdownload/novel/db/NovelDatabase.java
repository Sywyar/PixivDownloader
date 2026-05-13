package top.sywyar.pixivdownload.novel.db;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
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
        novelMapper.insertOrReplace(novelId, title, stripTrailingSlash(folder),
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
        return novelMapper.findById(novelId);
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
        return novelMapper.findBySeriesId(seriesId);
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

    // ── Series ─────────────────────────────────────────────────────────────────

    public NovelSeries getSeries(long seriesId) {
        return novelMapper.findSeriesById(seriesId);
    }

    public List<NovelSeries> getAllSeries() {
        return novelMapper.findAllSeries();
    }

    public List<NovelSeries> getSeriesByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return Collections.emptyList();
        return novelMapper.findSeriesByIds(ids);
    }

    public void updateSeriesMetadata(long seriesId, String description, String coverExt, String coverFolder) {
        if (seriesId <= 0) return;
        novelMapper.updateNovelSeriesMetadata(seriesId, description, coverExt, coverFolder);
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
