package top.sywyar.pixivdownload.download.db;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import top.sywyar.pixivdownload.download.ArtworkFileNameFormatter;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.util.TimestampUtils;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Repository
@RequiredArgsConstructor
public class PixivDatabase {

    private final PixivMapper pixivMapper;
    private final AppMessages messages;
    private final PathPrefixCodec pathPrefixCodec;

    /**
     * 进程内已分配但可能尚未持久化的最大时间戳。
     * 用于在并发下载时避免多个 worker 同时拿到相同的 unique time —— DB 行尚未写入前，
     * 仅依赖 {@code countByTime} 的旧实现会让所有并发调用拿到同一秒，
     * 后续 {@code INSERT OR IGNORE} 静默丢弃冲突行，导致下载成功但记录丢失。
     */
    private final AtomicLong lastIssuedTime = new AtomicLong(0);

    @PostConstruct
    public void init() {
        pixivMapper.createFileAuthorNamesTable();
        pixivMapper.createFileNameTemplatesTable();
        pixivMapper.ensureDefaultFileNameTemplate(ArtworkFileNameFormatter.DEFAULT_TEMPLATE);
        pixivMapper.createArtworksTable();
        pixivMapper.createStatisticsTable();
        pixivMapper.initStatistics();
        pixivMapper.createTagsTable();
        pixivMapper.createArtworkTagsTable();
        pixivMapper.createArtworkTagsTagIndex();
        // 幂等迁移：为无 R18 列的旧库补列，已有数据行该列为 NULL
        try { pixivMapper.addR18Column(); } catch (Exception ignored) {}
        try { pixivMapper.addIsAiColumn(); } catch (Exception ignored) {}
        try { pixivMapper.addAuthorIdColumn(); } catch (Exception ignored) {}
        try { pixivMapper.addDescriptionColumn(); } catch (Exception ignored) {}
        try { pixivMapper.addFileNameColumn(); } catch (Exception ignored) {}
        try { pixivMapper.addFileAuthorNameIdColumn(); } catch (Exception ignored) {}
        try { pixivMapper.addSeriesIdColumn(); } catch (Exception ignored) {}
        try { pixivMapper.addSeriesOrderColumn(); } catch (Exception ignored) {}
        pixivMapper.migrateArtworkTimestampsToMillis();
        pixivMapper.migrateArtworkMoveTimestampsToMillis();
        Long maxTime = pixivMapper.findMaxTime();
        if (maxTime != null) {
            lastIssuedTime.set(maxTime);
        }
        log.info(messages.getForLog("download.db.log.initialized"));
    }

    /**
     * 获取一个不与现有记录冲突的唯一时间戳（毫秒级）
     */
    public long getUniqueTime() {
        return getUniqueTime(TimestampUtils.nowMillis());
    }

    public long getUniqueTime(long preferredTime) {
        long normalizedPreferred = TimestampUtils.toMillis(preferredTime);
        long base = normalizedPreferred > 0 ? normalizedPreferred : TimestampUtils.nowMillis();
        long candidate;
        // CAS 推进进程内计数器：保证两个并发调用永远不会拿到相同时间。
        while (true) {
            long last = lastIssuedTime.get();
            candidate = Math.max(base, last + 1);
            if (lastIssuedTime.compareAndSet(last, candidate)) {
                break;
            }
        }
        // 兜底：若计数器初始化后仍有 DB 行（如外部工具写入）使用了相同时间，向后跳过。
        while (pixivMapper.countByTime(candidate) > 0) {
            candidate = lastIssuedTime.incrementAndGet();
        }
        return candidate;
    }

    public void insertArtwork(long artworkId, String title, String folder, int count,
                              String extensions, long time, Integer xRestrict, Boolean isAi, Long authorId,
                              String description, long fileName, Long fileAuthorNameId,
                              Long seriesId, Long seriesOrder) {
        pixivMapper.insertOrIgnore(artworkId, title, encodePath(folder),
                count, extensions, time, xRestrict, isAi, authorId, description, fileName, fileAuthorNameId,
                seriesId, seriesOrder);
    }

    public void insertArtwork(long artworkId, String title, String folder, int count,
                              String extensions, long time, Integer xRestrict, Boolean isAi, Long authorId,
                              String description, long fileName, Long fileAuthorNameId) {
        insertArtwork(artworkId, title, folder, count, extensions, time, xRestrict, isAi, authorId,
                description, fileName, fileAuthorNameId, null, null);
    }

    public void insertArtwork(long artworkId, String title, String folder, int count,
                              String extensions, long time, Integer xRestrict, Boolean isAi, Long authorId,
                              String description, long fileName) {
        insertArtwork(artworkId, title, folder, count, extensions, time, xRestrict, isAi,
                authorId, description, fileName, null);
    }

