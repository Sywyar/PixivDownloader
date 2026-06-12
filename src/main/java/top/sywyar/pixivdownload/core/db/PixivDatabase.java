package top.sywyar.pixivdownload.core.db;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.util.TimestampUtils;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Repository
@RequiredArgsConstructor
public class PixivDatabase {

    private final PixivMapper pixivMapper;
    private final AppMessages messages;
    private final PathPrefixCodec pathPrefixCodec;
    /** 不直接使用：仅表达对 {@link DatabaseInitializer} 的初始化顺序依赖（{@link #init()} 要求表已建好）。 */
    private final DatabaseInitializer databaseInitializer;

    /**
     * 进程内已分配但可能尚未持久化的最大时间戳。
     * 用于在并发下载时避免多个 worker 同时拿到相同的 unique time —— DB 行尚未写入前，
     * 仅依赖 {@code countByTime} 的旧实现会让所有并发调用拿到同一秒，
     * 后续 {@code INSERT OR IGNORE} 静默丢弃冲突行，导致下载成功但记录丢失。
     */
    private final AtomicLong lastIssuedTime = new AtomicLong(0);

    /**
     * 非 DDL 初始化：建表 / 补列 / 索引已统一由 {@link DatabaseInitializer} 执行，
     * 这里只保留种子数据与幂等数据迁移。
     */
    @PostConstruct
    public void init() {
        pixivMapper.ensureDefaultFileNameTemplate(ArtworkFileNameFormatter.DEFAULT_TEMPLATE);
        pixivMapper.initStatistics();
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

    @Transactional
    public void insertArtwork(long artworkId, String title, String folder, int count,
                              String extensions, long time, Integer xRestrict, Boolean isAi, Long authorId,
                              String description, long fileName, Long fileAuthorNameId,
                              Long seriesId, Long seriesOrder) {
        // 软删除残留行的 folder/time 已失效，先清掉再写入全新行，重新下载即复位 deleted 标记；
        // 普通已下载行不受影响（INSERT OR IGNORE 保持原有的不覆盖语义）。
        pixivMapper.deleteIfMarkedDeleted(artworkId);
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

    @Transactional
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

    public Map<Long, String> getFileNameTemplates(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> result = new HashMap<>();
        for (FileNameTemplateRow row : pixivMapper.findFileNameTemplatesByIds(ids)) {
            if (row.getId() != null) {
                result.put(row.getId(), ArtworkFileNameFormatter.normalizeTemplate(row.getTemplate()));
            }
        }
        return result;
    }

    /**
     * 驻留下载时的合规化作者名，返回 ID。优先复用已有记录，不存在时新建。
     * {@code name} 必须已是 {@link ArtworkFileNameFormatter#sanitize sanitize} 后的值。
     */
    @Transactional
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
                record.seriesOrder(),
                record.deleted()
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

    /**
     * 删除作品及其全部直接关联的 DB 留存数据：感知哈希（{@code artwork_image_hashes}）、
     * 标签关联（{@code artwork_tags}）、收藏夹关联（{@code artwork_collections}），最后删主行。
     * 共享池（{@code tags} / {@code authors} / {@code collections} / {@code manga_series}）与聚合
     * 统计（{@code statistics}）按设计不在此清理。磁盘文件由调用方（如 {@code GalleryService}）负责删除。
     */
    public void deleteArtwork(long artworkId) {
        pixivMapper.deleteImageHashesByArtwork(artworkId);
        pixivMapper.deleteArtworkTags(artworkId);
        pixivMapper.deleteArtworkCollections(artworkId);
        pixivMapper.deleteById(artworkId);
    }

    /**
     * 软删除：派生/关联数据（感知哈希、标签关联、收藏夹关联）照 {@link #deleteArtwork} 清理，
     * 但主行保留并置 {@code deleted = 1}，使下载判重能识别「已下载过，但被删除」。
     * 重新下载成功后由 {@link #insertArtwork} 路径替换主行、标记自动复位。
     */
    @Transactional
    public void markArtworkDeleted(long artworkId) {
        pixivMapper.deleteImageHashesByArtwork(artworkId);
        pixivMapper.deleteArtworkTags(artworkId);
        pixivMapper.deleteArtworkCollections(artworkId);
        pixivMapper.markDeletedById(artworkId);
    }

    /**
     * 仅在对应字段当前为 NULL / 空字符串时填入新值，不覆盖已有数据。
     * 用于 pixiv-batch 的两阶段恢复：先用磁盘文件写一条裸记录（{@link #insertArtwork} 时 meta 为空），
     * 再用前端拉到的 Pixiv 元数据补齐。
     */
    public void fillArtworkMetadataIfMissing(long artworkId, String title, Integer xRestrict,
                                             Boolean isAi, Long authorId, String description) {
        pixivMapper.updateMetadataIfMissing(artworkId, title, xRestrict, isAi, authorId, description);
    }

    public boolean hasArtwork(long artworkId) {
        return pixivMapper.countById(artworkId) > 0;
    }

    /** 是否存在未被软删除的记录；deleted 行视为不存在（可重新下载）。 */
    public boolean hasActiveArtwork(long artworkId) {
        return pixivMapper.countActiveById(artworkId) > 0;
    }

    /** 是否为软删除残留行（已下载过，但被画廊删除）。 */
    public boolean isArtworkDeleted(long artworkId) {
        return pixivMapper.countDeletedById(artworkId) > 0;
    }

    public ArtworkRecord getArtwork(long artworkId) {
        return resolveRecord(pixivMapper.findById(artworkId));
    }

    public List<ArtworkRecord> getArtworks(Collection<Long> artworkIds) {
        if (artworkIds == null || artworkIds.isEmpty()) {
            return List.of();
        }
        return pixivMapper.findByIds(artworkIds).stream()
                .map(this::resolveRecord)
                .toList();
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
    @Transactional
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
    @Transactional
    public Long upsertTagAndGetId(String name, String translatedName) {
        if (name == null || name.isBlank()) return null;
        String translated = (translatedName != null && translatedName.isBlank()) ? null : translatedName;
        pixivMapper.upsertTag(name, translated);
        return pixivMapper.findTagIdByName(name);
    }

    public List<TagDto> getArtworkTags(long artworkId) {
        return pixivMapper.findTagsByArtworkId(artworkId);
    }

    public Map<Long, List<TagDto>> getArtworkTags(Collection<Long> artworkIds) {
        if (artworkIds == null || artworkIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<TagDto>> result = new HashMap<>();
        for (ArtworkTagRow row : pixivMapper.findTagsByArtworkIds(artworkIds)) {
            if (row.getArtworkId() == null) continue;
            result.computeIfAbsent(row.getArtworkId(), ignored -> new java.util.ArrayList<>())
                    .add(new TagDto(row.getTagId(), row.getName(), row.getTranslatedName()));
        }
        return result;
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
