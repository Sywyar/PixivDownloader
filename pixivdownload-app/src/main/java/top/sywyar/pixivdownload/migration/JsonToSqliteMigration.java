package top.sywyar.pixivdownload.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixCodec;
import top.sywyar.pixivdownload.core.db.DatabaseTarget;
import top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer;
import javax.sql.DataSource;
import top.sywyar.pixivdownload.core.time.EpochMillisNormalizer;
import top.sywyar.pixivdownload.core.pixiv.filename.PixivWorkFileNameFormatter;
import top.sywyar.pixivdownload.i18n.MessageBundles;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.List;
import java.util.LinkedHashMap;
import top.sywyar.pixivdownload.plugin.api.storage.relational.*;
import top.sywyar.pixivdownload.core.db.schema.CoreMigrationSchema;
import top.sywyar.pixivdownload.plugin.registry.schema.DatabaseSchemaRegistry;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.Operator.*;
import java.util.function.Consumer;

/**
 * 将旧版 JSON 文件数据迁移到选定的宿主主库。
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
 *       供 GUI 工具面板在停掉后端后通过 JDBC 操作配置中的目标。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JsonToSqliteMigration {

    private static final top.sywyar.pixivdownload.plugin.api.database.migration.MigrationSchema SCHEMA =
            CoreMigrationSchema.create(DatabaseSchemaRegistry.forBuiltInPlugins(), true);

    public static final String DOWNLOAD_HISTORY_FILE = "download_history.json";
    public static final String STATISTICS_FILE = "statistics.json";

    private final DownloadConfig downloadConfig;
    private final DatabaseTarget databaseTarget;
    private final DataSource dataSource;

    public MigrationResponse migrate() throws Exception {
        return migrate(null);
    }

    public MigrationResponse migrate(Consumer<String> progressReporter) throws Exception {
        Options options = new Options(databaseTarget.displayName(), downloadConfig.getRootFolder(), databaseTarget);
        Summary summary = run(options, progressReporter, dataSource);
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
     * 不依赖 Spring 上下文的迁移入口，按指定目标幂等执行。
     */
    public static Summary run(Options options, Consumer<String> reporter) throws Exception {
        return run(options, reporter, null);
    }

    public static Summary run(Options options, Consumer<String> reporter, DataSource dataSource) throws Exception {
        File historyFile = Paths.get(options.rootFolder(), DOWNLOAD_HISTORY_FILE).toFile();
        File statisticsFile = Paths.get(options.rootFolder(), STATISTICS_FILE).toFile();

        log.info(logMessage("migration.log.started", historyFile.getAbsolutePath()));

        if (!historyFile.exists()) {
            String msg = message("migration.log.history-not-found");
            log.info(logMessage("migration.log.history-not-found"));
            report(reporter, msg);
            return new Summary(0, 0, 0, true, msg);
        }

        ObjectMapper mapper = new ObjectMapper();
        JsonNode history = mapper.readTree(historyFile);
        JsonNode downloaded = history.path("downloaded");
        int total = downloaded.size();

        report(reporter, message("migration.log.found-records", total));

        int migrated = 0;
        int skipped = 0;

        try (Connection conn = dataSource == null ? options.target().openConnection() : dataSource.getConnection()) {
            options.target().requireConnectionTarget(conn);
            DatabaseInitializer.prepareOffline(conn, options.target());
            boolean autoCommit = conn.getAutoCommit();
            if (autoCommit) conn.setAutoCommit(false);
            java.sql.Savepoint savepoint = autoCommit ? null : conn.setSavepoint();
            try (var session = options.target().storageSession(conn, SCHEMA)) {
                if (autoCommit) options.target().prepareTransaction(conn, false);
                session.insert("file_name_templates", Map.of("id", 1L, "template", PixivWorkFileNameFormatter.DEFAULT_TEMPLATE),
                        DatabaseSession.Conflict.ignore());
                for (Map.Entry<String, JsonNode> entry : downloaded.properties()) {
                    long artworkId;
                    try {
                        artworkId = Long.parseLong(entry.getKey());
                    } catch (NumberFormatException e) {
                        log.warn(logMessage("migration.log.invalid-artwork-id", entry.getKey()));
                        skipped++;
                        continue;
                    }

                    if (exists(session, "artwork_id", artworkId)) {
                        skipped++;
                    } else {
                        JsonNode artwork = entry.getValue();
                        String title = artwork.path("title").asText(null);
                        String folder = stripTrailingSlash(artwork.path("folder").asText(null));
                        int count = artwork.path("count").asInt();
                        String extensions = artwork.path("extensions").asText(null);
                        long time = artwork.has("time")
                                ? EpochMillisNormalizer.normalize(artwork.path("time").asLong())
                                : nextUniqueTime(session);

                        var values = new LinkedHashMap<String, Object>();
                        values.put("artwork_id", artworkId); values.put("title", title); values.put("folder", folder);
                        values.put("count", (long) count); values.put("extensions", extensions); values.put("time", time);
                        if (session.insert("artworks", values, DatabaseSession.Conflict.ignore()) == 0) {
                            skipped++;
                            continue;
                        }

                        if (artwork.has("moved") && artwork.path("moved").asBoolean()) {
                            String moveFolder = stripTrailingSlash(artwork.path("moveFolder").asText(null));
                            long moveTime = EpochMillisNormalizer.normalize(artwork.path("moveTime").asLong());
                            session.update("artworks", Map.of("moved", value(1), "move_folder", value(moveFolder), "move_time", value(moveTime)),
                                    call(EQUAL, column("artworks", "artwork_id"), value(artworkId)));
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

                // 迁移统计数据（仅在数据库统计全为 0 时写入，避免覆盖新数据）
                if (statisticsFile.exists()) {
                    long[] currentStats = readStats(session);
                    if (currentStats[0] == 0 && currentStats[1] == 0 && currentStats[2] == 0) {
                        JsonNode stats = mapper.readTree(statisticsFile);
                        int totalArtworks = stats.has("totalArtworks") ? stats.path("totalArtworks").asInt() : 0;
                        int totalImages = stats.has("totalImages") ? stats.path("totalImages").asInt() : 0;
                        int totalMoved = stats.has("totalMoved") ? stats.path("totalMoved").asInt() : 0;
                        writeStats(session, totalArtworks, totalImages, totalMoved);
                        String statsMsg = message(
                                "migration.log.statistics-migrated", totalArtworks, totalImages, totalMoved);
                        log.info(logMessage(
                                "migration.log.statistics-migrated", totalArtworks, totalImages, totalMoved));
                        report(reporter, statsMsg);
                    } else {
                        String statsMsg = message("migration.log.statistics-skipped");
                        log.info(logMessage("migration.log.statistics-skipped"));
                        report(reporter, statsMsg);
                    }
                }
                if (autoCommit) conn.commit();
                else conn.releaseSavepoint(savepoint);
            } catch (Exception failure) {
                if (autoCommit) conn.rollback();
                else conn.rollback(savepoint);
                throw failure;
            } finally {
                if (autoCommit) conn.setAutoCommit(true);
            }
        }

        String msg = message("migration.log.completed", migrated, skipped);
        log.info(logMessage("migration.log.completed", migrated, skipped));
        report(reporter, msg);
        return new Summary(total, migrated, skipped, false, msg);
    }

    private static boolean exists(DatabaseSession session, String field, long value) throws SQLException {
        return (Long) session.query(Query.count("artworks", call(EQUAL, column("artworks", field), value(value))))
                .get(0).get("count") > 0;
    }

    private static long nextUniqueTime(DatabaseSession session) throws SQLException {
        long time = System.currentTimeMillis();
        while (exists(session, "time", time)) time = Math.incrementExact(time);
        return time;
    }

    private static long[] readStats(DatabaseSession session) throws SQLException {
        var fields = new LinkedHashMap<String, Expression>();
        for (String field : List.of("total_artworks", "total_images", "total_moved")) fields.put(field, column("statistics", field));
        var rows = session.query(new Query("statistics", fields, call(EQUAL, column("statistics", "id"), value(1)),
                List.of(), List.of(), 1, 0));
        if (rows.isEmpty()) return new long[]{0, 0, 0};
        var row = rows.get(0);
        return fields.keySet().stream().mapToLong(field -> row.get(field) == null ? 0L : (Long) row.get(field)).toArray();
    }

    private static void writeStats(DatabaseSession session, int totalArtworks, int totalImages, int totalMoved) throws SQLException {
        session.insert("statistics", Map.of("id", 1L, "total_artworks", (long) totalArtworks,
                        "total_images", (long) totalImages, "total_moved", (long) totalMoved),
                new DatabaseSession.Conflict(DatabaseSession.Mode.UPDATE, List.of("id"),
                        Map.of("total_artworks", new Incoming("total_artworks"), "total_images", new Incoming("total_images"),
                                "total_moved", new Incoming("total_moved"))));
    }

    private static String stripTrailingSlash(String path) {
        return PathPrefixCodec.stripTrailingSeparators(path);
    }

    private static void report(Consumer<String> reporter, String message) {
        if (reporter != null) {
            reporter.accept(message);
        }
    }

    private static String message(String code, Object... args) {
        return MessageBundles.get(code, args);
    }

    private static String logMessage(String code, Object... args) {
        return MessageBundles.getForLog(code, args);
    }

    public record Options(String dbPath, String rootFolder, DatabaseTarget target) {

        public Options(String dbPath, String rootFolder) {
            this(dbPath, rootFolder, DatabaseTarget.unavailable());
        }

    }

    public record Summary(int totalCandidates,
                          int migrated,
                          int skipped,
                          boolean historyFileMissing,
                          String message) {}
}