    public void insertArtwork(long artworkId, String title, String folder, int count,
                              String extensions, long time, Integer xRestrict, Boolean isAi, Long authorId,
                              String description) {
        insertArtwork(artworkId, title, folder, count, extensions, time, xRestrict, isAi,
                authorId, description, ArtworkFileNameFormatter.DEFAULT_TEMPLATE_ID);
    }

    public void insertArtwork(long artworkId, String title, String folder, int count,
                              String extensions, long time, Integer xRestrict, Long authorId,
                              String description) {
        insertArtwork(artworkId, title, folder, count, extensions, time, xRestrict, null, authorId, description);
    }

    public void insertArtwork(long artworkId, String title, String folder, int count,
                              String extensions, long time, Integer xRestrict, Long authorId) {
        insertArtwork(artworkId, title, folder, count, extensions, time, xRestrict, authorId, null);
    }

    public void insertArtwork(long artworkId, String title, String folder, int count,
                              String extensions, long time, Integer xRestrict) {
        insertArtwork(artworkId, title, folder, count, extensions, time, xRestrict, null, null);
    }

    public long getOrCreateFileNameTemplateId(String template) {
        String normalized = ArtworkFileNameFormatter.normalizeTemplate(template);
        pixivMapper.insertFileNameTemplateIfAbsent(normalized);
        Long id = pixivMapper.findFileNameTemplateId(normalized);
        return id == null ? ArtworkFileNameFormatter.DEFAULT_TEMPLATE_ID : id;
    }

    public String getFileNameTemplate(long id) {
        String template = pixivMapper.findFileNameTemplateById(id);
        return ArtworkFileNameFormatter.normalizeTemplate(template);
    }

    /**
     * 驻留下载时的合规化作者名，返回 ID。优先复用已有记录，不存在时新建。
     * {@code name} 必须已是 {@link ArtworkFileNameFormatter#sanitize sanitize} 后的值。
     */
    public long getOrCreateFileAuthorNameId(String name) {
        if (name == null || name.isEmpty()) {
            return 0L;
        }
        pixivMapper.insertFileAuthorNameIfAbsent(name);
        Long id = pixivMapper.findFileAuthorNameId(name);
        return id == null ? 0L : id;
    }

    /**
     * 按 ID 查询合规化作者名。
     */
    public String getFileAuthorName(long id) {
        return pixivMapper.findFileAuthorNameById(id);
    }

    private static String stripTrailingSlash(String path) {
        return path == null ? null : path.replaceAll("[/\\\\]+$", "");
    }

    private String encodePath(String absolutePath) {
        return pathPrefixCodec.encode(stripTrailingSlash(absolutePath));
    }

    private ArtworkRecord resolveRecord(ArtworkRecord record) {
        if (record == null) return null;
        String resolvedFolder = pathPrefixCodec.resolve(record.folder());
        String resolvedMove = pathPrefixCodec.resolve(record.moveFolder());
        if (java.util.Objects.equals(resolvedFolder, record.folder())
                && java.util.Objects.equals(resolvedMove, record.moveFolder())) {
            return record;
        }
        return new ArtworkRecord(
                record.artworkId(),
                record.title(),
                resolvedFolder,
                record.count(),
                record.extensions(),
                record.time(),
                record.moved(),
                resolvedMove,
                record.moveTime(),
                record.xRestrict(),
                record.isAi(),
                record.authorId(),
                record.description(),
                record.fileName(),
                record.fileAuthorNameId(),
                record.seriesId(),
                record.seriesOrder()
        );
    }

    public ArtworkRecord getArtworkByMoveFolder(String moveFolder) {
        if (moveFolder == null) return null;
        String stripped = stripTrailingSlash(moveFolder);
        ArtworkRecord direct = pixivMapper.findByNormalizedMoveFolder(stripped);
        if (direct == null) {
            String encoded = pathPrefixCodec.encode(stripped);
            if (!java.util.Objects.equals(encoded, stripped)) {
                direct = pixivMapper.findByNormalizedMoveFolder(encoded);
            }
        }
        return resolveRecord(direct);
    }

    public void updateArtworkMove(long artworkId, String movePath, long moveTime) {
        updateArtworkMove(artworkId, movePath, moveTime, null);
    }

