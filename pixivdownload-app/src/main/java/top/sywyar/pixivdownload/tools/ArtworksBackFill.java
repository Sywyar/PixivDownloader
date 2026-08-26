package top.sywyar.pixivdownload.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import top.sywyar.pixivdownload.common.Utf8ConsoleStreams;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.i18n.MessageBundles;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 综合回填工具：一次 Pixiv AJAX 请求同时补全 artworks 表的 {@code author_id}、{@code "R18"}、
 * {@code is_ai}、{@code description} 四个字段，以及 {@code tags} / {@code artwork_tags} 关系表。
 *
 * {@code /ajax/illust/{id}}，根据响应内容选择性地更新当前仍为 NULL 的列；
 * 若 author_id 被补全，则同步维护 {@code authors} 表。
 * {@code artwork_tags} 中尚无任何记录的作品会把 Pixiv 返回的标签写入
 * {@code tags} 表并建立连接。
 *
 * <p>用法：
 * <pre>
 *   java -cp ... top.sywyar.pixivdownload.tools.ArtworksBackFill [选项]
 *
 *   --db       &lt;path&gt;       数据库文件路径（默认：data/pixiv_download.db）
 *   --proxy    &lt;host:port&gt;  HTTP 代理（默认：127.0.0.1:7890）
 *   --no-proxy              不使用代理
 *   --delay    &lt;ms&gt;         每次请求间隔毫秒（默认：800）
 *   --limit    &lt;n&gt;          仅处理前 n 条记录
 *   --dry-run               只打印结果，不写入数据库
 * </pre>
 */
@Slf4j
public class ArtworksBackFill {

    public static final Set<DatabaseColumn> SUPPORTED_DATABASE_COLUMNS = Set.of(
            new DatabaseColumn("artworks", "author_id"),
            new DatabaseColumn("artworks", "R18"),
            new DatabaseColumn("artworks", "is_ai"),
            new DatabaseColumn("artworks", "description"),
            new DatabaseColumn("artworks", "series_id"),
            new DatabaseColumn("artworks", "series_order")
    );

