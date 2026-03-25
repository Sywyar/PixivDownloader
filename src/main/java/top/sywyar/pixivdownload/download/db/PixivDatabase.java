package top.sywyar.pixivdownload.download.db;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class PixivDatabase {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void init() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS artworks (
                    artwork_id INTEGER PRIMARY KEY,
                    title TEXT NOT NULL,
                    folder TEXT NOT NULL,
                    count INTEGER NOT NULL,
                    extensions TEXT NOT NULL,
                    time INTEGER NOT NULL UNIQUE,
                    moved INTEGER DEFAULT 0,
                    move_folder TEXT,
                    move_time INTEGER
                )
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS statistics (
                    id INTEGER PRIMARY KEY CHECK (id = 1),
                    total_artworks INTEGER DEFAULT 0,
                    total_images INTEGER DEFAULT 0,
                    total_moved INTEGER DEFAULT 0
                )
                """);

        jdbcTemplate.execute(
                "INSERT OR IGNORE INTO statistics (id, total_artworks, total_images, total_moved) VALUES (1, 0, 0, 0)"
        );

        log.info("数据库初始化完成");
    }

    /**
     * 获取一个不与现有记录冲突的唯一时间戳（秒级）
     */
    public synchronized long getUniqueTime() {
        long time = System.currentTimeMillis() / 1000;
        Integer count;
        do {
            count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM artworks WHERE time = ?", Integer.class, time
            );
            if (count != null && count > 0) time++;
        } while (count != null && count > 0);
        return time;
    }

    public void insertArtwork(long artworkId, String title, String folder, int count, String extensions, long time) {
        jdbcTemplate.update(
                "INSERT OR IGNORE INTO artworks (artwork_id, title, folder, count, extensions, time) VALUES (?, ?, ?, ?, ?, ?)",
                artworkId, title, folder, count, extensions, time
        );
    }

    public void updateArtworkMove(long artworkId, String movePath, long moveTime) {
        jdbcTemplate.update(
                "UPDATE artworks SET moved = 1, move_folder = ?, move_time = ? WHERE artwork_id = ?",
                movePath, moveTime, artworkId
        );
    }

    public void deleteArtwork(long artworkId) {
        jdbcTemplate.update("DELETE FROM artworks WHERE artwork_id = ?", artworkId);
    }

    public boolean hasArtwork(long artworkId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM artworks WHERE artwork_id = ?", Integer.class, artworkId
        );
        return count != null && count > 0;
    }

    public ArtworkRecord getArtwork(long artworkId) {
        List<ArtworkRecord> results = jdbcTemplate.query(
                "SELECT artwork_id, title, folder, count, extensions, time, moved, move_folder, move_time FROM artworks WHERE artwork_id = ?",
                (rs, rowNum) -> {
                    boolean moved = rs.getInt("moved") == 1;
                    long moveTimeVal = rs.getLong("move_time");
                    Long moveTime = rs.wasNull() ? null : moveTimeVal;
                    return new ArtworkRecord(
                            rs.getLong("artwork_id"),
                            rs.getString("title"),
                            rs.getString("folder"),
                            rs.getInt("count"),
                            rs.getString("extensions"),
                            rs.getLong("time"),
                            moved,
                            rs.getString("move_folder"),
                            moveTime
                    );
                },
                artworkId
        );
        return results.isEmpty() ? null : results.get(0);
    }

    public List<Long> getAllArtworkIds() {
        return jdbcTemplate.queryForList("SELECT artwork_id FROM artworks", Long.class);
    }

    public List<Long> getArtworkIdsSortedByTimeDesc() {
        return jdbcTemplate.queryForList(
                "SELECT artwork_id FROM artworks ORDER BY time DESC", Long.class
        );
    }

    public long countArtworks() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM artworks", Long.class);
        return count != null ? count : 0;
    }

    public void incrementStats(int imageCount) {
        jdbcTemplate.update(
                "UPDATE statistics SET total_artworks = total_artworks + 1, total_images = total_images + ? WHERE id = 1",
                imageCount
        );
    }

    public void incrementMoved() {
        jdbcTemplate.update("UPDATE statistics SET total_moved = total_moved + 1 WHERE id = 1");
    }

    public int[] getStats() {
        return jdbcTemplate.queryForObject(
                "SELECT total_artworks, total_images, total_moved FROM statistics WHERE id = 1",
                (rs, rowNum) -> new int[]{
                        rs.getInt("total_artworks"),
                        rs.getInt("total_images"),
                        rs.getInt("total_moved")
                }
        );
    }

    public void setStats(int totalArtworks, int totalImages, int totalMoved) {
        jdbcTemplate.update(
                "UPDATE statistics SET total_artworks = ?, total_images = ?, total_moved = ? WHERE id = 1",
                totalArtworks, totalImages, totalMoved
        );
    }
}
