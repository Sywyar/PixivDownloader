package top.sywyar.pixivdownload.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 综合回填工具：一次 Pixiv AJAX 请求同时补全 artworks 表的
 * {@code author_id}、{@code "R18"}、{@code description}、{@code tags} 四个字段。
 *
 * <p>取代旧的 {@link AuthorBackfill} / {@link R18Backfill}：每条作品只请求一次
 * {@code /ajax/illust/{id}}，根据响应内容选择性地更新当前仍为 NULL 的列；
 * 若 author_id 被补全，则同步维护 {@code authors} 表。
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
        String dbPath = RuntimeFiles.dataDirectory().resolve(RuntimeFiles.PIXIV_DOWNLOAD_DB).toString();
        String proxyHost = "127.0.0.1";
        int proxyPort = 7890;
        boolean useProxy = true;
        long delayMs = 800;
        int limit = 0;
        boolean dryRun = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--db" -> dbPath = args[++i];
                case "--proxy" -> {
                    String[] parts = args[++i].split(":");
                    proxyHost = parts[0];
                    proxyPort = Integer.parseInt(parts[1]);
                }
                case "--no-proxy" -> useProxy = false;
                case "--delay" -> delayMs = Long.parseLong(args[++i]);
                case "--limit" -> limit = Integer.parseInt(args[++i]);
                case "--dry-run" -> dryRun = true;
                default -> {
                    printUsage();
                    return;
                }
            }
        }

        System.out.printf("DB: %s | 代理: %s | 延迟: %dms | 限制: %s | 试运行: %s%n",
                dbPath,
                useProxy ? proxyHost + ":" + proxyPort : "无",
                delayMs,
                limit > 0 ? limit : "ALL",
                dryRun);
        System.out.println("提示：运行 ArtworksBackFill 前请先停止后端服务，避免与 SQLite 同时写入。");

        SQLiteConfig sqliteConfig = new SQLiteConfig();
        sqliteConfig.setBusyTimeout(5000);
        sqliteConfig.setJournalMode(SQLiteConfig.JournalMode.WAL);
        String jdbcUrl = "jdbc:sqlite:" + dbPath;

        RequestConfig reqConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofSeconds(15))
                .setResponseTimeout(Timeout.ofSeconds(15))
                .setConnectionRequestTimeout(Timeout.ofSeconds(5))
                .build();
        var clientBuilder = HttpClients.custom()
                .setDefaultRequestConfig(reqConfig)
                .disableCookieManagement();
        if (useProxy) {
            clientBuilder.setProxy(new HttpHost("http", proxyHost, proxyPort));
        }

        try (CloseableHttpClient http = clientBuilder.build();
             Connection conn = DriverManager.getConnection(jdbcUrl, sqliteConfig.toProperties())) {

            ensureSchema(conn);
            List<Candidate> candidates = findCandidates(conn, limit);
            System.out.printf("共 %d 条记录需要补全%n", candidates.size());
            if (candidates.isEmpty()) {
                return;
            }

            ObjectMapper mapper = new ObjectMapper();
            int filledAuthor = 0;
            int filledR18 = 0;
            int filledDescription = 0;
            int filledTags = 0;
            int deletedCount = 0;
            int skipped = 0;

            for (int i = 0; i < candidates.size(); i++) {
                Candidate c = candidates.get(i);
                System.out.printf("[%d/%d] 查询 %d [%s] ... ",
                        i + 1, candidates.size(), c.artworkId, describeMissing(c));

                LookupResult result = queryPixiv(http, mapper, c.artworkId);
                switch (result.type) {
                    case FOUND -> {
                        boolean didAuthor = c.authorMissing && result.authorId > 0;
                        boolean didR18 = c.r18Missing;
                        boolean didDesc = c.descriptionMissing && result.description != null;
                        boolean didTags = c.tagsMissing && result.tags != null;

                        List<String> changes = new ArrayList<>();
                        if (didAuthor) {
                            changes.add("author=" + result.authorName + "(#" + result.authorId + ")");
                        }
                        if (didR18) {
                            changes.add("R18=" + (result.isR18 ? "1" : "0"));
                        }
                        if (didDesc) {
                            changes.add("desc=" + result.description.length() + "字符");
                        }
                        if (didTags) {
                            long tagCount = result.tags.isEmpty() ? 0
                                    : result.tags.chars().filter(ch -> ch == ',').count() + 1;
                            changes.add("tags=" + tagCount + "个");
                        }

                        if (changes.isEmpty()) {
                            System.out.println("无可补全数据");
                            skipped++;
                        } else {
                            System.out.println(String.join(", ", changes));
                            if (!dryRun) {
                                applyUpdates(conn, c, result, didAuthor, didR18, didDesc, didTags);
                            }
                            if (didAuthor) filledAuthor++;
                            if (didR18) filledR18++;
                            if (didDesc) filledDescription++;
                            if (didTags) filledTags++;
                        }
                    }
                    case R18_ONLY -> {
                        if (c.r18Missing) {
                            System.out.println("R18 (via error msg: " + result.message + ")");
                            filledR18++;
                            if (!dryRun) {
                                applyR18Only(conn, c.artworkId);
                            }
                        } else {
                            System.out.println("跳过 (" + result.message + "，R18 已有值)");
                            skipped++;
                        }
                    }
                    case DELETED -> {
                        System.out.println("已删除/不可访问 — 跳过 (" + result.message + ")");
                        deletedCount++;
                    }
                    case SKIP -> {
                        System.out.println("跳过 (" + result.message + ")");
                        skipped++;
                    }
                    case RATE_LIMITED -> {
                        System.out.println("触发限流（429），已停止");
                        System.out.printf("%n已处理 %d/%d 条：author=%d  R18=%d  desc=%d  tags=%d  已删除=%d  跳过=%d%n",
                                i, candidates.size(), filledAuthor, filledR18, filledDescription, filledTags, deletedCount, skipped);
                        if (dryRun) {
                            System.out.println("（试运行模式，未写入数据库）");
                        }
                        return;
                    }
                }

                if (i < candidates.size() - 1) {
                    Thread.sleep(delayMs);
                }
            }

            System.out.printf("%n完成：扫描=%d  author=%d  R18=%d  desc=%d  tags=%d  已删除=%d  跳过=%d%n",
                    candidates.size(), filledAuthor, filledR18, filledDescription, filledTags, deletedCount, skipped);
            if (dryRun) {
                System.out.println("（试运行模式，未写入数据库）");
            }
        }
    }

    private static String describeMissing(Candidate c) {
        List<String> parts = new ArrayList<>(4);
        if (c.authorMissing) parts.add("author");
        if (c.r18Missing) parts.add("R18");
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
        addColumnIfMissing(conn, "ALTER TABLE artworks ADD COLUMN author_id INTEGER DEFAULT NULL");
        addColumnIfMissing(conn, "ALTER TABLE artworks ADD COLUMN \"R18\" INTEGER DEFAULT NULL");
        addColumnIfMissing(conn, "ALTER TABLE artworks ADD COLUMN description TEXT DEFAULT NULL");
        addColumnIfMissing(conn, "ALTER TABLE artworks ADD COLUMN tags TEXT DEFAULT NULL");
    }

    private static void addColumnIfMissing(Connection conn, String ddl) {
        try (PreparedStatement ps = conn.prepareStatement(ddl)) {
            ps.executeUpdate();
        } catch (SQLException ignored) {
            // 列已存在时直接忽略，行为与运行时迁移保持一致。
        }
    }

    private static List<Candidate> findCandidates(Connection conn, int limit) throws SQLException {
        String sql = "SELECT artwork_id, author_id, \"R18\", description, tags FROM artworks"
                + " WHERE author_id IS NULL OR \"R18\" IS NULL OR description IS NULL OR tags IS NULL"
                + " ORDER BY artwork_id";
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
                    boolean descMissing = rs.getObject(4) == null;
                    boolean tagsMissing = rs.getObject(5) == null;
                    list.add(new Candidate(id, authorMissing, r18Missing, descMissing, tagsMissing));
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
                boolean isR18 = xRestrict > 0;
                String description = payload.path("description").asText("");
                String tags = extractTags(payload);
                return LookupResult.found(authorId, authorName, isR18, description, tags);
            });
        } catch (Exception e) {
            return LookupResult.skip("请求异常: " + e.getMessage());
        }
    }

    private static void applyUpdates(Connection conn, Candidate c, LookupResult result,
                                     boolean updateAuthor, boolean updateR18,
                                     boolean updateDescription, boolean updateTags) throws SQLException {
        List<String> sets = new ArrayList<>(4);
        if (updateAuthor) sets.add("author_id = ?");
        if (updateR18) sets.add("\"R18\" = ?");
        if (updateDescription) sets.add("description = ?");
        if (updateTags) sets.add("tags = ?");
        if (sets.isEmpty()) {
            return;
        }

        String sql = "UPDATE artworks SET " + String.join(", ", sets) + " WHERE artwork_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            if (updateAuthor) ps.setLong(idx++, result.authorId);
            if (updateR18) ps.setInt(idx++, result.isR18 ? 1 : 0);
            if (updateDescription) ps.setString(idx++, result.description);
            if (updateTags) ps.setString(idx++, result.tags);
            ps.setLong(idx, c.artworkId);
            ps.executeUpdate();
        }

        if (updateAuthor && result.authorId > 0) {
            upsertAuthor(conn, result.authorId, result.authorName);
        }
    }

    private static String extractTags(JsonNode payload) {
        JsonNode tagsArr = payload.path("tags").path("tags");
        if (!tagsArr.isArray() || tagsArr.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode t : tagsArr) {
            String tag = t.path("tag").asText("");
            if (tag.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(',');
            sb.append(tag);
        }
        return sb.toString();
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

    private static final class Candidate {
        final long artworkId;
        final boolean authorMissing;
        final boolean r18Missing;
        final boolean descriptionMissing;
        final boolean tagsMissing;

        Candidate(long artworkId, boolean authorMissing, boolean r18Missing,
                  boolean descriptionMissing, boolean tagsMissing) {
            this.artworkId = artworkId;
            this.authorMissing = authorMissing;
            this.r18Missing = r18Missing;
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
        final boolean isR18;
        final String description;
        final String tags;
        final String message;

        private LookupResult(ResultType type, long authorId, String authorName, boolean isR18,
                             String description, String tags, String message) {
            this.type = type;
            this.authorId = authorId;
            this.authorName = authorName;
            this.isR18 = isR18;
            this.description = description;
            this.tags = tags;
            this.message = message;
        }

        static LookupResult found(long authorId, String authorName, boolean isR18, String description, String tags) {
            return new LookupResult(ResultType.FOUND, authorId, authorName, isR18, description, tags, null);
        }

        static LookupResult r18Only(String message) {
            return new LookupResult(ResultType.R18_ONLY, 0, null, true, null, null, message);
        }

        static LookupResult deleted(String message) {
            return new LookupResult(ResultType.DELETED, 0, null, false, null, null, message);
        }

        static LookupResult skip(String message) {
            return new LookupResult(ResultType.SKIP, 0, null, false, null, null, message);
        }

        static LookupResult rateLimited() {
            return new LookupResult(ResultType.RATE_LIMITED, 0, null, false, null, null, "HTTP 429 Too Many Requests");
        }
    }
}