    public static void main(String[] args) throws Exception {
        Utf8ConsoleStreams.install();
        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException e) {
            printUsage();
            return;
        }
        run(options);
    }

    public static boolean supportsDatabaseColumn(String tableName, String columnName) {
        return SUPPORTED_DATABASE_COLUMNS.contains(new DatabaseColumn(tableName, columnName));
    }

    public static int countCandidates(Options options) throws Exception {
        try (ArtworksBackFillDatabase database = ArtworksBackFillDatabase.open(options.dbPath())) {
            return countCandidates(options, database);
        }
    }

    public static int countCandidates(Options options, DataSource dataSource) throws Exception {
        try (ArtworksBackFillDatabase database = ArtworksBackFillDatabase.open(dataSource, options.dbPath())) {
            return countCandidates(options, database);
        }
    }

    private static int countCandidates(Options options, ArtworksBackFillDatabase database) throws Exception {
        ArtworksBackFillUnreachableStore store = ArtworksBackFillUnreachableStore.load(
                RuntimeFiles.resolveBackfillUnreachablePath(),
                new ObjectMapper()
        );
        return database.countCandidates(options.limit(), store);
    }

    public static Summary run(Options options) throws Exception {
        try (ArtworksBackFillDatabase database = ArtworksBackFillDatabase.open(options.dbPath())) {
            return run(options, database, true);
        }
    }

    public static Summary run(Options options, DataSource dataSource) throws Exception {
        try (ArtworksBackFillDatabase database = ArtworksBackFillDatabase.open(dataSource, options.dbPath())) {
            return run(options, database, false);
        }
    }

    private static Summary run(
            Options options,
            ArtworksBackFillDatabase database,
            boolean requiresStoppedBackend
    ) throws Exception {
        log.info(logMessage(
                "artworks-backfill.log.started",
                options.dbPath(),
                options.useProxy()
                        ? options.proxyHost() + ":" + options.proxyPort()
                        : logMessage("artworks-backfill.option.proxy.none"),
                options.delayMs(),
                options.limit() > 0 ? options.limit() : logMessage("artworks-backfill.option.limit.all"),
                options.dryRun()
        ));
        if (requiresStoppedBackend) {
            log.info(logMessage("artworks-backfill.log.stop-backend-hint"));
        }

        ObjectMapper mapper = new ObjectMapper();
        Path unreachablePath = RuntimeFiles.resolveBackfillUnreachablePath();
        ArtworksBackFillUnreachableStore unreachable = ArtworksBackFillUnreachableStore.load(
                unreachablePath,
                mapper
        );
        log.info(logMessage("artworks-backfill.unreachable.loaded", unreachablePath, unreachable.size()));

        try (ArtworksBackFillPixivClient pixivClient = ArtworksBackFillPixivClient.open(options, mapper)) {

            ArtworksBackFillDatabase.FilteredCandidates filtered = database.findCandidates(
                    options.limit(),
                    unreachable
            );
            List<ArtworksBackFillDatabase.Candidate> candidates = filtered.candidates();
            int previouslyUnreachable = filtered.skippedUnreachable();
            if (previouslyUnreachable > 0) {
                log.info(logMessage("artworks-backfill.unreachable.skipped-existing", previouslyUnreachable));
            }
            log.info(logMessage("artworks-backfill.log.candidates.count", candidates.size()));
            if (candidates.isEmpty()) {
                Summary summary = new Summary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, previouslyUnreachable, 0, options.dryRun(), false);
                logSummary(summary);
                return summary;
            }

            int filledAuthor = 0;
            int filledR18 = 0;
            int filledAi = 0;
            int filledDescription = 0;
            int filledTags = 0;
            int filledSeries = 0;
            int deletedCount = 0;
            int newlyUnreachable = 0;
            int skipped = 0;

            for (int i = 0; i < candidates.size(); i++) {
                ArtworksBackFillDatabase.Candidate candidate = candidates.get(i);
                ArtworksBackFillPixivClient.LookupResult result = pixivClient.query(candidate.artworkId());
                String prefix = "[" + (i + 1) + "/" + candidates.size() + "] artwork="
                        + candidate.artworkId() + " missing=[" + describeMissing(candidate) + "]";

                switch (result.type) {
                    case FOUND -> {
                        boolean didAuthor = candidate.authorMissing() && result.authorId > 0;
                        boolean didR18 = candidate.r18Missing();
                        boolean didAi = candidate.aiMissing();
                        boolean didDesc = candidate.descriptionMissing() && result.description != null;
                        boolean didTags = candidate.tagsMissing() && result.tags != null;
                        boolean didSeries = candidate.seriesMissing();

                        List<String> changes = new ArrayList<>();
                        if (didAuthor) {
                            changes.add(logMessage("artworks-backfill.log.change.author", result.authorName, result.authorId));
                        }
                        if (didR18) {
                            changes.add(logMessage("artworks-backfill.log.change.r18", result.xRestrict));
                        }
                        if (didAi) {
                            changes.add(logMessage("artworks-backfill.log.change.ai", result.isAi ? 1 : 0));
                        }
                        if (didDesc) {
                            changes.add(logMessage("artworks-backfill.log.change.description", result.description.length()));
                        }
                        if (didTags) {
                            changes.add(logMessage("artworks-backfill.log.change.tags", result.tags.size()));
                        }
                        if (didSeries) {
                            changes.add(logMessage("artworks-backfill.log.change.series", result.seriesId, result.seriesOrder));
                        }

                        if (changes.isEmpty()) {
                            log.info(logMessage("artworks-backfill.log.no-fillable-data", prefix));
                            skipped++;
                        } else {
                            log.info(logMessage("artworks-backfill.log.changes", prefix, String.join(", ", changes)));
                            if (!options.dryRun()) {
                                database.applyUpdates(
                                        candidate,
                                        result,
                                        didAuthor,
                                        didR18,
                                        didAi,
                                        didDesc,
                                        didTags,
                                        didSeries
                                );
                            }
                            if (didAuthor) filledAuthor++;
                            if (didR18) filledR18++;
                            if (didAi) filledAi++;
                            if (didDesc) filledDescription++;
                            if (didTags) filledTags++;
                            if (didSeries) filledSeries++;
                        }
                    }
                    case R18_ONLY -> {
                        if (candidate.r18Missing()) {
                            log.info(logMessage("artworks-backfill.log.r18-only", prefix, result.message));
                            filledR18++;
                            if (!options.dryRun()) {
                                database.applyR18Only(candidate.artworkId());
                            }
                        } else {
                            log.info(logMessage("artworks-backfill.log.skip.r18-already-filled", prefix, result.message));
                            skipped++;
                        }
                    }
                    case DELETED -> {
                        log.info(logMessage("artworks-backfill.log.deleted-skip", prefix, result.message));
                        deletedCount++;
                        boolean alreadyKnown = unreachable.contains(candidate.artworkId());
                        unreachable.record(candidate.artworkId(), result.message);
                        if (!alreadyKnown) {
                            newlyUnreachable++;
                        }
                        persistUnreachable(unreachable, unreachablePath);
                    }
                    case SKIP -> {
                        log.info(logMessage("artworks-backfill.log.skip", prefix, result.message));
                        skipped++;
                    }
                    case RATE_LIMITED -> {
                        log.warn(logMessage("artworks-backfill.log.rate-limited", prefix));
                        log.info(logMessage(
                                "artworks-backfill.log.progress",
                                i, candidates.size(), filledAuthor, filledR18, filledAi, filledDescription, filledTags, filledSeries, deletedCount, skipped
                        ));
                        if (options.dryRun()) {
                            log.info(logMessage("artworks-backfill.log.dry-run"));
                        }
                        persistUnreachable(unreachable, unreachablePath);
                        Summary summary = new Summary(
                                candidates.size(),
                                i,
                                filledAuthor,
                                filledR18,
                                filledAi,
                                filledDescription,
                                filledTags,
                                filledSeries,
                                deletedCount,
                                skipped,
                                previouslyUnreachable,
                                newlyUnreachable,
                                options.dryRun(),
                                true
                        );
                        logSummary(summary);
                        return summary;
                    }
                }

                if (i < candidates.size() - 1) {
                    Thread.sleep(options.delayMs());
                }
            }

            persistUnreachable(unreachable, unreachablePath);
            Summary summary = new Summary(
                    candidates.size(),
                    candidates.size(),
                    filledAuthor,
                    filledR18,
                    filledAi,
                    filledDescription,
                    filledTags,
                    filledSeries,
                    deletedCount,
                    skipped,
                    previouslyUnreachable,
                    newlyUnreachable,
                    options.dryRun(),
                    false
            );
            logSummary(summary);
            return summary;
        }
    }

    private static void persistUnreachable(ArtworksBackFillUnreachableStore store, Path path) {
        try {
            store.save();
        } catch (IOException e) {
            log.warn(logMessage("artworks-backfill.unreachable.save-failed", path, e.getMessage()));
        }
    }

    private static void logSummary(Summary summary) {
        log.info(logMessage(
                "artworks-backfill.log.summary",
                summary.totalCandidates(),
                summary.filledAuthor(),
                summary.filledR18(),
                summary.filledAi(),
                summary.filledDescription(),
                summary.filledTags(),
                summary.filledSeries(),
                summary.deletedCount(),
                summary.skipped(),
                summary.previouslyUnreachable(),
                summary.newlyUnreachable()
        ));
        if (summary.dryRun()) {
            log.info(logMessage("artworks-backfill.log.dry-run"));
        }
    }

    private static String safeMessage(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static String describeMissing(ArtworksBackFillDatabase.Candidate candidate) {
        List<String> parts = new ArrayList<>(6);
        if (candidate.authorMissing()) parts.add("author");
        if (candidate.r18Missing()) parts.add("R18");
        if (candidate.aiMissing()) parts.add("AI");
        if (candidate.descriptionMissing()) parts.add("desc");
        if (candidate.tagsMissing()) parts.add("tags");
        if (candidate.seriesMissing()) parts.add("series");
        return String.join("+", parts);
    }

    private static void printUsage() {
        System.out.println(message("artworks-backfill.cli.usage"));
    }

    public record Options(String dbPath,
                          String proxyHost,
                          int proxyPort,
                          boolean useProxy,
                          long delayMs,
                          int limit,
                          boolean dryRun) {

        public static Options defaults() {
            return new Options(
                    RuntimeFiles.dataDirectory().resolve(RuntimeFiles.PIXIV_DOWNLOAD_DB).toString(),
                    "127.0.0.1",
                    7890,
                    true,
                    800L,
                    0,
                    false
            );
        }

        public static Options parse(String[] args) {
            String dbPath = defaults().dbPath;
            String proxyHost = defaults().proxyHost;
            int proxyPort = defaults().proxyPort;
            boolean useProxy = defaults().useProxy;
            long delayMs = defaults().delayMs;
            int limit = defaults().limit;
            boolean dryRun = defaults().dryRun;

            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--db" -> dbPath = requireValue(args, ++i, "--db");
                    case "--proxy" -> {
                        String[] parts = requireValue(args, ++i, "--proxy").split(":");
                        if (parts.length != 2) {
                            throw new IllegalArgumentException(message("artworks-backfill.cli.error.invalid-proxy"));
                        }
                        proxyHost = parts[0];
                        proxyPort = Integer.parseInt(parts[1]);
                    }
                    case "--no-proxy" -> useProxy = false;
                    case "--delay" -> delayMs = Long.parseLong(requireValue(args, ++i, "--delay"));
                    case "--limit" -> limit = Integer.parseInt(requireValue(args, ++i, "--limit"));
                    case "--dry-run" -> dryRun = true;
                    default -> throw new IllegalArgumentException(message("artworks-backfill.cli.error.unknown-option", args[i]));
                }
            }
            return new Options(dbPath, proxyHost, proxyPort, useProxy, delayMs, limit, dryRun);
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException(message("artworks-backfill.cli.error.missing-value", option));
            }
            return args[index];
        }
    }

    public record Summary(int totalCandidates,
                          int processed,
                          int filledAuthor,
                          int filledR18,
                          int filledAi,
                          int filledDescription,
                          int filledTags,
                          int filledSeries,
                          int deletedCount,
                          int skipped,
                          int previouslyUnreachable,
                          int newlyUnreachable,
                          boolean dryRun,
                          boolean rateLimited) {}

    private static String message(String code, Object... args) {
        return MessageBundles.get(code, args);
    }

    private static String logMessage(String code, Object... args) {
        return MessageBundles.getForLog(code, args);
    }

    public record DatabaseColumn(String tableName, String columnName) {
        public DatabaseColumn {
            tableName = normalizeIdentifier(tableName);
            columnName = normalizeIdentifier(columnName);
        }
    }

    private static String normalizeIdentifier(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

}
