package top.sywyar.pixivdownload.novel.db;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import top.sywyar.pixivdownload.core.db.DatabaseInitializer;
import top.sywyar.pixivdownload.core.db.PathPrefixCodec;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.core.db.TagDto;
import top.sywyar.pixivdownload.core.metadata.NovelMetadataRepository;
import top.sywyar.pixivdownload.core.metadata.NovelRecord;
import top.sywyar.pixivdownload.core.metadata.NovelSeries;
import top.sywyar.pixivdownload.util.TimestampUtils;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Repository
@RequiredArgsConstructor
public class NovelDatabase {

    private final NovelMapper novelMapper;
    private final PixivDatabase pixivDatabase;
    private final PathPrefixCodec pathPrefixCodec;
    /** 不直接使用：仅表达对 {@link DatabaseInitializer} 的初始化顺序依赖（{@link #init()} 要求表已建好）。 */
    private final DatabaseInitializer databaseInitializer;
    /**
     * 小说行 / 标签 / 系列 / 收藏夹链接的查询面已收编进核心数据层；本类被 novel-core 调用的
     * 同名读方法委托此核心仓库，使查询 SQL 单源、且持久化类无需自持读 SQL。
     */
    private final NovelMetadataRepository novelMetadataRepository;

    /**
     * 进程内已分配但可能尚未持久化的最大 novel time。
     * 与 {@link PixivDatabase#getUniqueTime} 同样的原因 —— 防止并发下载时多个 worker
     * 拿到相同时间戳后被 {@code INSERT OR REPLACE} 互相覆盖导致已写入的行丢失。
     */
    private final AtomicLong lastIssuedTime = new AtomicLong(0);

