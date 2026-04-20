package top.sywyar.pixivdownload.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.util.Timeout;
import org.sqlite.SQLiteConfig;
import top.sywyar.pixivdownload.config.RuntimeFiles;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 综合回填工具：一次 Pixiv AJAX 请求同时补全 artworks 表的 {@code author_id}、{@code "R18"}、
 * {@code is_ai}、{@code description} 四个字段，以及 {@code tags} / {@code artwork_tags} 关系表。
 *
 * <p>取代旧的 {@link AuthorBackfill} / {@link R18Backfill}：每条作品只请求一次
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

    private static final String[] R18_KEYWORDS = {
            "R-18", "R18", "年齢制限", "年龄限制", "閲覧制限", "18歳未満",
            "成人向け", "成人向", "restricted", "age"
    };

    private static final String[] DELETED_KEYWORDS = {
            "削除", "存在しない", "not found", "该作品", "不存在", "已删除"
    };

    public static void main(String[] args) throws Exception {
        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException e) {
            printUsage();
            return;
        }
        run(options);
    }

    public static int countCandidates(Options options) throws Exception {
        SQLiteConfig sqliteConfig = new SQLiteConfig();
        sqliteConfig.setBusyTimeout(5000);
        sqliteConfig.setJournalMode(SQLiteConfig.JournalMode.WAL);

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + options.dbPath(), sqliteConfig.toProperties())) {
            ensureSchema(conn);
            return countCandidates(conn, options.limit());
        }
    }

    public static Summary run(Options options) throws Exception {
        log.info("回填开始: db={} 代理={} 延迟={}ms 限制={} 试运行={}",
                options.dbPath(),
                options.useProxy() ? options.proxyHost() + ":" + options.proxyPort() : "无",
                options.delayMs(),
                options.limit() > 0 ? options.limit() : "ALL",
                options.dryRun());
        log.info("提示：运行 ArtworksBackFill 前请先停止后端服务，避免与 SQLite 同时写入。");

        SQLiteConfig sqliteConfig = new SQLiteConfig();
        sqliteConfig.setBusyTimeout(5000);
        sqliteConfig.setJournalMode(SQLiteConfig.JournalMode.WAL);

        try (CloseableHttpClient http = buildHttpClient(options);
             Connection conn = DriverManager.getConnection("jdbc:sqlite:" + options.dbPath(), sqliteConfig.toProperties())) {

            ensureSchema(conn);
            List<Candidate> candidates = findCandidates(conn, options.limit());
            log.info("共 {} 条记录需要补全", candidates.size());
            if (candidates.isEmpty()) {
                Summary summary = new Summary(0, 0, 0, 0, 0, 0, 0, 0, 0, options.dryRun(), false);
                logSummary(summary);
                return summary;
            }

            ObjectMapper mapper = new ObjectMapper();
            int filledAuthor = 0;
            int filledR18 = 0;
            int filledAi = 0;
            int filledDescription = 0;
            int filledTags = 0;
            int deletedCount = 0;
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

                        List<String> changes = new ArrayList<>();
                        if (didAuthor) {
                            changes.add("author=" + result.authorName + "(#" + result.authorId + ")");
                        }
                        if (didR18) {
                            changes.add("R18=" + result.xRestrict);
                        }
                        if (didAi) {
                            changes.add("AI=" + (result.isAi ? 1 : 0));
                        }
                        if (didDesc) {
                            changes.add("desc=" + result.description.length() + "字符");
                        }
                        if (didTags) {
                            changes.add("tags=" + result.tags.size() + "个");
                        }

                        if (changes.isEmpty()) {
                            log.info("{} 无可补全数据", prefix);
                            skipped++;
                        } else {
                            log.info("{} {}", prefix, String.join(", ", changes));
                            if (!options.dryRun()) {
                                applyUpdates(conn, candidate, result, didAuthor, didR18, didAi, didDesc, didTags);
                            }
                            if (didAuthor) filledAuthor++;
                            if (didR18) filledR18++;
                            if (didAi) filledAi++;
                            if (didDesc) filledDescription++;
                            if (didTags) filledTags++;
                        }
                    }
                    case R18_ONLY -> {
                        if (candidate.r18Missing) {
                            log.info("{} R18 (via error msg: {})", prefix, result.message);
                            filledR18++;
                            if (!options.dryRun()) {
                                applyR18Only(conn, candidate.artworkId);
                            }
                        } else {
                            log.info("{} 跳过 ({}，R18 已有值)", prefix, result.message);
                            skipped++;
                        }
                    }
                    case DELETED -> {
                        log.info("{} 已删除/不可访问 — 跳过 ({})", prefix, result.message);
                        deletedCount++;
                    }
                    case SKIP -> {
                        log.info("{} 跳过 ({})", prefix, result.message);
                        skipped++;
                    }
                    case RATE_LIMITED -> {
                        log.warn("{} 触发限流（429），已停止", prefix);
                        log.info("已处理 {}/{} 条：author={}  R18={}  AI={}  desc={}  tags={}  已删除={}  跳过={}",
                                i, candidates.size(), filledAuthor, filledR18, filledAi, filledDescription, filledTags, deletedCount, skipped);
                        if (options.dryRun()) {
                            log.info("（试运行模式，未写入数据库）");
                        }
                        Summary summary = new Summary(
                                candidates.size(),
                                i,
                                filledAuthor,
                                filledR18,
                                filledAi,
                                filledDescription,
                                filledTags,
                                deletedCount,
                                skipped,
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

            Summary summary = new Summary(
                    candidates.size(),
                    candidates.size(),
                    filledAuthor,
                    filledR18,
                    filledAi,
                    filledDescription,
                    filledTags,
                    deletedCount,
                    skipped,
                    options.dryRun(),
                    false
            );
            logSummary(summary);
            return summary;
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
        log.info("完成：扫描={}  author={}  R18={}  AI={}  desc={}  tags={}  已删除={}  跳过={}",
                summary.totalCandidates(), summary.filledAuthor(), summary.filledR18(), summary.filledAi(),
                summary.filledDescription(), summary.filledTags(), summary.deletedCount(), summary.skipped());
        if (summary.dryRun()) {
            log.info("（试运行模式，未写入数据库）");
        }
    }

    private static String safeMessage(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static String describeMissing(Candidate c) {
        List<String> parts = new ArrayList<>(5);
        if (c.authorMissing) parts.add("author");
        if (c.r18Missing) parts.add("R18");
        if (c.aiMissing) parts.add("AI");
        if (c.descriptionMissing) parts.add("desc");
        if (c.tagsMissing) parts.add("tags");
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
        addColumnIfMissing(conn, "ALTER TABLE artworks ADD COLUMN author_id INTEGER DEFAULT NULL");
        addColumnIfMissing(conn, "ALTER TABLE artworks ADD COLUMN \"R18\" INTEGER DEFAULT NULL");
        addColumnIfMissing(conn, "ALTER TABLE artworks ADD COLUMN is_ai INTEGER DEFAULT NULL");
        addColumnIfMissing(conn, "ALTER TABLE artworks ADD COLUMN description TEXT DEFAULT NULL");
    }

    private static void addColumnIfMissing(Connection conn, String ddl) {
        try (PreparedStatement ps = conn.prepareStatement(ddl)) {
            ps.executeUpdate();
        } catch (SQLException ignored) {
            // 列已存在时直接忽略，行为与运行时迁移保持一致。
        }
    }

    private static int countCandidates(Connection conn, int limit) throws SQLException {
        String sql = "SELECT COUNT(*)"
                + " FROM artworks a"
                + " WHERE a.author_id IS NULL OR a.\"R18\" IS NULL OR a.is_ai IS NULL OR a.description IS NULL"
                + " OR NOT EXISTS (SELECT 1 FROM artwork_tags t WHERE t.artwork_id = a.artwork_id)";

        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            int total = rs.next() ? rs.getInt(1) : 0;
            return limit > 0 ? Math.min(total, limit) : total;
        }
    }

    private static List<Candidate> findCandidates(Connection conn, int limit) throws SQLException {
        String sql = "SELECT a.artwork_id, a.author_id, a.\"R18\", a.is_ai, a.description,"
                + " (SELECT 1 FROM artwork_tags t WHERE t.artwork_id = a.artwork_id LIMIT 1) AS has_tags"
                + " FROM artworks a"
                + " WHERE a.author_id IS NULL OR a.\"R18\" IS NULL OR a.is_ai IS NULL OR a.description IS NULL"
                + " OR NOT EXISTS (SELECT 1 FROM artwork_tags t WHERE t.artwork_id = a.artwork_id)"
                + " ORDER BY a.artwork_id";
        if (limit > 0) {
            sql += " LIMIT ?";
        }

        List<Candidate> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (limit > 0) {
                ps.setInt(1, limit);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong(1);
                    boolean authorMissing = rs.getObject(2) == null;
                    boolean r18Missing = rs.getObject(3) == null;
                    boolean aiMissing = rs.getObject(4) == null;
                    boolean descMissing = rs.getObject(5) == null;
                    boolean tagsMissing = rs.getObject(6) == null;
                    list.add(new Candidate(id, authorMissing, r18Missing, aiMissing, descMissing, tagsMissing));
                }
            }
        }
        return list;
    }

    private static LookupResult queryPixiv(CloseableHttpClient http, ObjectMapper mapper, long artworkId) {
        HttpGet request = new HttpGet(PIXIV_AJAX + artworkId);
        request.setHeader("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        request.setHeader("Referer", "https://www.pixiv.net/");
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
                    return LookupResult.skip("空响应");
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
                return LookupResult.found(authorId, authorName, xRestrict, isAi, description, tags);
            });
        } catch (Exception e) {
            return LookupResult.skip("请求异常: " + e.getMessage());
        }
    }

    private static void applyUpdates(Connection conn, Candidate c, LookupResult result,
                                     boolean updateAuthor, boolean updateR18, boolean updateAi,
                                     boolean updateDescription, boolean updateTags) throws SQLException {
        List<String> sets = new ArrayList<>(4);
        if (updateAuthor) sets.add("author_id = ?");
        if (updateR18) sets.add("\"R18\" = ?");
        if (updateAi) sets.add("is_ai = ?");
        if (updateDescription) sets.add("description = ?");

        if (!sets.isEmpty()) {
            String sql = "UPDATE artworks SET " + String.join(", ", sets) + " WHERE artwork_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                int idx = 1;
                if (updateAuthor) ps.setLong(idx++, result.authorId);
                if (updateR18) ps.setInt(idx++, result.xRestrict);
                if (updateAi) ps.setInt(idx++, result.isAi ? 1 : 0);
                if (updateDescription) ps.setString(idx++, result.description);
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

    private static void upsertAuthor(Connection conn, long authorId, String authorName) throws SQLException {
        long nowSeconds = Instant.now().getEpochSecond();
        try (PreparedStatement insertAuthor = conn.prepareStatement(
                "INSERT OR IGNORE INTO authors(author_id, name, updated_time) VALUES(?, ?, ?)");
             PreparedStatement updateAuthor = conn.prepareStatement(
                     "UPDATE authors SET name = ?, updated_time = ? WHERE author_id = ? AND name <> ?")) {
            insertAuthor.setLong(1, authorId);
            insertAuthor.setString(2, authorName);
            insertAuthor.setLong(3, nowSeconds);
            insertAuthor.executeUpdate();

            updateAuthor.setString(1, authorName);
            updateAuthor.setLong(2, nowSeconds);
            updateAuthor.setLong(3, authorId);
            updateAuthor.setString(4, authorName);
            updateAuthor.executeUpdate();
        }
    }

    private static void printUsage() {
        System.out.println("用法: ArtworksBackFill [--db <path>] [--proxy <host:port>] [--no-proxy] [--delay <ms>] [--limit <n>] [--dry-run]");
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
                            throw new IllegalArgumentException("Invalid proxy");
                        }
                        proxyHost = parts[0];
                        proxyPort = Integer.parseInt(parts[1]);
                    }
                    case "--no-proxy" -> useProxy = false;
                    case "--delay" -> delayMs = Long.parseLong(requireValue(args, ++i, "--delay"));
                    case "--limit" -> limit = Integer.parseInt(requireValue(args, ++i, "--limit"));
                    case "--dry-run" -> dryRun = true;
                    default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
                }
            }
            return new Options(dbPath, proxyHost, proxyPort, useProxy, delayMs, limit, dryRun);
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException("Missing value for " + option);
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
                          int deletedCount,
                          int skipped,
                          boolean dryRun,
                          boolean rateLimited) {}

    private static final class Candidate {
        final long artworkId;
        final boolean authorMissing;
        final boolean r18Missing;
        final boolean aiMissing;
        final boolean descriptionMissing;
        final boolean tagsMissing;

        private Candidate(long artworkId, boolean authorMissing, boolean r18Missing, boolean aiMissing,
                          boolean descriptionMissing, boolean tagsMissing) {
            this.artworkId = artworkId;
            this.authorMissing = authorMissing;
            this.r18Missing = r18Missing;
            this.aiMissing = aiMissing;
            this.descriptionMissing = descriptionMissing;
            this.tagsMissing = tagsMissing;
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
        final String message;

        private LookupResult(ResultType type, long authorId, String authorName, int xRestrict, boolean isAi,
                             String description, List<TagEntry> tags, String message) {
            this.type = type;
            this.authorId = authorId;
            this.authorName = authorName;
            this.xRestrict = xRestrict;
            this.isAi = isAi;
            this.description = description;
            this.tags = tags;
            this.message = message;
        }

        static LookupResult found(long authorId, String authorName, int xRestrict, boolean isAi,
                                  String description, List<TagEntry> tags) {
            return new LookupResult(ResultType.FOUND, authorId, authorName, xRestrict, isAi, description, tags, null);
        }

        static LookupResult r18Only(String message) {
            return new LookupResult(ResultType.R18_ONLY, 0, null, 1, false, null, null, message);
        }

        static LookupResult deleted(String message) {
            return new LookupResult(ResultType.DELETED, 0, null, 0, false, null, null, message);
        }

        static LookupResult skip(String message) {
            return new LookupResult(ResultType.SKIP, 0, null, 0, false, null, null, message);
        }

        static LookupResult rateLimited() {
            return new LookupResult(ResultType.RATE_LIMITED, 0, null, 0, false, null, null, "HTTP 429");
        }
    }

    private record TagEntry(String name, String translatedName) {}
}
