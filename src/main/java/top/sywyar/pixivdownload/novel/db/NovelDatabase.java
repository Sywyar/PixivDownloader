package top.sywyar.pixivdownload.novel.db;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
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
        novelMapper.createNovelFtsTable();
        // 回填尚未建索引的正文（辅助数据，失败不应阻断启动）
        try { novelMapper.backfillNovelFts(); } catch (Exception e) {
            log.warn("Failed to backfill novel full-text index: {}", e.getMessage());
        }
        // 幂等迁移：旧库 novels 表补 cover_ext 列；列已存在抛异常吞掉
        addColumnIfMissing(novelMapper::addCoverExtColumn);
        addColumnIfMissing(novelMapper::addReadingTimeSecondsColumn);
        // 幂等迁移：novel_series 表补 description/cover_ext/cover_folder 列；列已存在抛异常吞掉
        addColumnIfMissing(novelMapper::addNovelSeriesDescriptionColumn);
        addColumnIfMissing(novelMapper::addNovelSeriesCoverExtColumn);
        addColumnIfMissing(novelMapper::addNovelSeriesCoverFolderColumn);
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

    private void addColumnIfMissing(Runnable addColumn) {
        try { addColumn.run(); } catch (Exception e) {
            String msg = String.valueOf(e.getMessage());
            if (!msg.toLowerCase().contains("duplicate column")) {
                log.warn("Unexpected error adding column: {}", msg, e);
            }
        }
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

    @Transactional
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
        syncNovelFts(novelId, rawContent);
    }

    /** 重建单本小说的全文索引行；正文索引是辅助数据，失败仅记日志、不影响下载落库。 */
    private void syncNovelFts(long novelId, String rawContent) {
        try {
            novelMapper.deleteNovelFts(novelId);
            novelMapper.insertNovelFts(novelId, rawContent == null ? "" : rawContent);
        } catch (Exception e) {
            log.warn("Failed to update novel full-text index for {}: {}", novelId, e.getMessage());
        }
    }

    /**
     * 正文全文检索，返回命中的 novel_id 集合。trigram 索引要求查询串至少 3 个字符，
     * 更短的关键词回退到 {@code raw_content} 的 LIKE 子串扫描。
     */
    public java.util.Set<Long> searchNovelContentIds(String term) {
        if (term == null) return java.util.Collections.emptySet();
        String trimmed = term.trim();
        if (trimmed.isEmpty()) return java.util.Collections.emptySet();
        try {
            List<Long> ids;
            if (trimmed.codePointCount(0, trimmed.length()) < 3) {
                ids = novelMapper.findNovelIdsByContentLike("%" + escapeLikePattern(trimmed) + "%");
            } else {
                String phrase = "\"" + trimmed.replace("\"", "\"\"") + "\"";
                ids = novelMapper.searchNovelFtsIds(phrase);
            }
            return new java.util.HashSet<>(ids);
        } catch (Exception e) {
            // 全文检索是辅助能力，查询异常时退化为"无匹配"，不让画廊列表请求 500
            log.warn("Novel full-text search failed for term '{}': {}", trimmed, e.getMessage());
            return java.util.Collections.emptySet();
        }
    }

    private static String escapeLikePattern(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '\\' || ch == '%' || ch == '_') {
                out.append('\\');
            }
            out.append(ch);
        }
        return out.toString();
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

    @Transactional
    public void deleteNovel(long novelId) {
        novelMapper.deleteNovelTags(novelId);
        novelMapper.deleteAllNovelCollections(novelId);
        novelMapper.deleteNovelImages(novelId);
        novelMapper.deleteById(novelId);
        try { novelMapper.deleteNovelFts(novelId); } catch (Exception e) {
            log.warn("Failed to remove novel {} from full-text index: {}", novelId, e.getMessage());
        }
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

    /** Returns the subset of the given novel ids that have already been downloaded. */
    public List<Long> getExistingNovelIds(java.util.Collection<Long> novelIds) {
        if (novelIds == null || novelIds.isEmpty()) {
            return List.of();
        }
        return novelMapper.findExistingNovelIds(novelIds);
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
    @Transactional
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
    @Transactional
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

    @Transactional
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
