package top.sywyar.pixivdownload.migration;

import com.sywyar.superjsonobject.SuperJsonObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.download.db.PixivDatabase;

import java.io.File;
import java.nio.file.Paths;
import java.util.function.Consumer;

/**
 * 将旧版 JSON 文件数据迁移到 SQLite 数据库。
 * <p>
 * 支持迁移的文件：
 * <ul>
 *   <li>download_history.json - 作品下载记录（含移动信息）</li>
 *   <li>statistics.json - 统计数据（totalArtworks / totalImages / totalMoved）</li>
 *   <li>timeArtwork.json - 已集成至 download_history.json 的 time 字段，无需单独迁移</li>
 * </ul>
 * 迁移操作是幂等的：已存在于数据库中的作品会被自动跳过。
 */
@Slf4j
@Component
public class JsonToSqliteMigration {

    @Autowired
    private PixivDatabase pixivDatabase;

    @Value("${download.root-folder:pixiv-download}")
    private String rootFolder;

    public MigrationResult migrate() {
        return migrate(null);
    }

    public MigrationResult migrate(Consumer<String> progressReporter) {
        File historyFile = Paths.get(rootFolder, "download_history.json").toFile();
        File statisticsFile = Paths.get(rootFolder, "statistics.json").toFile();

        log.info("迁移开始，查找文件: {}", historyFile.getAbsolutePath());

        if (!historyFile.exists()) {
            String msg = "download_history.json 不存在，无需迁移";
            log.info(msg);
            report(progressReporter, msg);
            return new MigrationResult(true, 0, 0, msg);
        }

        int migrated = 0;
        int skipped = 0;

        try {
            SuperJsonObject history = new SuperJsonObject(historyFile);
            SuperJsonObject downloaded = history.getOrDefault("downloaded", new SuperJsonObject());
            int total = downloaded.asMap().size();

            report(progressReporter, String.format("共找到 %d 条记录，开始迁移...", total));

            for (var entry : downloaded.asMap().entrySet()) {
                long artworkId;
                try {
                    artworkId = Long.parseLong(entry.getKey());
                } catch (NumberFormatException e) {
                    log.warn("跳过非法 artworkId: {}", entry.getKey());
                    skipped++;
                    continue;
                }

                if (pixivDatabase.hasArtwork(artworkId)) {
                    skipped++;
                } else {
                    SuperJsonObject artwork = downloaded.getAsSuperJsonObject(entry.getKey());
                    String title = artwork.getAsString("title");
                    String folder = artwork.getAsString("folder");
                    int count = artwork.getAsInt("count");
                    String extensions = artwork.getAsString("extensions");
                    long time = artwork.has("time")
                            ? artwork.getAsLong("time")
                            : pixivDatabase.getUniqueTime();

                    pixivDatabase.insertArtwork(artworkId, title, folder, count, extensions, time);

                    if (artwork.has("moved") && artwork.getAsBoolean("moved")) {
                        String moveFolder = artwork.getAsString("moveFolder");
                        long moveTime = artwork.getAsLong("moveTime");
                        pixivDatabase.updateArtworkMove(artworkId, moveFolder, moveTime);
                    }

                    migrated++;
                }

                int done = migrated + skipped;
                if (total > 0 && done % 100 == 0) {
                    int percent = done * 100 / total;
                    report(progressReporter, String.format("进度: %d/%d (%d%%)，已迁移 %d 条，跳过 %d 条",
                            done, total, percent, migrated, skipped));
                }
            }

            // 迁移统计数据（仅在数据库统计全为 0 时写入，避免覆盖新数据）
            if (statisticsFile.exists()) {
                int[] currentStats = pixivDatabase.getStats();
                if (currentStats[0] == 0 && currentStats[1] == 0 && currentStats[2] == 0) {
                    SuperJsonObject stats = new SuperJsonObject(statisticsFile);
                    int totalArtworks = stats.has("totalArtworks") ? stats.getAsInt("totalArtworks") : 0;
                    int totalImages = stats.has("totalImages") ? stats.getAsInt("totalImages") : 0;
                    int totalMoved = stats.has("totalMoved") ? stats.getAsInt("totalMoved") : 0;
                    pixivDatabase.setStats(totalArtworks, totalImages, totalMoved);
                    log.info("迁移统计数据: totalArtworks={}, totalImages={}, totalMoved={}",
                            totalArtworks, totalImages, totalMoved);
                } else {
                    log.info("数据库统计数据已存在，跳过统计迁移");
                }
            }

            String msg = String.format("迁移完成：成功迁移 %d 条，跳过 %d 条", migrated, skipped);
            log.info(msg);
            report(progressReporter, msg);
            return new MigrationResult(true, migrated, skipped, msg);

        } catch (Exception e) {
            log.error("迁移失败: {}", e.getMessage(), e);
            String msg = "迁移失败: " + e.getMessage();
            report(progressReporter, msg);
            return new MigrationResult(false, migrated, skipped, msg);
        }
    }

    private void report(Consumer<String> reporter, String message) {
        if (reporter != null) {
            reporter.accept(message);
        }
    }

    public record MigrationResult(boolean success, int migrated, int skipped, String message) {}
}
