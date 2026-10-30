package top.sywyar.pixivdownload.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import top.sywyar.pixivdownload.common.Utf8ConsoleStreams;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.core.db.DatabaseTarget;
import top.sywyar.pixivdownload.config.ProxyConfig;
import top.sywyar.pixivdownload.i18n.MessageBundles;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 从本地元数据或 Pixiv 详情补齐插画资料与小说上传时间，只填当前缺失的值。
 * 插画同时维护作者、标签与系列关联；小说只更新上传时间。
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
            new DatabaseColumn("artworks", "series_order"),
            new DatabaseColumn("artworks", "upload_time"),
            new DatabaseColumn("novels", "upload_time")
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
        var environment = DatabaseTarget.configurationEnvironment();
        try (var plugins = top.sywyar.pixivdownload.core.db.provider.DatabaseBootstrap.openToolSession(environment)) {
            var target = top.sywyar.pixivdownload.core.db.provider.DatabaseBootstrap.inspect(plugins, environment);
            if (java.util.Arrays.asList(args).contains("--db")) target = target.withLocalFile(Path.of(options.dbPath()));
            run(options.withTarget(target).withSources(
                    java.util.Arrays.asList(args).contains("--root-folder") ? options.rootFolder()
                            : environment.getProperty("download.root-folder", new top.sywyar.pixivdownload.core.appconfig.DownloadConfig().getRootFolder()),
                    options.alwaysNetwork()));
        }
    }

    public static boolean supportsDatabaseColumn(String tableName, String columnName) {
        return SUPPORTED_DATABASE_COLUMNS.contains(new DatabaseColumn(tableName, columnName));
    }

    public static int countCandidates(Options options) throws Exception {
        try (ArtworksBackFillDatabase database = ArtworksBackFillDatabase.open(options.target())) {
            return countCandidates(options, database);
        }
    }

    public static int countCandidates(Options options, DataSource dataSource) throws Exception {
        try (ArtworksBackFillDatabase database = ArtworksBackFillDatabase.open(dataSource, options.target())) {
            return countCandidates(options, database);
        }
    }

    private static int countCandidates(Options options, ArtworksBackFillDatabase database) throws Exception {
        ArtworksBackFillUnreachableStore store = ArtworksBackFillUnreachableStore.load(
                RuntimeFiles.resolveBackfillUnreachablePath(),
                new ObjectMapper()
        );
        return database.findCandidates(options.limit(), store, options.alwaysNetwork() ? null : options.rootFolder(),
                options.automatic()).candidates().size();
    }

    public static Summary run(Options options) throws Exception {
        try (ArtworksBackFillDatabase database = ArtworksBackFillDatabase.open(options.target())) {
            return run(options, database, true, value -> {});
        }
    }

    public static Summary run(Options options, DataSource dataSource) throws Exception {
        return run(options, dataSource, (processed, total) -> {});
    }

    public static Summary run(Options options, DataSource dataSource,
            java.util.function.BiConsumer<Integer, Integer> progress) throws Exception {
        java.util.Objects.requireNonNull(progress, "progress");
        return runObserved(options, dataSource, value -> {
            if (value.total() != null) progress.accept(value.processed(), value.total());
        });
    }

    /** 同步发布真实阶段与计数；调用方不需要解析日志。 */
    public static Summary runObserved(Options options, DataSource dataSource,
            java.util.function.Consumer<Progress> progress) throws Exception {
        java.util.Objects.requireNonNull(progress, "progress");
        try (ArtworksBackFillDatabase database = ArtworksBackFillDatabase.open(dataSource, options.target())) {
            return run(options, database, false, progress);
        }
    }

    public enum Phase { IMPORTING, SCANNING, LOCAL, NETWORK, WAITING, FINISHED }

    /** total 在候选扫描完成前未知；changed 按作品去重，不累计字段数。 */
    public record Progress(Phase phase, int processed, Integer total, int changed, int remaining,
                           int unavailable, String workType, Long workId, Summary summary) {}

    private static Summary run(
            Options options,
            ArtworksBackFillDatabase database,
            boolean requiresStoppedBackend,
            java.util.function.Consumer<Progress> observer
    ) throws Exception {
        var progress = new top.sywyar.pixivdownload.tools.backfill.BackfillProgressLogger().andThen(observer);
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
        database.requireAvailable();
        Path unreachablePath = RuntimeFiles.resolveBackfillUnreachablePath();
        ArtworksBackFillUnreachableStore unreachable = ArtworksBackFillUnreachableStore.load(
                unreachablePath,
                mapper
        );
        log.info(logMessage("artworks-backfill.unreachable.loaded", unreachablePath, unreachable.size()));

        Counts counts = new Counts();
        reportProgress(progress, Phase.IMPORTING, counts, null, options.dryRun());
        database.importMetadata(options.rootFolder(), options.dryRun(), text -> {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            log.info(text);
        });
        try (ArtworksBackFillPixivClient pixivClient = ArtworksBackFillPixivClient.open(options, mapper)) {
            reportProgress(progress, Phase.SCANNING, counts, null, options.dryRun());
            var filtered = database.findCandidates(options.limit(), unreachable, options.alwaysNetwork() ? null : options.rootFolder(),
                    options.automatic());
            var candidates = filtered.candidates();
            counts.previouslyUnreachable = filtered.skippedUnreachable();
            reportProgress(progress, Phase.LOCAL, counts, candidates.size(), options.dryRun());
            boolean requested = false;
            try {
                for (var candidate : candidates) {
                    counts.current = candidate;
                    counts.workStartFilled = counts.filled();
                    reportProgress(progress, Phase.LOCAL, counts, candidates.size(), options.dryRun());
                    database.requireAvailable();
                    int before = counts.filled() + counts.deleted + counts.previouslyUnreachable;
                    int authorBefore = counts.author, seriesBefore = counts.series;
                    var remaining = candidate;
                    boolean networkFound = false;
                    if (!options.alwaysNetwork()) {
                        var local = database.localMetadata(candidate.workType(), candidate.artworkId(), options.rootFolder());
                        if (local != null) remaining = fill(database, remaining, local, options.dryRun(), counts);
                        reportProgress(progress, Phase.LOCAL, counts, candidates.size(), options.dryRun());
                    }
                    if (remaining.hasMissing()) {
                        if (unreachable.blocksNetwork(candidate.workType(), candidate.artworkId(), options.automatic())) {
                            counts.unavailable++;
                            if (unreachable.contains(candidate.workType(), candidate.artworkId())) counts.previouslyUnreachable++;
                        } else {
                            if (requested && options.delayMs() > 0) {
                                reportProgress(progress, Phase.WAITING, counts, candidates.size(), options.dryRun());
                                Thread.sleep(options.delayMs());
                            }
                            reportProgress(progress, Phase.NETWORK, counts, candidates.size(), options.dryRun());
                            database.requireAvailable();
                            var result = candidate.workType() == top.sywyar.pixivdownload.core.work.model.WorkType.NOVEL
                                    ? pixivClient.queryNovel(candidate.artworkId()) : pixivClient.query(candidate.artworkId());
                            requested = true;
                            reportProgress(progress, Phase.NETWORK, counts, candidates.size(), options.dryRun());
                            database.requireAvailable();
                            switch (result.type) {
                                case FOUND -> {
                                    remaining = fill(database, remaining, result, options.dryRun(), counts);
                                    networkFound = true;
                                }
                                case R18_ONLY -> {
                                    counts.unavailable++;
                                    remaining = fill(database, remaining, result, options.dryRun(), counts);
                                    if (!options.dryRun()) {
                                        unreachable.recordRestricted(candidate.workType(), candidate.artworkId(), result.message);
                                        persistUnreachable(database, unreachable, unreachablePath);
                                    }
                                }
                                case DELETED -> {
                                    counts.unavailable++;
                                    counts.deleted++;
                                    counts.newlyUnreachable++;
                                    if (!options.dryRun()) {
                                        unreachable.record(candidate.workType(), candidate.artworkId(), result.message);
                                        persistUnreachable(database, unreachable, unreachablePath);
                                    }
                                }
                                case SKIP -> { }
                                case RATE_LIMITED -> {
                                    var summary = counts.summary(candidates.size(), options.dryRun(), true);
                                    publishProgress(progress, Phase.FINISHED, counts, candidates.size(), options.dryRun());
                                    logSummary(summary);
                                    return summary;
                                }
                            }
                        }
                    }
                    if (!options.dryRun() && (networkFound || !remaining.hasMissing())) {
                        unreachable.clearRestricted(candidate.workType(), candidate.artworkId());
                        persistUnreachable(database, unreachable, unreachablePath);
                    }
                    // 同一作品的本地与网络分段补齐只计一次字段组。
                    counts.author = Math.min(counts.author, authorBefore + 1);
                    counts.series = Math.min(counts.series, seriesBefore + 1);
                    if (counts.filled() > counts.workStartFilled) counts.changed++;
                    if (remaining.hasMissing()) counts.remaining++;
                    if (before == counts.filled() + counts.deleted + counts.previouslyUnreachable) counts.skipped++;
                    counts.processed++;
                    counts.current = null;
                    reportProgress(progress, Phase.LOCAL, counts, candidates.size(), options.dryRun());
                }
            } catch (Exception failure) {
                publishProgress(progress, Phase.FINISHED, counts, candidates.size(), options.dryRun());
                logSummary(counts.summary(candidates.size(), options.dryRun(), false));
                throw failure;
            }
            var summary = counts.summary(candidates.size(), options.dryRun(), false);
            publishProgress(progress, Phase.FINISHED, counts, candidates.size(), options.dryRun());
            logSummary(summary);
            return summary;
        }
    }

    private static ArtworksBackFillDatabase.Candidate fill(ArtworksBackFillDatabase database,
            ArtworksBackFillDatabase.Candidate candidate, ArtworksBackFillPixivClient.LookupResult result,
            boolean dryRun, Counts counts) throws java.sql.SQLException {
        var available = candidate.availableFields(result);
        boolean author = available.authorMissing(), r18 = available.r18Missing(), ai = available.aiMissing();
        boolean description = available.descriptionMissing(), tags = available.tagsMissing();
        boolean series = available.seriesMissing(), uploadTime = available.uploadTimeMissing();
        if (available.hasMissing()) {
            if (!dryRun) {
                var applied = database.applyUpdates(candidate, result, author, r18, ai, description, tags, series, uploadTime);
                author = author && applied.author();
                r18 = r18 && applied.r18();
                ai = ai && applied.ai();
                description = description && applied.description();
                tags = tags && applied.tags();
                series = series && applied.series();
                uploadTime = uploadTime && applied.uploadTime();
            }
            if (author) counts.author++;
            if (r18) counts.r18++;
            if (ai) counts.ai++;
            if (description) counts.description++;
            if (tags) counts.tags++;
            if (series) counts.series++;
            if (uploadTime) counts.uploadTime++;
            var applied = new ArtworksBackFillDatabase.Candidate(candidate.artworkId(), author, r18, ai, description, tags, series, uploadTime);
            if (applied.hasMissing())
                log.debug(logMessage("artworks-backfill.log.changes", candidate.workType().name() + ":" + candidate.artworkId(), describeMissing(applied)));
        }
        if (!dryRun) return database.currentCandidate(candidate.workType(), candidate.artworkId());
        Long authorId = author ? Long.valueOf(result.authorId) : candidate.authorId();
        Long seriesId = series ? Long.valueOf(result.seriesId) : candidate.seriesId();
        boolean nameMissing = candidate.authorNameMissing() && !(author && result.authorName != null);
        boolean orderMissing = candidate.seriesOrderMissing() && !(series && (result.seriesId == 0 || result.seriesOrder != null));
        boolean titleMissing = candidate.seriesTitleMissing() && !(series && (result.seriesId == 0 || result.seriesTitle != null));
        return new ArtworksBackFillDatabase.Candidate(candidate.artworkId(),
                candidate.authorMissing() && (authorId == null || nameMissing), candidate.r18Missing() && !r18,
                candidate.aiMissing() && !ai, candidate.descriptionMissing() && !description,
                candidate.tagsMissing() && !tags, candidate.seriesMissing() && (seriesId == null || orderMissing || titleMissing),
                candidate.uploadTimeMissing() && !uploadTime, authorId, seriesId, nameMissing, orderMissing, titleMissing).withType(candidate.workType());
    }

    private static final class Counts {
        int processed, author, r18, ai, description, tags, series, uploadTime, deleted, skipped,
                previouslyUnreachable, newlyUnreachable;
        int changed, remaining, unavailable, workStartFilled;
        ArtworksBackFillDatabase.Candidate current;
        int filled() { return author + r18 + ai + description + tags + series + uploadTime; }
        Summary summary(int total, boolean dryRun, boolean rateLimited) {
            return new Summary(total, processed, author, r18, ai, description, tags, series, deleted, skipped,
                    previouslyUnreachable, newlyUnreachable, dryRun, rateLimited, uploadTime);
        }
    }

    private static void reportProgress(java.util.function.Consumer<Progress> progress,
            Phase phase, Counts counts, Integer total, boolean dryRun) throws InterruptedException {
        publishProgress(progress, phase, counts, total, dryRun);
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Backfill interrupted");
    }

    private static void publishProgress(java.util.function.Consumer<Progress> progress,
            Phase phase, Counts counts, Integer total, boolean dryRun) {
        var current = counts.current;
        progress.accept(new Progress(phase, counts.processed, total,
                counts.changed + (current != null && counts.filled() > counts.workStartFilled ? 1 : 0),
                counts.remaining, counts.unavailable,
                current == null ? null : current.workType().name(), current == null ? null : current.artworkId(),
                counts.summary(total == null ? 0 : total, dryRun, false)));
    }

    private static void persistUnreachable(ArtworksBackFillDatabase database,
                                           ArtworksBackFillUnreachableStore store, Path path) throws java.sql.SQLException {
        database.requireAvailable();
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
                summary.newlyUnreachable(),
                summary.filledUploadTime()
        ));
        if (summary.dryRun()) {
            log.info(logMessage("artworks-backfill.log.dry-run"));
        }
    }

    private static String describeMissing(ArtworksBackFillDatabase.Candidate candidate) {
        List<String> parts = new ArrayList<>(6);
        if (candidate.authorMissing()) parts.add("author");
        if (candidate.r18Missing()) parts.add("R18");
        if (candidate.aiMissing()) parts.add("AI");
        if (candidate.descriptionMissing()) parts.add("desc");
        if (candidate.tagsMissing()) parts.add("tags");
        if (candidate.seriesMissing()) parts.add("series");
        if (candidate.uploadTimeMissing()) parts.add("upload_time");
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
                          boolean dryRun,
                          DatabaseTarget target,
                          String rootFolder,
                          boolean alwaysNetwork,
                          boolean automatic) {

        public Options(String dbPath, String proxyHost, int proxyPort, boolean useProxy,
                       long delayMs, int limit, boolean dryRun, DatabaseTarget target, String rootFolder, boolean alwaysNetwork) {
            this(dbPath, proxyHost, proxyPort, useProxy, delayMs, limit, dryRun, target, rootFolder, alwaysNetwork, false);
        }

        public Options(String dbPath, String proxyHost, int proxyPort, boolean useProxy,
                       long delayMs, int limit, boolean dryRun, DatabaseTarget target) {
            this(dbPath, proxyHost, proxyPort, useProxy, delayMs, limit, dryRun, target,
                    new top.sywyar.pixivdownload.core.appconfig.DownloadConfig().getRootFolder(), false);
        }

        public Options withSources(String folder, boolean network) {
            return new Options(dbPath, proxyHost, proxyPort, useProxy, delayMs, limit, dryRun, target, folder, network, automatic);
        }

        /** 启动自动恢复暂缓已知受限作品的联网请求，仍允许本地补缺。 */
        public Options forStartup() {
            return new Options(dbPath, proxyHost, proxyPort, useProxy, delayMs, limit, dryRun, target, rootFolder, alwaysNetwork, true);
        }

        public Options(String dbPath, String proxyHost, int proxyPort, boolean useProxy,
                       long delayMs, int limit, boolean dryRun) {
            this(dbPath, proxyHost, proxyPort, useProxy, delayMs, limit, dryRun,
                    DatabaseTarget.unavailable());
        }

        public Options withTarget(DatabaseTarget databaseTarget) {
            return new Options(databaseTarget.displayName(), proxyHost, proxyPort, useProxy,
                    delayMs, limit, dryRun, databaseTarget, rootFolder, alwaysNetwork, automatic);
        }

        public static Options defaults() {
            ProxyConfig proxy = new ProxyConfig();
            return new Options(
                    "DATABASE_UNAVAILABLE",
                    proxy.getHost(),
                    proxy.getPort(),
                    proxy.isEnabled(),
                    800L,
                    0,
                    false
            );
        }

        public static Options parse(String[] args) {
            Options defaults = defaults();
            String dbPath = defaults.dbPath;
            String proxyHost = defaults.proxyHost;
            int proxyPort = defaults.proxyPort;
            boolean useProxy = defaults.useProxy;
            long delayMs = defaults.delayMs;
            int limit = defaults.limit;
            boolean dryRun = defaults.dryRun;
            boolean alwaysNetwork = false;
            String rootFolder = defaults.rootFolder;

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
                    case "--always-network" -> alwaysNetwork = true;
                    case "--root-folder" -> rootFolder = requireValue(args, ++i, "--root-folder");
                    default -> throw new IllegalArgumentException(message("artworks-backfill.cli.error.unknown-option", args[i]));
                }
            }
            return new Options(dbPath, proxyHost, proxyPort, useProxy, delayMs, limit, dryRun)
                    .withSources(rootFolder, alwaysNetwork);
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
                          boolean rateLimited,
                          int filledUploadTime) {}

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
