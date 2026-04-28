package top.sywyar.pixivdownload.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.sqlite.SQLiteConfig;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.download.ArtworkFileNameFormatter;
import top.sywyar.pixivdownload.download.config.DownloadConfig;
import top.sywyar.pixivdownload.i18n.MessageBundles;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
 *
 * <p>同时提供两套调用入口：
 * <ul>
 *   <li>Spring 注入版本：{@link #migrate()} / {@link #migrateAsync(SseEmitter)}，
 *       由 {@link MigrationController} 通过 REST 触发。</li>
 *   <li>独立运行版本：静态 {@link #run(Options, Consumer)} 和 {@link #countCandidates(Options)}，
 *       供 GUI 工具面板在停掉后端后通过 JDBC 直接操作 SQLite。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JsonToSqliteMigration {

    public static final String DOWNLOAD_HISTORY_FILE = "download_history.json";
    public static final String STATISTICS_FILE = "statistics.json";

    private final DownloadConfig downloadConfig;

    public MigrationResponse migrate() throws Exception {
        return migrate(null);
    }

    public MigrationResponse migrate(Consumer<String> progressReporter) throws Exception {
        Options options = Options.fromRootFolder(downloadConfig.getRootFolder());
        Summary summary = run(options, progressReporter);
        return new MigrationResponse(summary.migrated(), summary.skipped(), summary.message());
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

    /**
     * 仅扫描旧版 JSON 文件，返回待迁移记录数。供 GUI 在执行前提示用户。
     */
    public static int countCandidates(Options options) throws IOException {
        File historyFile = Paths.get(options.rootFolder(), DOWNLOAD_HISTORY_FILE).toFile();
        if (!historyFile.exists()) {
            return 0;
        }
        ObjectMapper mapper = new ObjectMapper();
        JsonNode history = mapper.readTree(historyFile);
        return history.path("downloaded").size();
    }

    /**
     * 不依赖 Spring 上下文的迁移入口：直接通过 JDBC 操作 SQLite，幂等执行。
     */
    public static Summary run(Options options, Consumer<String> reporter) throws Exception {
        File historyFile = Paths.get(options.rootFolder(), DOWNLOAD_HISTORY_FILE).toFile();
        File statisticsFile = Paths.get(options.rootFolder(), STATISTICS_FILE).toFile();

        log.info(message("migration.log.started", historyFile.getAbsolutePath()));

        if (!historyFile.exists()) {
            String msg = message("migration.log.history-not-found");
            log.info(msg);
            report(reporter, msg);
            return new Summary(0, 0, 0, true, msg);
        }

        ObjectMapper mapper = new ObjectMapper();
        JsonNode history = mapper.readTree(historyFile);
        JsonNode downloaded = history.path("downloaded");
        int total = downloaded.size();

        report(reporter, message("migration.log.found-records", total));

        SQLiteConfig sqliteConfig = new SQLiteConfig();
        sqliteConfig.setBusyTimeout(5000);
        sqliteConfig.setJournalMode(SQLiteConfig.JournalMode.WAL);

        int migrated = 0;
        int skipped = 0;

        try (Connection conn = DriverManager.getConnection(
                "jdbc:sqlite:" + options.dbPath(), sqliteConfig.toProperties())) {

            ensureSchema(conn);

            try (PreparedStatement countStmt = conn.prepareStatement(
                    "SELECT COUNT(*) FROM artworks WHERE artwork_id = ?");
                 PreparedStatement countTimeStmt = conn.prepareStatement(
                         "SELECT COUNT(*) FROM artworks WHERE time = ?");
                 PreparedStatement insertStmt = conn.prepareStatement(
                         "INSERT OR IGNORE INTO artworks(artwork_id, title, folder, count, extensions, time)"
                                 + " VALUES(?, ?, ?, ?, ?, ?)");
                 PreparedStatement updateMoveStmt = conn.prepareStatement(
                         "UPDATE artworks SET moved = 1, move_folder = ?, move_time = ? WHERE artwork_id = ?")) {

                for (Map.Entry<String, JsonNode> entry : downloaded.properties()) {
                    long artworkId;
                    try {
                        artworkId = Long.parseLong(entry.getKey());
                    } catch (NumberFormatException e) {
                        log.warn(message("migration.log.invalid-artwork-id", entry.getKey()));
                        skipped++;
                        continue;
                    }

                    if (hasArtwork(countStmt, artworkId)) {
                        skipped++;
                    } else {
                        JsonNode artwork = entry.getValue();
                        String title = artwork.path("title").asText(null);
                        String folder = stripTrailingSlash(artwork.path("folder").asText(null));
                        int count = artwork.path("count").asInt();
                        String extensions = artwork.path("extensions").asText(null);
                        long time = artwork.has("time")
                                ? artwork.path("time").asLong()
                                : nextUniqueTime(countTimeStmt);

                        insertStmt.setLong(1, artworkId);
                        insertStmt.setString(2, title);
                        insertStmt.setString(3, folder);
                        insertStmt.setInt(4, count);
                        insertStmt.setString(5, extensions);
                        insertStmt.setLong(6, time);
                        insertStmt.executeUpdate();

                        if (artwork.has("moved") && artwork.path("moved").asBoolean()) {
                            String moveFolder = stripTrailingSlash(artwork.path("moveFolder").asText(null));
                            long moveTime = artwork.path("moveTime").asLong();
                            updateMoveStmt.setString(1, moveFolder);
                            updateMoveStmt.setLong(2, moveTime);
                            updateMoveStmt.setLong(3, artworkId);
                            updateMoveStmt.executeUpdate();
                        }

                        migrated++;
                    }

                    int done = migrated + skipped;
                    if (total > 0 && done % 100 == 0) {
                        int percent = done * 100 / total;
                        report(reporter, message(
                                "migration.log.progress", done, total, percent, migrated, skipped));
                    }
                }
            }

            // 迁移统计数据（仅在数据库统计全为 0 时写入，避免覆盖新数据）
            if (statisticsFile.exists()) {
                int[] currentStats = readStats(conn);
                if (currentStats[0] == 0 && currentStats[1] == 0 && currentStats[2] == 0) {
                    JsonNode stats = mapper.readTree(statisticsFile);
                    int totalArtworks = stats.has("totalArtworks") ? stats.path("totalArtworks").asInt() : 0;
                    int totalImages = stats.has("totalImages") ? stats.path("totalImages").asInt() : 0;
                    int totalMoved = stats.has("totalMoved") ? stats.path("totalMoved").asInt() : 0;
                    writeStats(conn, totalArtworks, totalImages, totalMoved);
                    String statsMsg = message(
                            "migration.log.statistics-migrated", totalArtworks, totalImages, totalMoved);
                    log.info(statsMsg);
                    report(reporter, statsMsg);
                } else {
                    String statsMsg = message("migration.log.statistics-skipped");
                    log.info(statsMsg);
                    report(reporter, statsMsg);
                }
            }
        }

        String msg = message("migration.log.completed", migrated, skipped);
        log.info(msg);
        report(reporter, msg);
        return new Summary(total, migrated, skipped, false, msg);
    }

    private static boolean hasArtwork(PreparedStatement stmt, long artworkId) throws SQLException {
        stmt.setLong(1, artworkId);
        try (ResultSet rs = stmt.executeQuery()) {
            return rs.next() && rs.getInt(1) > 0;
        }
    }

    private static long nextUniqueTime(PreparedStatement countTimeStmt) throws SQLException {
        long time = System.currentTimeMillis() / 1000;
        while (true) {
            countTimeStmt.setLong(1, time);
            try (ResultSet rs = countTimeStmt.executeQuery()) {
                if (!rs.next() || rs.getInt(1) == 0) {
                    return time;
                }
            }
            time++;
        }
    }

    private static int[] readStats(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT total_artworks, total_images, total_moved FROM statistics WHERE id = 1");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return new int[]{rs.getInt(1), rs.getInt(2), rs.getInt(3)};
            }
            return new int[]{0, 0, 0};
        }
    }

    private static void writeStats(Connection conn, int totalArtworks, int totalImages, int totalMoved)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO statistics(id, total_artworks, total_images, total_moved) VALUES(1, ?, ?, ?)"
                        + " ON CONFLICT(id) DO UPDATE SET"
                        + " total_artworks = excluded.total_artworks,"
                        + " total_images = excluded.total_images,"
                        + " total_moved = excluded.total_moved")) {
            ps.setInt(1, totalArtworks);
            ps.setInt(2, totalImages);
            ps.setInt(3, totalMoved);
            ps.executeUpdate();
        }
    }

    /**
     * 保证脱机运行时也能拿到一份和后端启动后等价的最小 schema。
     * 仅创建本工具实际写入的两张表，其它列由 PixivDatabase.init 在后端启动时维护。
     */
    private static void ensureSchema(Connection conn) throws SQLException {
        try (PreparedStatement createArtworks = conn.prepareStatement(
                "CREATE TABLE IF NOT EXISTS artworks ("
                        + "artwork_id INTEGER PRIMARY KEY,"
                        + "title TEXT,"
                        + "folder TEXT,"
                        + "count INTEGER,"
                        + "extensions TEXT,"
                        + "time INTEGER UNIQUE,"
                        + "file_name INTEGER NOT NULL DEFAULT 1,"
                        + "file_names TEXT,"
                        + "moved INTEGER DEFAULT 0,"
                        + "move_folder TEXT,"
                        + "move_time INTEGER)")) {
            createArtworks.executeUpdate();
        }
        try (PreparedStatement createFileNameTemplates = conn.prepareStatement(
                "CREATE TABLE IF NOT EXISTS file_name_templates ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "template TEXT NOT NULL UNIQUE)")) {
            createFileNameTemplates.executeUpdate();
        }
        try (PreparedStatement defaultFileNameTemplate = conn.prepareStatement(
                "INSERT OR IGNORE INTO file_name_templates(id, template) VALUES(1, ?)")) {
            defaultFileNameTemplate.setString(1, ArtworkFileNameFormatter.DEFAULT_TEMPLATE);
            defaultFileNameTemplate.executeUpdate();
        }
        try (PreparedStatement createStatistics = conn.prepareStatement(
                "CREATE TABLE IF NOT EXISTS statistics ("
                        + "id INTEGER PRIMARY KEY,"
                        + "total_artworks INTEGER DEFAULT 0,"
                        + "total_images INTEGER DEFAULT 0,"
                        + "total_moved INTEGER DEFAULT 0)")) {
            createStatistics.executeUpdate();
        }
    }

    private static String stripTrailingSlash(String path) {
        return path == null ? null : path.replaceAll("[/\\\\]+$", "");
    }

    private static void report(Consumer<String> reporter, String message) {
        if (reporter != null) {
            reporter.accept(message);
        }
    }

    private static String message(String code, Object... args) {
        return MessageBundles.get(code, args);
    }

    public record Options(String dbPath, String rootFolder) {

        public static Options fromRootFolder(String rootFolder) {
            String normalized = RuntimeFiles.normalizeRootFolder(rootFolder);
            return new Options(
                    RuntimeFiles.resolveDatabasePath(normalized).toString(),
                    normalized
            );
        }

        public static Options defaults() {
            return fromRootFolder(RuntimeFiles.DEFAULT_DOWNLOAD_ROOT);
        }
    }

    public record Summary(int totalCandidates,
                          int migrated,
                          int skipped,
                          boolean historyFileMissing,
                          String message) {}
}
