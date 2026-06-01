package top.sywyar.pixivdownload.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.util.Timeout;
import org.sqlite.SQLiteConfig;
import top.sywyar.pixivdownload.common.PixivRequestHeaders;
import top.sywyar.pixivdownload.common.Utf8ConsoleStreams;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.i18n.MessageBundles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    private static final String PIXIV_AJAX = "https://www.pixiv.net/ajax/illust/";

    public static final Set<DatabaseColumn> SUPPORTED_DATABASE_COLUMNS = Set.of(
            new DatabaseColumn("artworks", "author_id"),
            new DatabaseColumn("artworks", "R18"),
            new DatabaseColumn("artworks", "is_ai"),
            new DatabaseColumn("artworks", "description"),
            new DatabaseColumn("artworks", "series_id"),
            new DatabaseColumn("artworks", "series_order")
    );

    private static final String[] R18_KEYWORDS = {
            "R-18", "R18", "年齢制限", "年龄限制", "閲覧制限", "18歳未満",
            "成人向け", "成人向", "restricted", "age"
    };

    private static final String[] DELETED_KEYWORDS = {
            "削除", "存在しない", "not found", "该作品", "不存在", "已删除"
    };

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
        SQLiteConfig sqliteConfig = new SQLiteConfig();
        sqliteConfig.setBusyTimeout(5000);
        sqliteConfig.setJournalMode(SQLiteConfig.JournalMode.WAL);

        UnreachableStore store = UnreachableStore.load(RuntimeFiles.resolveBackfillUnreachablePath(), new ObjectMapper());
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + options.dbPath(), sqliteConfig.toProperties())) {
            ensureSchema(conn);
            return countCandidates(conn, options.limit(), store);
        }
    }

    public static Summary run(Options options) throws Exception {
        log.info(message(
                "artworks-backfill.log.started",
                options.dbPath(),
                options.useProxy()
                        ? options.proxyHost() + ":" + options.proxyPort()
                        : message("artworks-backfill.option.proxy.none"),
                options.delayMs(),
                options.limit() > 0 ? options.limit() : message("artworks-backfill.option.limit.all"),
                options.dryRun()
        ));
        log.info(message("artworks-backfill.log.stop-backend-hint"));

        SQLiteConfig sqliteConfig = new SQLiteConfig();
        sqliteConfig.setBusyTimeout(5000);
        sqliteConfig.setJournalMode(SQLiteConfig.JournalMode.WAL);

        ObjectMapper mapper = new ObjectMapper();
        Path unreachablePath = RuntimeFiles.resolveBackfillUnreachablePath();
        UnreachableStore unreachable = UnreachableStore.load(unreachablePath, mapper);
        log.info(message("artworks-backfill.unreachable.loaded", unreachablePath, unreachable.size()));

        try (CloseableHttpClient http = buildHttpClient(options);
             Connection conn = DriverManager.getConnection("jdbc:sqlite:" + options.dbPath(), sqliteConfig.toProperties())) {

            ensureSchema(conn);
            FilteredCandidates filtered = findCandidates(conn, options.limit(), unreachable);
            List<Candidate> candidates = filtered.candidates();
            int previouslyUnreachable = filtered.skippedUnreachable();
            if (previouslyUnreachable > 0) {
                log.info(message("artworks-backfill.unreachable.skipped-existing", previouslyUnreachable));
            }
            log.info(message("artworks-backfill.log.candidates.count", candidates.size()));
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
                Candidate candidate = candidates.get(i);
                LookupResult result = queryPixiv(http, mapper, candidate.artworkId);
                String prefix = "[" + (i + 1) + "/" + candidates.size() + "] artwork="
                        + candidate.artworkId + " missing=[" + describeMissing(candidate) + "]";

                switch (result.type) {
                    case FOUND -> {
                        boolean didAuthor = candidate.authorMissing && result.authorId > 0;
                        boolean didR18 = candidate.r18Missing;
                        boolean didAi = candidate.aiMissing;
                        boolean didDesc = candidate.descriptionMissing && result.description != null;
                        boolean didTags = candidate.tagsMissing && result.tags != null;
                        boolean didSeries = candidate.seriesMissing;

                        List<String> changes = new ArrayList<>();
                        if (didAuthor) {
                            changes.add(message("artworks-backfill.log.change.author", result.authorName, result.authorId));
                        }
                        if (didR18) {
                            changes.add(message("artworks-backfill.log.change.r18", result.xRestrict));
                        }
                        if (didAi) {
                            changes.add(message("artworks-backfill.log.change.ai", result.isAi ? 1 : 0));
                        }
                        if (didDesc) {
                            changes.add(message("artworks-backfill.log.change.description", result.description.length()));
                        }
                        if (didTags) {
                            changes.add(message("artworks-backfill.log.change.tags", result.tags.size()));
                        }
                        if (didSeries) {
                            changes.add(message("artworks-backfill.log.change.series", result.seriesId, result.seriesOrder));
                        }

                        if (changes.isEmpty()) {
                            log.info(message("artworks-backfill.log.no-fillable-data", prefix));
                            skipped++;
                        } else {
                            log.info(message("artworks-backfill.log.changes", prefix, String.join(", ", changes)));
                            if (!options.dryRun()) {
                                applyUpdates(conn, candidate, result, didAuthor, didR18, didAi, didDesc, didTags, didSeries);
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
                        if (candidate.r18Missing) {
                            log.info(message("artworks-backfill.log.r18-only", prefix, result.message));
                            filledR18++;
                            if (!options.dryRun()) {
                                applyR18Only(conn, candidate.artworkId);
                            }
                        } else {
                            log.info(message("artworks-backfill.log.skip.r18-already-filled", prefix, result.message));
                            skipped++;
                        }
                    }
                    case DELETED -> {
                        log.info(message("artworks-backfill.log.deleted-skip", prefix, result.message));
                        deletedCount++;
                        boolean alreadyKnown = unreachable.contains(candidate.artworkId);
                        unreachable.record(candidate.artworkId, result.message);
                        if (!alreadyKnown) {
                            newlyUnreachable++;
                        }
                        persistUnreachable(unreachable, unreachablePath);
                    }
                    case SKIP -> {
                        log.info(message("artworks-backfill.log.skip", prefix, result.message));
                        skipped++;
                    }
                    case RATE_LIMITED -> {
                        log.warn(message("artworks-backfill.log.rate-limited", prefix));
                        log.info(message(
                                "artworks-backfill.log.progress",
                                i, candidates.size(), filledAuthor, filledR18, filledAi, filledDescription, filledTags, filledSeries, deletedCount, skipped
                        ));
                        if (options.dryRun()) {
                            log.info(message("artworks-backfill.log.dry-run"));
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

    private static void persistUnreachable(UnreachableStore store, Path path) {
        try {
            store.save();
        } catch (IOException e) {
            log.warn(message("artworks-backfill.unreachable.save-failed", path, e.getMessage()));
        }
    }

    private static CloseableHttpClient buildHttpClient(Options options) {
        RequestConfig reqConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofSeconds(15))
                .setResponseTimeout(Timeout.ofSeconds(15))
                .setConnectionRequestTimeout(Timeout.ofSeconds(5))
                .build();

        var clientBuilder = HttpClients.custom()
                .setDefaultRequestConfig(reqConfig)
                .disableCookieManagement();
        if (options.useProxy()) {
            clientBuilder.setProxy(new HttpHost("http", options.proxyHost(), options.proxyPort()));
        }
        return clientBuilder.build();
    }

    private static void logSummary(Summary summary) {
        log.info(message(
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
            log.info(message("artworks-backfill.log.dry-run"));
        }
    }

    private static String safeMessage(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static String describeMissing(Candidate c) {
        List<String> parts = new ArrayList<>(6);
        if (c.authorMissing) parts.add("author");
        if (c.r18Missing) parts.add("R18");
        if (c.aiMissing) parts.add("AI");
        if (c.descriptionMissing) parts.add("desc");
        if (c.tagsMissing) parts.add("tags");
        if (c.seriesMissing) parts.add("series");
        return String.join("+", parts);
    }

    private static void ensureSchema(Connection conn) throws SQLException {
        try (PreparedStatement createAuthors = conn.prepareStatement(
                "CREATE TABLE IF NOT EXISTS authors ("
                        + "author_id INTEGER PRIMARY KEY,"
                        + "name TEXT NOT NULL,"
                        + "updated_time INTEGER NOT NULL)")) {
            createAuthors.executeUpdate();
        }
        try (PreparedStatement createTags = conn.prepareStatement(
                "CREATE TABLE IF NOT EXISTS tags ("
                        + "tag_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "name TEXT NOT NULL UNIQUE,"
                        + "translated_name TEXT)")) {
            createTags.executeUpdate();
        }
        try (PreparedStatement createArtworkTags = conn.prepareStatement(
                "CREATE TABLE IF NOT EXISTS artwork_tags ("
                        + "artwork_id INTEGER NOT NULL,"
                        + "tag_id INTEGER NOT NULL,"
                        + "PRIMARY KEY (artwork_id, tag_id))")) {
            createArtworkTags.executeUpdate();
        }
        try (PreparedStatement createIndex = conn.prepareStatement(
                "CREATE INDEX IF NOT EXISTS idx_artwork_tags_tag_id ON artwork_tags(tag_id)")) {
            createIndex.executeUpdate();
        }
        try (PreparedStatement createSeries = conn.prepareStatement(
                "CREATE TABLE IF NOT EXISTS manga_series ("
                        + "series_id INTEGER PRIMARY KEY,"
                        + "title TEXT NOT NULL,"
                        + "author_id INTEGER,"
                        + "updated_time INTEGER NOT NULL)")) {
            createSeries.executeUpdate();
        }
        addColumnIfMissing(conn, "ALTER TABLE artworks ADD COLUMN author_id INTEGER DEFAULT NULL");
        addColumnIfMissing(conn, "ALTER TABLE artworks ADD COLUMN \"R18\" INTEGER DEFAULT NULL");
        addColumnIfMissing(conn, "ALTER TABLE artworks ADD COLUMN is_ai INTEGER DEFAULT NULL");
        addColumnIfMissing(conn, "ALTER TABLE artworks ADD COLUMN description TEXT DEFAULT NULL");
        addColumnIfMissing(conn, "ALTER TABLE artworks ADD COLUMN series_id INTEGER DEFAULT NULL");
        addColumnIfMissing(conn, "ALTER TABLE artworks ADD COLUMN series_order INTEGER DEFAULT NULL");
    }

    private static void addColumnIfMissing(Connection conn, String ddl) {
        try (PreparedStatement ps = conn.prepareStatement(ddl)) {
            ps.executeUpdate();
        } catch (SQLException ignored) {
            // 列已存在时直接忽略，行为与运行时迁移保持一致。
        }
    }

    private static int countCandidates(Connection conn, int limit, UnreachableStore unreachable) throws SQLException {
        return findCandidates(conn, limit, unreachable).candidates().size();
    }

    private static FilteredCandidates findCandidates(Connection conn, int limit, UnreachableStore unreachable) throws SQLException {
        // 在内存中过滤已知不可达 ID，避免 SQL IN 受 SQLite 参数上限约束；为了在 limit 截断前剔除它们，这里不再下推 LIMIT。
        String sql = "SELECT a.artwork_id, a.author_id, a.\"R18\", a.is_ai, a.description,"
                + " a.series_id,"
                + " (SELECT 1 FROM artwork_tags t WHERE t.artwork_id = a.artwork_id LIMIT 1) AS has_tags"
                + " FROM artworks a"
                + " WHERE a.author_id IS NULL OR a.\"R18\" IS NULL OR a.is_ai IS NULL OR a.description IS NULL"
                + " OR a.series_id IS NULL"
                + " OR NOT EXISTS (SELECT 1 FROM artwork_tags t WHERE t.artwork_id = a.artwork_id)"
                + " ORDER BY a.artwork_id";

        List<Candidate> list = new ArrayList<>();
        int skippedUnreachable = 0;
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long id = rs.getLong(1);
                if (unreachable.contains(id)) {
                    skippedUnreachable++;
                    continue;
                }
                boolean authorMissing = rs.getObject(2) == null;
                boolean r18Missing = rs.getObject(3) == null;
                boolean aiMissing = rs.getObject(4) == null;
                boolean descMissing = rs.getObject(5) == null;
                boolean seriesMissing = rs.getObject(6) == null;
                boolean tagsMissing = rs.getObject(7) == null;
                list.add(new Candidate(id, authorMissing, r18Missing, aiMissing, descMissing, tagsMissing, seriesMissing));
                if (limit > 0 && list.size() >= limit) {
                    break;
                }
            }
        }
        return new FilteredCandidates(list, skippedUnreachable);
    }

    private record FilteredCandidates(List<Candidate> candidates, int skippedUnreachable) {}

    private static LookupResult queryPixiv(CloseableHttpClient http, ObjectMapper mapper, long artworkId) {
        HttpGet request = new HttpGet(PIXIV_AJAX + artworkId);
        request.setHeader("User-Agent", PixivRequestHeaders.USER_AGENT);
        request.setHeader("Referer", PixivRequestHeaders.PIXIV_HOME);
        request.setHeader("Accept-Language", "ja,en;q=0.9");

        try {
            return http.execute(request, response -> {
                int status = response.getCode();
                String body = EntityUtils.toString(response.getEntity(), "UTF-8");

                if (status == 429) {
                    return LookupResult.rateLimited();
                }
                if (status == 404) {
                    return LookupResult.deleted("HTTP 404");
                }

                JsonNode root = mapper.readTree(body);
                if (root == null) {
                    return LookupResult.skip(message("artworks-backfill.lookup.empty-response"));
                }
                if (root.path("error").asBoolean(false)) {
                    String message = root.path("message").asText("pixiv ajax error");
                    String lower = message.toLowerCase();
                    for (String keyword : R18_KEYWORDS) {
                        if (lower.contains(keyword.toLowerCase())) {
                            return LookupResult.r18Only(message);
                        }
                    }
                    for (String keyword : DELETED_KEYWORDS) {
                        if (lower.contains(keyword.toLowerCase())) {
                            return LookupResult.deleted(message);
                        }
                    }
                    return LookupResult.skip(message);
                }

                JsonNode payload = root.path("body");
                long authorId = payload.path("userId").asLong(0);
                String authorName = payload.path("userName").asText("").trim();
                if (authorId > 0 && authorName.isEmpty()) {
                    authorName = String.valueOf(authorId);
                }
                int xRestrict = payload.path("xRestrict").asInt(0);
                boolean isAi = payload.path("aiType").asInt(0) >= 2;
                String description = payload.path("description").asText("");
                List<TagEntry> tags = extractTags(payload);
                long seriesId = 0;
                long seriesOrder = 0;
                String seriesTitle = null;
                JsonNode nav = payload.path("seriesNavData");
                if (nav.isObject()) {
                    long sid = nav.path("seriesId").asLong(0);
                    if (sid > 0) {
                        seriesId = sid;
                        seriesOrder = nav.path("order").asLong(0);
                        seriesTitle = nav.path("title").asText("").trim();
                        if (seriesTitle.isEmpty()) seriesTitle = String.valueOf(seriesId);
                    }
                }
                return LookupResult.found(authorId, authorName, xRestrict, isAi, description, tags,
                        seriesId, seriesOrder, seriesTitle);
            });
        } catch (Exception e) {
            return LookupResult.skip(message("artworks-backfill.lookup.request-error", e.getMessage()));
        }
    }

    private static void applyUpdates(Connection conn, Candidate c, LookupResult result,
                                     boolean updateAuthor, boolean updateR18, boolean updateAi,
                                     boolean updateDescription, boolean updateTags,
                                     boolean updateSeries) throws SQLException {
        List<String> sets = new ArrayList<>(6);
        if (updateAuthor) sets.add("author_id = ?");
        if (updateR18) sets.add("\"R18\" = ?");
        if (updateAi) sets.add("is_ai = ?");
        if (updateDescription) sets.add("description = ?");
        if (updateSeries) {
            sets.add("series_id = ?");
            sets.add("series_order = ?");
        }

        if (!sets.isEmpty()) {
            String sql = "UPDATE artworks SET " + String.join(", ", sets) + " WHERE artwork_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                int idx = 1;
                if (updateAuthor) ps.setLong(idx++, result.authorId);
                if (updateR18) ps.setInt(idx++, result.xRestrict);
                if (updateAi) ps.setInt(idx++, result.isAi ? 1 : 0);
                if (updateDescription) ps.setString(idx++, result.description);
                if (updateSeries) {
                    ps.setLong(idx++, result.seriesId);
                    ps.setLong(idx++, result.seriesOrder);
                }
                ps.setLong(idx, c.artworkId);
                ps.executeUpdate();
            }
        }

        if (updateAuthor && result.authorId > 0) {
            upsertAuthor(conn, result.authorId, result.authorName);
        }

        if (updateTags && result.tags != null && !result.tags.isEmpty()) {
            saveTags(conn, c.artworkId, result.tags);
        }

        if (updateSeries && result.seriesId > 0 && result.seriesTitle != null) {
            upsertSeries(conn, result.seriesId, result.seriesTitle,
                    result.authorId > 0 ? result.authorId : null);
        }
    }

    private static List<TagEntry> extractTags(JsonNode payload) {
        JsonNode tagsArr = payload.path("tags").path("tags");
        if (!tagsArr.isArray() || tagsArr.isEmpty()) {
            return List.of();
        }

        List<TagEntry> out = new ArrayList<>();
        for (JsonNode t : tagsArr) {
            String tag = t.path("tag").asText("");
            if (tag.isEmpty()) {
                continue;
            }
            String translated = null;
            JsonNode translation = t.path("translation");
            if (translation.isObject()) {
                String en = translation.path("en").asText("");
                if (!en.isEmpty()) {
                    translated = en;
                }
            }
            out.add(new TagEntry(tag, translated));
        }
        return out;
    }

    private static void saveTags(Connection conn, long artworkId, List<TagEntry> tags) throws SQLException {
        try (PreparedStatement upsertTag = conn.prepareStatement(
                "INSERT INTO tags(name, translated_name) VALUES(?, ?)"
                        + " ON CONFLICT(name) DO UPDATE SET"
                        + " translated_name = COALESCE(tags.translated_name, excluded.translated_name)");
             PreparedStatement selectTag = conn.prepareStatement(
                     "SELECT tag_id FROM tags WHERE name = ?");
             PreparedStatement linkTag = conn.prepareStatement(
                     "INSERT OR IGNORE INTO artwork_tags(artwork_id, tag_id) VALUES(?, ?)")) {
            for (TagEntry tag : tags) {
                upsertTag.setString(1, tag.name);
                if (tag.translatedName == null) {
                    upsertTag.setNull(2, java.sql.Types.VARCHAR);
                } else {
                    upsertTag.setString(2, tag.translatedName);
                }
                upsertTag.executeUpdate();

                selectTag.setString(1, tag.name);
                try (ResultSet rs = selectTag.executeQuery()) {
                    if (!rs.next()) {
                        continue;
                    }
                    long tagId = rs.getLong(1);
                    linkTag.setLong(1, artworkId);
                    linkTag.setLong(2, tagId);
                    linkTag.executeUpdate();
                }
            }
        }
    }

    private static void applyR18Only(Connection conn, long artworkId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE artworks SET \"R18\" = 1 WHERE artwork_id = ?")) {
            ps.setLong(1, artworkId);
            ps.executeUpdate();
        }
    }

    private static void upsertSeries(Connection conn, long seriesId, String title, Long authorId) throws SQLException {
        long nowMillis = System.currentTimeMillis();
        // 与 MangaSeriesService.observe 对齐：title 或 author 任一变化都触发 update。
        // 之前 WHERE 含 `AND title <> ?` 会让仅 author 变化的场景无更新，导致回填工具落后于运行时。
        try (PreparedStatement insertSeries = conn.prepareStatement(
                "INSERT OR IGNORE INTO manga_series(series_id, title, author_id, updated_time) VALUES(?, ?, ?, ?)");
             PreparedStatement updateSeries = conn.prepareStatement(
                     "UPDATE manga_series SET title = ?, author_id = COALESCE(?, author_id),"
                             + " updated_time = ? WHERE series_id = ?"
                             + " AND (title <> ? OR (? IS NOT NULL AND (author_id IS NULL OR author_id <> ?)))")) {
            insertSeries.setLong(1, seriesId);
            insertSeries.setString(2, title);
            if (authorId == null) {
                insertSeries.setNull(3, java.sql.Types.INTEGER);
            } else {
                insertSeries.setLong(3, authorId);
            }
            insertSeries.setLong(4, nowMillis);
            insertSeries.executeUpdate();

            updateSeries.setString(1, title);
            if (authorId == null) {
                updateSeries.setNull(2, java.sql.Types.INTEGER);
                updateSeries.setNull(6, java.sql.Types.INTEGER);
                updateSeries.setNull(7, java.sql.Types.INTEGER);
            } else {
                updateSeries.setLong(2, authorId);
                updateSeries.setLong(6, authorId);
                updateSeries.setLong(7, authorId);
            }
            updateSeries.setLong(3, nowMillis);
            updateSeries.setLong(4, seriesId);
            updateSeries.setString(5, title);
            updateSeries.executeUpdate();
        }
    }

    private static void upsertAuthor(Connection conn, long authorId, String authorName) throws SQLException {
        long nowMillis = System.currentTimeMillis();
        try (PreparedStatement insertAuthor = conn.prepareStatement(
                "INSERT OR IGNORE INTO authors(author_id, name, updated_time) VALUES(?, ?, ?)");
             PreparedStatement updateAuthor = conn.prepareStatement(
                     "UPDATE authors SET name = ?, updated_time = ? WHERE author_id = ? AND name <> ?")) {
            insertAuthor.setLong(1, authorId);
            insertAuthor.setString(2, authorName);
            insertAuthor.setLong(3, nowMillis);
            insertAuthor.executeUpdate();

            updateAuthor.setString(1, authorName);
            updateAuthor.setLong(2, nowMillis);
            updateAuthor.setLong(3, authorId);
            updateAuthor.setString(4, authorName);
            updateAuthor.executeUpdate();
        }
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

    private static final class Candidate {
        final long artworkId;
        final boolean authorMissing;
        final boolean r18Missing;
        final boolean aiMissing;
        final boolean descriptionMissing;
        final boolean tagsMissing;
        final boolean seriesMissing;

        private Candidate(long artworkId, boolean authorMissing, boolean r18Missing, boolean aiMissing,
                          boolean descriptionMissing, boolean tagsMissing, boolean seriesMissing) {
            this.artworkId = artworkId;
            this.authorMissing = authorMissing;
            this.r18Missing = r18Missing;
            this.aiMissing = aiMissing;
            this.descriptionMissing = descriptionMissing;
            this.tagsMissing = tagsMissing;
            this.seriesMissing = seriesMissing;
        }
    }

    private enum ResultType {
        FOUND,
        R18_ONLY,
        DELETED,
        SKIP,
        RATE_LIMITED
    }

    private static final class LookupResult {
        final ResultType type;
        final long authorId;
        final String authorName;
        final int xRestrict;
        final boolean isAi;
        final String description;
        final List<TagEntry> tags;
        final long seriesId;
        final long seriesOrder;
        final String seriesTitle;
        final String message;

        private LookupResult(ResultType type, long authorId, String authorName, int xRestrict, boolean isAi,
                             String description, List<TagEntry> tags,
                             long seriesId, long seriesOrder, String seriesTitle,
                             String message) {
            this.type = type;
            this.authorId = authorId;
            this.authorName = authorName;
            this.xRestrict = xRestrict;
            this.isAi = isAi;
            this.description = description;
            this.tags = tags;
            this.seriesId = seriesId;
            this.seriesOrder = seriesOrder;
            this.seriesTitle = seriesTitle;
            this.message = message;
        }

        static LookupResult found(long authorId, String authorName, int xRestrict, boolean isAi,
                                  String description, List<TagEntry> tags,
                                  long seriesId, long seriesOrder, String seriesTitle) {
            return new LookupResult(ResultType.FOUND, authorId, authorName, xRestrict, isAi, description, tags,
                    seriesId, seriesOrder, seriesTitle, null);
        }

        static LookupResult r18Only(String message) {
            return new LookupResult(ResultType.R18_ONLY, 0, null, 1, false, null, null, 0, 0, null, message);
        }

        static LookupResult deleted(String message) {
            return new LookupResult(ResultType.DELETED, 0, null, 0, false, null, null, 0, 0, null, message);
        }

        static LookupResult skip(String message) {
            return new LookupResult(ResultType.SKIP, 0, null, 0, false, null, null, 0, 0, null, message);
        }

        static LookupResult rateLimited() {
            return new LookupResult(ResultType.RATE_LIMITED, 0, null, 0, false, null, null, 0, 0, null, "HTTP 429");
        }
    }

    private record TagEntry(String name, String translatedName) {}

    private static String message(String code, Object... args) {
        return MessageBundles.get(code, args);
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

    /**
     * 本地不可达作品缓存：记录 Pixiv AJAX 返回 DELETED / 404 等已知不可达响应的作品 ID，
     * 后续运行直接跳过，避免重复消耗请求量。文件位置由 {@link RuntimeFiles#resolveBackfillUnreachablePath()} 决定。
     */
    private static final class UnreachableStore {
        private final Path path;
        private final ObjectMapper mapper;
        private final Map<Long, UnreachableEntry> entries = new LinkedHashMap<>();
        private boolean dirty;

        private UnreachableStore(Path path, ObjectMapper mapper) {
            this.path = path;
            this.mapper = mapper;
        }

        static UnreachableStore load(Path path, ObjectMapper mapper) {
            UnreachableStore store = new UnreachableStore(path, mapper);
            if (path == null || !Files.isRegularFile(path)) {
                return store;
            }
            try {
                byte[] bytes = Files.readAllBytes(path);
                if (bytes.length == 0) {
                    return store;
                }
                JsonNode root = mapper.readTree(bytes);
                if (root == null || !root.isObject()) {
                    return store;
                }
                Iterator<Map.Entry<String, JsonNode>> it = root.fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> field = it.next();
                    long id;
                    try {
                        id = Long.parseLong(field.getKey());
                    } catch (NumberFormatException ignored) {
                        continue;
                    }
                    JsonNode value = field.getValue();
                    String reason = value.path("reason").asText("");
                    long firstSeenAt = value.path("firstSeenAt").asLong(0L);
                    long lastSeenAt = value.path("lastSeenAt").asLong(firstSeenAt);
                    int attempts = value.path("attempts").asInt(1);
                    store.entries.put(id, new UnreachableEntry(reason, firstSeenAt, lastSeenAt, attempts));
                }
            } catch (IOException e) {
                log.warn(message("artworks-backfill.unreachable.load-failed", path, e.getMessage()));
            }
            return store;
        }

        boolean contains(long artworkId) {
            return entries.containsKey(artworkId);
        }

        int size() {
            return entries.size();
        }

        void record(long artworkId, String reason) {
            long now = System.currentTimeMillis();
            UnreachableEntry existing = entries.get(artworkId);
            String effectiveReason = reason == null || reason.isBlank()
                    ? (existing != null ? existing.reason() : "")
                    : reason;
            if (existing == null) {
                entries.put(artworkId, new UnreachableEntry(effectiveReason, now, now, 1));
            } else {
                entries.put(artworkId, new UnreachableEntry(
                        effectiveReason,
                        existing.firstSeenAt() > 0 ? existing.firstSeenAt() : now,
                        now,
                        existing.attempts() + 1));
            }
            dirty = true;
        }

        void save() throws IOException {
            if (!dirty) {
                return;
            }
            Files.createDirectories(path.getParent());
            ObjectNode root = mapper.createObjectNode();
            List<Long> sortedIds = new ArrayList<>(entries.keySet());
            Collections.sort(sortedIds);
            for (Long id : sortedIds) {
                UnreachableEntry entry = entries.get(id);
                ObjectNode obj = root.putObject(String.valueOf(id));
                obj.put("reason", entry.reason());
                obj.put("firstSeenAt", entry.firstSeenAt());
                obj.put("lastSeenAt", entry.lastSeenAt());
                obj.put("attempts", entry.attempts());
            }
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), root);
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailure) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
        }
    }

    private record UnreachableEntry(String reason, long firstSeenAt, long lastSeenAt, int attempts) {}
}