    /**
     * @param classifierTargetFolder 调用方（如分类工具）已知的"内置目标根目录"。
     *        非空时会被预先注册到 {@code path_prefixes}，确保 movePath 落在该根目录下时
     *        编码出 {@code {N}/<rest>}，避免每个编号子目录各自占一行。
     *        为空时回退到 {@link PathPrefixCodec#encodeOrRegister} —— 没匹配就把
     *        movePath 本身注册成新前缀。
     */
    public void updateArtworkMove(long artworkId, String movePath, long moveTime,
                                  String classifierTargetFolder) {
        String stripped = stripTrailingSlash(movePath);
        String encoded;
        if (stripped == null) {
            encoded = null;
        } else {
            String presetRoot = stripTrailingSlash(classifierTargetFolder);
            if (presetRoot != null && !presetRoot.isEmpty()
                    && !pathPrefixCodec.looksEncoded(presetRoot)) {
                pathPrefixCodec.getOrCreatePrefixId(presetRoot);
            }
            encoded = pathPrefixCodec.encodeOrRegister(stripped);
        }
        pixivMapper.updateMove(artworkId, encoded, moveTime);
    }

    public void deleteArtwork(long artworkId) {
        pixivMapper.deleteById(artworkId);
    }

    public boolean hasArtwork(long artworkId) {
        return pixivMapper.countById(artworkId) > 0;
    }

    public ArtworkRecord getArtwork(long artworkId) {
        return resolveRecord(pixivMapper.findById(artworkId));
    }

    public List<Long> getAllArtworkIds() {
        return pixivMapper.findAllIds();
    }

    public List<Long> getArtworkIdsSortedByTimeDesc() {
        return pixivMapper.findAllIdsSortedByTimeDesc();
    }

    public List<Long> getArtworkIdsSortedByAuthorIdAsc() {
        return pixivMapper.findAllIdsSortedByAuthorIdAsc();
    }

    public List<ArtworkRecord> getArtworksOlderThan(long beforeTimeMillis) {
        return pixivMapper.findByTimeBefore(beforeTimeMillis).stream()
                .map(this::resolveRecord)
                .toList();
    }

    public long countArtworks() {
        return pixivMapper.countAll();
    }

    public List<Long> getArtworkIdsSortedByTimeDescPaged(int offset, int size) {
        return pixivMapper.findIdsSortedByTimeDescPaged(size, offset);
    }

    public List<Long> getArtworkIdsSortedByAuthorIdAscPaged(int offset, int size) {
        return pixivMapper.findIdsSortedByAuthorIdAscPaged(size, offset);
    }

    public void updateAuthorId(long artworkId, long authorId) {
        pixivMapper.updateAuthorId(artworkId, authorId);
    }

    public List<Long> getArtworkIdsMissingAuthor() {
        return pixivMapper.findIdsMissingAuthor();
    }

    public void updateSeriesInfo(long artworkId, Long seriesId, Long seriesOrder) {
        pixivMapper.updateSeriesInfo(artworkId, seriesId, seriesOrder);
    }

    public List<Long> getArtworkIdsMissingSeries() {
        return pixivMapper.findIdsMissingSeries();
    }

    /**
     * 将作品标签持久化到 {@code tags} + {@code artwork_tags} 表。
     * 标签名按 {@code INSERT OR IGNORE} 去重，已存在同名时仅补齐 {@code translated_name}；
     * 作品-标签对同样使用 {@code INSERT OR IGNORE}，重复调用不会产生冗余行。
     */
    public void saveArtworkTags(long artworkId, List<TagDto> tags) {
        if (tags == null || tags.isEmpty()) return;
        for (TagDto t : tags) {
            if (t == null) continue;
            String name = t.getName();
            if (name == null || name.isBlank()) continue;
            Long tagId = upsertTagAndGetId(name, t.getTranslatedName());
            if (tagId != null) {
                pixivMapper.insertArtworkTag(artworkId, tagId);
            }
        }
    }

    /**
     * Upsert a tag (by name) into the shared {@code tags} pool and return its id.
     * Used by both artwork and novel tag persistence so they share the same id space.
     */
    public Long upsertTagAndGetId(String name, String translatedName) {
        if (name == null || name.isBlank()) return null;
        String translated = (translatedName != null && translatedName.isBlank()) ? null : translatedName;
        pixivMapper.upsertTag(name, translated);
        return pixivMapper.findTagIdByName(name);
    }

    public List<TagDto> getArtworkTags(long artworkId) {
        return pixivMapper.findTagsByArtworkId(artworkId);
    }

    public boolean hasArtworkTags(long artworkId) {
        return pixivMapper.existsTagsForArtwork(artworkId) != null;
    }

    public void incrementStats(int imageCount) {
        pixivMapper.incrementStats(imageCount);
    }

    public void incrementMoved() {
        pixivMapper.incrementMoved();
    }

    public int[] getStats() {
        StatisticsData data = pixivMapper.getStats();
        return new int[]{data.totalArtworks(), data.totalImages(), data.totalMoved()};
    }

    public void setStats(int totalArtworks, int totalImages, int totalMoved) {
        pixivMapper.setStats(totalArtworks, totalImages, totalMoved);
    }

}
