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
 * 独立工具：批量补全 artworks.author_id，并同步维护 authors 表。
 *
 * <p>用法：
 * <pre>
 *   java -cp ... top.sywyar.pixivdownload.tools.AuthorBackfill [选项]
 *
 *   --db       &lt;path&gt;       数据库文件路径（默认：data/pixiv_download.db）
 *   --proxy    &lt;host:port&gt;  HTTP 代理（默认：127.0.0.1:7890）
 *   --no-proxy              不使用代理
 *   --delay    &lt;ms&gt;         每次请求间隔毫秒（默认：800）
 *   --limit    &lt;n&gt;          仅处理前 n 条 author_id 缺失记录
 *   --dry-run               只打印结果，不写入数据库
 * </pre>
 */
public class AuthorBackfill {

    private static final String PIXIV_AJAX = "https://www.pixiv.net/ajax/illust/";
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
        System.out.println("提示：运行 AuthorBackfill 前请先停止后端服务，避免与 SQLite 同时写入。");

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
            List<Long> missingIds = findMissingAuthorIds(conn, limit);
            System.out.printf("共 %d 条记录需要补全%n", missingIds.size());
            if (missingIds.isEmpty()) {
                return;
            }

            ObjectMapper mapper = new ObjectMapper();
            int filled = 0;
            int deleted = 0;
            int skipped = 0;

            for (int i = 0; i < missingIds.size(); i++) {
                long artworkId = missingIds.get(i);
                System.out.printf("[%d/%d] 查询 %d ... ", i + 1, missingIds.size(), artworkId);

                LookupResult result = queryPixiv(http, mapper, artworkId);
                switch (result.type()) {
                    case FOUND -> {
                        System.out.printf("%s (#%d)%n", result.authorName(), result.authorId());
                        filled++;
                        if (!dryRun) {
                            writeAuthorData(conn, artworkId, result.authorId(), result.authorName());
                        }
                    }
                    case DELETED -> {
                        System.out.println("已删除/不可访问 — 跳过 (" + result.message() + ")");
                        deleted++;
                    }
                    case SKIP -> {
                        System.out.println("跳过 (" + result.message() + ")");
                        skipped++;
                    }
                    case RATE_LIMITED -> {
                        System.out.println("触发限流（429），已停止");
                        System.out.printf("%n已处理 %d/%d 条：补全=%d  已删除=%d  跳过=%d%n",
                                i, missingIds.size(), filled, deleted, skipped);
                        if (dryRun) {
                            System.out.println("（试运行模式，未写入数据库）");
                        }
                        return;
                    }
                }

                if (i < missingIds.size() - 1) {
                    Thread.sleep(delayMs);
                }
            }

            System.out.printf("%n完成：扫描=%d  补全=%d  已删除=%d  跳过=%d%n",
                    missingIds.size(), filled, deleted, skipped);
            if (dryRun) {
                System.out.println("（试运行模式，未写入数据库）");
            }
        }
    }

    private static void ensureSchema(Connection conn) throws SQLException {
        try (PreparedStatement createAuthors = conn.prepareStatement(
                "CREATE TABLE IF NOT EXISTS authors ("
                        + "author_id INTEGER PRIMARY KEY,"
                        + "name TEXT NOT NULL,"
                        + "updated_time INTEGER NOT NULL)")) {
            createAuthors.executeUpdate();
        }
        try (PreparedStatement addAuthorId = conn.prepareStatement(
                "ALTER TABLE artworks ADD COLUMN author_id INTEGER DEFAULT NULL")) {
            addAuthorId.executeUpdate();
        } catch (SQLException ignored) {
            // 列已存在时直接忽略，行为与运行时迁移保持一致。
        }
    }

    private static List<Long> findMissingAuthorIds(Connection conn, int limit) throws SQLException {
        String sql = limit > 0
                ? "SELECT artwork_id FROM artworks WHERE author_id IS NULL ORDER BY artwork_id LIMIT ?"
                : "SELECT artwork_id FROM artworks WHERE author_id IS NULL ORDER BY artwork_id";
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (limit > 0) {
                ps.setInt(1, limit);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
        }
        return ids;
    }

    private static LookupResult queryPixiv(CloseableHttpClient http, ObjectMapper mapper, long artworkId) {
        HttpGet request = new HttpGet(PIXIV_AJAX + artworkId);
        request.setHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
        request.setHeader("Referer", "https://www.pixiv.net/");
        request.setHeader("Accept-Language", "ja,en;q=0.9");

        try {
            return http.execute(request, response -> {
                int status = response.getCode();
                String body = EntityUtils.toString(response.getEntity(), "UTF-8");

                if (status == 429) {
                    return LookupResult.rateLimited("HTTP 429 Too Many Requests");
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
                    for (String keyword : DELETED_KEYWORDS) {
                        if (lower.contains(keyword.toLowerCase())) {
                            return LookupResult.deleted(message);
                        }
                    }
                    return LookupResult.skip(message);
                }

                JsonNode payload = root.path("body");
                long authorId = payload.path("userId").asLong(0);
                if (authorId <= 0) {
                    return LookupResult.skip("missing userId");
                }
                String authorName = payload.path("userName").asText(String.valueOf(authorId)).trim();
                if (authorName.isEmpty()) {
                    authorName = String.valueOf(authorId);
                }
                return LookupResult.found(authorId, authorName);
            });
        } catch (Exception e) {
            return LookupResult.skip("请求异常: " + e.getMessage());
        }
    }

    private static void writeAuthorData(Connection conn, long artworkId, long authorId, String authorName) throws SQLException {
        long nowSeconds = Instant.now().getEpochSecond();
        try (PreparedStatement updateArtwork = conn.prepareStatement(
                "UPDATE artworks SET author_id = ? WHERE artwork_id = ?");
             PreparedStatement insertAuthor = conn.prepareStatement(
                     "INSERT OR IGNORE INTO authors(author_id, name, updated_time) VALUES(?, ?, ?)");
             PreparedStatement updateAuthor = conn.prepareStatement(
                     "UPDATE authors SET name = ?, updated_time = ? WHERE author_id = ? AND name <> ?")) {
            updateArtwork.setLong(1, authorId);
            updateArtwork.setLong(2, artworkId);
            updateArtwork.executeUpdate();

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
        System.out.println("用法: AuthorBackfill [--db <path>] [--proxy <host:port>] [--no-proxy] [--delay <ms>] [--limit <n>] [--dry-run]");
    }

    private enum ResultType {
        FOUND,
        DELETED,
        SKIP,
        RATE_LIMITED
    }

    private record LookupResult(ResultType type, long authorId, String authorName, String message) {
        private static LookupResult found(long authorId, String authorName) {
            return new LookupResult(ResultType.FOUND, authorId, authorName, null);
        }

        private static LookupResult deleted(String message) {
            return new LookupResult(ResultType.DELETED, 0, null, message);
        }

        private static LookupResult skip(String message) {
            return new LookupResult(ResultType.SKIP, 0, null, message);
        }

        private static LookupResult rateLimited(String message) {
            return new LookupResult(ResultType.RATE_LIMITED, 0, null, message);
        }
    }
}
