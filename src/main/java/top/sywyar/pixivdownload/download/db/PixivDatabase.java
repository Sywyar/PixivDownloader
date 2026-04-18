package top.sywyar.pixivdownload.download.db;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.List;

@Slf4j
@Repository
@RequiredArgsConstructor
public class PixivDatabase {

    private final PixivMapper pixivMapper;

    @PostConstruct
    public void init() {
        pixivMapper.createArtworksTable();
        pixivMapper.createStatisticsTable();
        pixivMapper.initStatistics();
        // 幂等迁移：为无 R18 列的旧库补列，已有数据行该列为 NULL
        try { pixivMapper.addR18Column(); } catch (Exception ignored) {}
        try { pixivMapper.addAuthorIdColumn(); } catch (Exception ignored) {}
        log.info("数据库初始化完成");
    }

    /**
     * 获取一个不与现有记录冲突的唯一时间戳（秒级）
     */
    public synchronized long getUniqueTime() {
        long time = System.currentTimeMillis() / 1000;
        int count;
        do {
            count = pixivMapper.countByTime(time);
            if (count > 0) time++;
        } while (count > 0);
        return time;
    }

    public void insertArtwork(long artworkId, String title, String folder, int count,
                              String extensions, long time, Boolean isR18, Long authorId) {
        pixivMapper.insertOrIgnore(artworkId, title, stripTrailingSlash(folder),
                count, extensions, time, isR18, authorId);
    }

    public void insertArtwork(long artworkId, String title, String folder, int count,
                              String extensions, long time, Boolean isR18) {
        insertArtwork(artworkId, title, folder, count, extensions, time, isR18, null);
    }

    private static String stripTrailingSlash(String path) {
        return path == null ? null : path.replaceAll("[/\\\\]+$", "");
    }

    public ArtworkRecord getArtworkByMoveFolder(String moveFolder) {
        return pixivMapper.findByNormalizedMoveFolder(moveFolder.replaceAll("[/\\\\]+$", ""));
    }

    public void updateArtworkMove(long artworkId, String movePath, long moveTime) {
        pixivMapper.updateMove(artworkId, stripTrailingSlash(movePath), moveTime);
    }

    public void deleteArtwork(long artworkId) {
        pixivMapper.deleteById(artworkId);
    }

    public boolean hasArtwork(long artworkId) {
        return pixivMapper.countById(artworkId) > 0;
    }

    public ArtworkRecord getArtwork(long artworkId) {
        return pixivMapper.findById(artworkId);
    }

    public List<Long> getAllArtworkIds() {
        return pixivMapper.findAllIds();
    }

    public List<Long> getArtworkIdsSortedByTimeDesc() {
        return pixivMapper.findAllIdsSortedByTimeDesc();
    }

    public List<ArtworkRecord> getArtworksOlderThan(long beforeTimeSec) {
        return pixivMapper.findByTimeBefore(beforeTimeSec);
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