    /**
     * 非 DDL 初始化：受管表的建表 / 补列 / 索引已统一由 {@link DatabaseInitializer} 执行
     * （含 backfillNovelFts 所依赖的 deleted 列），这里只保留 FTS 虚拟表维护与幂等数据迁移
     * —— {@code novels_fts} 不入受管 schema，其 DDL 留在本类。
     */
    @PostConstruct
    public void init() {
        novelMapper.createNovelFtsTable();
        // 回填尚未建索引的正文（辅助数据，失败不应阻断启动）
        try { novelMapper.backfillNovelFts(); } catch (Exception e) {
            log.warn("Failed to backfill novel full-text index: {}", e.getMessage());
        }
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

    @Transactional
    public void insertNovel(long novelId, String title, String folder, int count,
                            String extensions, long time, Integer xRestrict, Boolean isAi,
                            Long authorId, String description,
                            long fileName, Long fileAuthorNameId,
                            Long seriesId, Long seriesOrder,
                            Integer wordCount, Integer textLength, Integer readingTimeSeconds,
                            Integer pageCount, Boolean isOriginal, String xLanguage,
                            String rawContent, String coverExt) {
        NovelRecord previous = novelMapper.findById(novelId);
        novelMapper.insertOrReplace(novelId, title, pathPrefixCodec.encode(stripTrailingSlash(folder)),
                count, extensions, time, xRestrict, isAi, authorId, description,
                fileName, fileAuthorNameId, seriesId, seriesOrder,
                wordCount, textLength, readingTimeSeconds, pageCount, isOriginal, xLanguage, rawContent, coverExt);
        if (previous != null && !Objects.equals(previous.rawContent(), rawContent)) {
            novelMapper.deleteNarrationScripts(novelId);
        }
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

    public void updateCoverExt(long novelId, String coverExt) {
        novelMapper.updateCoverExt(novelId, coverExt);
    }

    /** 是否存在未被软删除的记录；deleted 行视为不存在（可重新下载）。委托核心仓库。 */
    public boolean hasActiveNovel(long novelId) {
        return novelMetadataRepository.hasActiveNovel(novelId);
    }

    /** 委托核心仓库（行读取已收编进核心数据层，查询 SQL 单源）。 */
    public NovelRecord getNovel(long novelId) {
        return novelMetadataRepository.getNovel(novelId);
    }

    @Transactional
    public void deleteNovel(long novelId) {
        novelMapper.deleteNovelTags(novelId);
        novelMapper.deleteAllNovelCollections(novelId);
        novelMapper.deleteNovelImages(novelId);
        novelMapper.deleteTranslations(novelId);
        novelMapper.deleteNarrationScripts(novelId);
        novelMapper.deleteById(novelId);
        try { novelMapper.deleteNovelFts(novelId); } catch (Exception e) {
            log.warn("Failed to remove novel {} from full-text index: {}", novelId, e.getMessage());
        }
    }

    // ── AI translations ──────────────────────────────────────────────────────────

    /**
     * 保存（覆盖）某本小说在某语言下的 AI 译文。{@code translatedTitle} / {@code translatedDescription}
     * 都可为空——传 {@code null} 表示本次未翻译对应字段，保留 DB 中既有值不变化。这是为了让
     * 「内容已译、标题/简介翻译失败 / 单独触发」时不会被空值抹掉。
     */
    public void saveTranslation(long novelId, String langCode, String rawContent,
                                String translatedTitle, String translatedDescription) {
        if (langCode == null || langCode.isBlank()) return;
        String lang = langCode.trim();
        String content = rawContent == null ? "" : rawContent;
        String previousContent = novelMapper.findTranslationContent(novelId, lang);
        long now = TimestampUtils.nowMillis();
        String titleToWrite = (translatedTitle != null && !translatedTitle.isBlank())
                ? translatedTitle.trim()
                : novelMapper.findTranslationTitle(novelId, lang);
        String descriptionToWrite = (translatedDescription != null && !translatedDescription.isBlank())
                ? translatedDescription.trim()
                : novelMapper.findTranslationDescription(novelId, lang);
        novelMapper.insertOrReplaceTranslation(novelId, lang, content, titleToWrite, descriptionToWrite, now);
        if (!Objects.equals(previousContent, content)) {
            novelMapper.deleteNarrationScript(novelId, lang);
        }
    }

    /** 单独覆盖某本小说某语言的译文标题（不动正文），用于内容已译但标题刚翻译成功的回填。 */
    public void saveTranslationTitle(long novelId, String langCode, String translatedTitle) {
        if (langCode == null || langCode.isBlank()) return;
        if (translatedTitle == null || translatedTitle.isBlank()) return;
        novelMapper.updateTranslationTitle(novelId, langCode.trim(), translatedTitle.trim());
    }

    /**
     * 仅写入译文标题 / 译文简介，保留正文（无既有行时以空 {@code raw_content} 落行）。供「仅翻译标题 / 仅翻译简介」
     * 流程使用：{@link #saveTranslation} 会用调用方传入的正文覆写旧译文正文，本方法则先回填既有正文再走同一 upsert，
     * 避免空正文抹掉已经翻译完成的章节。
     */
    public void saveTranslationMetadata(long novelId, String langCode,
                                        String translatedTitle, String translatedDescription) {
        if (langCode == null || langCode.isBlank()) return;
        if ((translatedTitle == null || translatedTitle.isBlank())
                && (translatedDescription == null || translatedDescription.isBlank())) {
            return;
        }
        String lang = langCode.trim();
        String existingContent = novelMapper.findTranslationContent(novelId, lang);
        String content = existingContent == null ? "" : existingContent;
        saveTranslation(novelId, lang, content, translatedTitle, translatedDescription);
    }

    /** 读取某本小说某语言的译文 markup；不存在返回 {@code null}。 */
    public String getTranslationContent(long novelId, String langCode) {
        if (langCode == null || langCode.isBlank()) return null;
        return novelMapper.findTranslationContent(novelId, langCode.trim());
    }

    /** 读取某本小说某语言的译文标题；不存在 / 未翻译标题返回 {@code null}。 */
    public String getTranslationTitle(long novelId, String langCode) {
        if (langCode == null || langCode.isBlank()) return null;
        return novelMapper.findTranslationTitle(novelId, langCode.trim());
    }

    /** 读取某本小说某语言的译文简介；不存在 / 未翻译简介返回 {@code null}。 */
    public String getTranslationDescription(long novelId, String langCode) {
        if (langCode == null || langCode.isBlank()) return null;
        return novelMapper.findTranslationDescription(novelId, langCode.trim());
    }

    public boolean hasTranslation(long novelId, String langCode) {
        if (langCode == null || langCode.isBlank()) return false;
        return novelMapper.countTranslation(novelId, langCode.trim()) > 0;
    }

    /** 某本小说已有译文的全部语言代码。 */
    public List<String> getTranslationLangs(long novelId) {
        List<String> langs = novelMapper.findTranslationLangs(novelId);
        return langs == null ? Collections.emptyList() : langs;
    }

    /** 某系列下「至少有一章存在译文」的全部语言代码（变体合订需要逐一重生）。 */
    public List<String> getSeriesTranslatedLangs(long seriesId) {
        if (seriesId <= 0) return Collections.emptyList();
        List<String> langs = novelMapper.findSeriesTranslatedLangs(seriesId);
        return langs == null ? Collections.emptyList() : langs;
    }

    /**
     * 保存（覆盖）某系列在某语言下的系列名 + 系列简介翻译。{@code translatedDescription} 可为空——传 {@code null}
     * 表示本次未翻译系列简介（保留 DB 中既有简介值不变），与 saveTranslation 的标题/简介 nullability 语义一致。
     */
    public void saveSeriesTitleTranslation(long seriesId, String langCode,
                                           String translatedTitle, String translatedDescription) {
        if (seriesId <= 0 || langCode == null || langCode.isBlank()) return;
        if (translatedTitle == null || translatedTitle.isBlank()) return;
        String lang = langCode.trim();
        String descriptionToWrite = (translatedDescription != null && !translatedDescription.isBlank())
                ? translatedDescription.trim()
                : novelMapper.findSeriesDescriptionTranslation(seriesId, lang);
        novelMapper.insertOrReplaceSeriesTitleTranslation(
                seriesId, lang, translatedTitle.trim(), descriptionToWrite, TimestampUtils.nowMillis());
    }

    /** 读取某系列某语言的系列名翻译；不存在返回 {@code null}。 */
    public String getSeriesTitleTranslation(long seriesId, String langCode) {
        if (seriesId <= 0 || langCode == null || langCode.isBlank()) return null;
        return novelMapper.findSeriesTitleTranslation(seriesId, langCode.trim());
    }

    /** 读取某系列某语言的系列简介翻译；不存在 / 未翻译简介返回 {@code null}。 */
    public String getSeriesDescriptionTranslation(long seriesId, String langCode) {
        if (seriesId <= 0 || langCode == null || langCode.isBlank()) return null;
        return novelMapper.findSeriesDescriptionTranslation(seriesId, langCode.trim());
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

    /** 委托核心仓库（{@code series_order ASC, time ASC}，软删除行过滤）。 */
    public List<NovelRecord> getNovelsBySeriesId(long seriesId) {
        return novelMetadataRepository.getNovelsBySeriesId(seriesId);
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

    public boolean hasNovelTags(long novelId) {
        return novelMapper.existsTagsForNovel(novelId) != null;
    }

    public void clearNovelTags(long novelId) {
        novelMapper.deleteNovelTags(novelId);
    }

    /** 委托核心仓库（系列标签读取已收编进核心数据层，查询 SQL 单源）。 */
    public List<TagDto> getNovelSeriesTags(long seriesId) {
        if (seriesId <= 0) return Collections.emptyList();
        return novelMetadataRepository.getNovelSeriesTags(seriesId);
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

    /** 委托核心仓库（系列行读取已收编进核心数据层）。 */
    public NovelSeries getSeries(long seriesId) {
        return novelMetadataRepository.getSeries(seriesId);
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

    public long countNovelsInCollection(long collectionId) {
        return novelMapper.countNovelsByCollectionId(collectionId);
    }

    private static String stripTrailingSlash(String path) {
        return path == null ? null : path.replaceAll("[/\\\\]+$", "");
    }
}
