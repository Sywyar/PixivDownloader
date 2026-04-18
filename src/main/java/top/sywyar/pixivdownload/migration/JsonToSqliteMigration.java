package top.sywyar.pixivdownload.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import top.sywyar.pixivdownload.download.config.DownloadConfig;
import top.sywyar.pixivdownload.download.db.PixivDatabase;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Map;
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
@RequiredArgsConstructor
public class JsonToSqliteMigration {

    private final ObjectMapper objectMapper;
    private final PixivDatabase pixivDatabase;
    private final DownloadConfig downloadConfig;

    public MigrationResponse migrate() throws IOException {
        return migrate(null);
    }

    public MigrationResponse migrate(Consumer<String> progressReporter) throws IOException {
        File historyFile = Paths.get(downloadConfig.getRootFolder(), "download_history.json").toFile();
        File statisticsFile = Paths.get(downloadConfig.getRootFolder(), "statistics.json").toFile();

        log.info("迁移开始，查找文件: {}", historyFile.getAbsolutePath());

        if (!historyFile.exists()) {
            String msg = "download_history.json 不存在，无需迁移";
            log.info(msg);
            report(progressReporter, msg);
            return new MigrationResponse(0, 0, msg);
        }

        int migrated = 0;
        int skipped = 0;

        JsonNode history = objectMapper.readTree(historyFile);
        JsonNode downloaded = history.path("downloaded");
        int total = downloaded.size();

        report(progressReporter, String.format("共找到 %d 条记录，开始迁移...", total));

        for (Map.Entry<String, JsonNode> entry : downloaded.properties()) {
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
                JsonNode artwork = entry.getValue();
                String title = artwork.path("title").asText(null);
                String folder = artwork.path("folder").asText(null);
                int count = artwork.path("count").asInt();
                String extensions = artwork.path("extensions").asText(null);
                long time = artwork.has("time")
                        ? artwork.path("time").asLong()
                        : pixivDatabase.getUniqueTime();

                pixivDatabase.insertArtwork(artworkId, title, folder, count, extensions, time, null, null);

                if (artwork.has("moved") && artwork.path("moved").asBoolean()) {
                    String moveFolder = artwork.path("moveFolder").asText(null);
                    long moveTime = artwork.path("moveTime").asLong();
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
                JsonNode stats = objectMapper.readTree(statisticsFile);
                int totalArtworks = stats.has("totalArtworks") ? stats.path("totalArtworks").asInt() : 0;
                int totalImages = stats.has("totalImages") ? stats.path("totalImages").asInt() : 0;
                int totalMoved = stats.has("totalMoved") ? stats.path("totalMoved").asInt() : 0;
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
        return new MigrationResponse(migrated, skipped, msg);
    }

    /**
     * 异步执行迁移并通过 SSE 推送进度。
     */
    @Async
    public void migrateAsync(SseEmitter emitter) {
        try {
            migrate(message -> {
                try {
                    emitter.send(SseEmitter.event().data(message));
                } catch (IOException e) {
                    emitter.completeWithError(e);
                }
            });
            emitter.complete();
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    }

    private void report(Consumer<String> reporter, String message) {
        if (reporter != null) {
            reporter.accept(message);
        }
    }
}
