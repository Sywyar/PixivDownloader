package top.sywyar.pixivdownload.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpHost;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.sqlite.SQLiteConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * 独立工具：批量补全数据库中 R18 字段为 NULL 的作品。
 *
 * <p>通过调用 Pixiv AJAX 接口（无需登录），根据响应内容判断作品是否为 R18：
 * <ul>
 *   <li>响应正常（error=false）：直接读取 body.xRestrict（0=SFW，1/2=R18）</li>
 *   <li>响应报错且 message 含 R18 关键词：判定为 R18</li>
 *   <li>响应报错且 message 含删除关键词：跳过，不修改数据库</li>
 *   <li>其他（网络异常、未知错误）：跳过，不修改数据库</li>
 * </ul>
 *
 * <p>用法：
 * <pre>
 *   java -cp ... R18Backfill [选项]
 *
 *   --db      &lt;path&gt;       数据库文件路径（默认：pixiv-download/pixiv_download.db）
 *   --proxy   &lt;host:port&gt;  HTTP 代理（默认：127.0.0.1:7890）
 *   --no-proxy              不使用代理
 *   --delay   &lt;ms&gt;         每次请求间隔毫秒（默认：800）
 *   --dry-run               只打印结果，不写入数据库
 * </pre>
 */
public class R18Backfill {

    private static final String PIXIV_AJAX = "https://www.pixiv.net/ajax/illust/";

    // 判定为 R18 的 message 关键词（日文/英文）
    private static final String[] R18_KEYWORDS = {
            "R-18", "R18", "年齢制限", "年龄限制", "閲覧制限", "18歳未満",
            "成人向け", "成人向", "restricted", "age"
    };

    // 判定为已删除/不存在的 message 关键词
    private static final String[] DELETED_KEYWORDS = {
            "削除", "存在しない", "not found", "该作品", "不存在", "已删除"
    };

    public static void main(String[] args) throws Exception {
        // ---- 解析参数 ----
        String dbPath   = "pixiv-download/pixiv_download.db";
        String proxyHost = "127.0.0.1";
        int    proxyPort = 7890;
        boolean useProxy = true;
        long   delayMs  = 800;
        boolean dryRun  = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--db"       -> dbPath    = args[++i];
                case "--proxy"    -> { String[] p = args[++i].split(":"); proxyHost = p[0]; proxyPort = Integer.parseInt(p[1]); }
                case "--no-proxy" -> useProxy  = false;
                case "--delay"    -> delayMs   = Long.parseLong(args[++i]);
                case "--dry-run"  -> dryRun    = true;
            }
        }

        System.out.printf("DB: %s | 代理: %s | 延迟: %dms | 试运行: %s%n",
                dbPath, useProxy ? proxyHost + ":" + proxyPort : "无", delayMs, dryRun);

        // ---- 初始化 SQLite 连接 ----
        SQLiteConfig sqliteConfig = new SQLiteConfig();
        sqliteConfig.setBusyTimeout(5000);
        sqliteConfig.setJournalMode(SQLiteConfig.JournalMode.WAL);
        String jdbcUrl = "jdbc:sqlite:" + dbPath;

        // ---- 初始化 HttpClient ----
        RequestConfig reqConfig = RequestConfig.custom()
                .setConnectTimeout(15000)
                .setSocketTimeout(15000)
                .setConnectionRequestTimeout(5000)
                .build();
        var clientBuilder = HttpClients.custom()
                .setDefaultRequestConfig(reqConfig)
                .disableCookieManagement();  // 禁用 Cookie，确保每次请求都是纯匿名状态
        if (useProxy) clientBuilder.setProxy(new HttpHost(proxyHost, proxyPort));
        CloseableHttpClient http = clientBuilder.build();

        ObjectMapper mapper = new ObjectMapper();

        // ---- 查询未知 R18 的作品 ----
        List<Long> unknownIds = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(jdbcUrl, sqliteConfig.toProperties())) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT artwork_id FROM artworks WHERE \"R18\" IS NULL ORDER BY artwork_id")) {
                ResultSet rs = ps.executeQuery();
                while (rs.next()) unknownIds.add(rs.getLong(1));
            }
        }

        System.out.printf("共 %d 条记录需要补全%n", unknownIds.size());
        if (unknownIds.isEmpty()) return;

        // ---- 统计 ----
        int cntR18 = 0, cntSfw = 0, cntDeleted = 0, cntSkip = 0;

        try (Connection conn = DriverManager.getConnection(jdbcUrl, sqliteConfig.toProperties())) {
            for (int i = 0; i < unknownIds.size(); i++) {
                long artworkId = unknownIds.get(i);
                System.out.printf("[%d/%d] 查询 %d ... ", i + 1, unknownIds.size(), artworkId);

                R18Result result = queryPixiv(http, mapper, artworkId);

                switch (result.type()) {
                    case R18 -> {
                        System.out.println("R18");
                        cntR18++;
                        if (!dryRun) updateR18(conn, artworkId, true);
                    }
                    case SFW -> {
                        System.out.println("SFW");
                        cntSfw++;
                        if (!dryRun) updateR18(conn, artworkId, false);
                    }
                    case DELETED -> {
                        System.out.println("已删除 — 跳过 (" + result.message() + ")");
                        cntDeleted++;
                    }
                    case SKIP -> {
                        System.out.println("跳过 (" + result.message() + ")");
                        cntSkip++;
                    }
                    case RATE_LIMITED -> {
                        System.out.println("触发限流（429），已停止");
                        http.close();
                        System.out.printf("%n已处理 %d/%d 条：R18=%d  SFW=%d  已删除=%d  跳过=%d%n",
                                i, unknownIds.size(), cntR18, cntSfw, cntDeleted, cntSkip);
                        if (dryRun) System.out.println("（试运行模式，未写入数据库）");
                        return;
                    }
                }

                if (i < unknownIds.size() - 1) Thread.sleep(delayMs);
            }
        }

        http.close();

        System.out.printf("%n完成：R18=%d  SFW=%d  已删除=%d  跳过=%d%n",
                cntR18, cntSfw, cntDeleted, cntSkip);
        if (dryRun) System.out.println("（试运行模式，未写入数据库）");
    }

    // ---- 查询 Pixiv ----
    private static R18Result queryPixiv(CloseableHttpClient http, ObjectMapper mapper, long artworkId) {
        String url = PIXIV_AJAX + artworkId;
        HttpGet req = new HttpGet(url);
        req.setHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        req.setHeader("Referer", "https://www.pixiv.net/");
        req.setHeader("Accept-Language", "ja,en;q=0.9");

        try (CloseableHttpResponse resp = http.execute(req)) {
            int status = resp.getStatusLine().getStatusCode();
            String body = EntityUtils.toString(resp.getEntity(), "UTF-8");

            if (status == 429) return new R18Result(ResultType.RATE_LIMITED, "HTTP 429 Too Many Requests");
            if (status == 404) return new R18Result(ResultType.DELETED, "HTTP 404");

            JsonNode root = mapper.readTree(body);
            boolean isError = root.path("error").asBoolean(false);

            if (!isError) {
                // 正常响应，直接读 xRestrict
                int xRestrict = root.path("body").path("xRestrict").asInt(0);
                return new R18Result(xRestrict > 0 ? ResultType.R18 : ResultType.SFW, null);
            }

            // 响应为 error，解析 message
            String message = root.path("message").asText("");
            String msgLower = message.toLowerCase();

            for (String kw : R18_KEYWORDS) {
                if (msgLower.contains(kw.toLowerCase())) {
                    return new R18Result(ResultType.R18, message);
                }
            }
            for (String kw : DELETED_KEYWORDS) {
                if (msgLower.contains(kw.toLowerCase())) {
                    return new R18Result(ResultType.DELETED, message);
                }
            }

            // message 无法识别
            return new R18Result(ResultType.SKIP, "未知错误: " + message);

        } catch (Exception e) {
            return new R18Result(ResultType.SKIP, "请求异常: " + e.getMessage());
        }
    }

    private static void updateR18(Connection conn, long artworkId, boolean isR18) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE artworks SET \"R18\" = ? WHERE artwork_id = ?")) {
            ps.setInt(1, isR18 ? 1 : 0);
            ps.setLong(2, artworkId);
            ps.executeUpdate();
        }
    }

    // ---- 数据类 ----
    enum ResultType { R18, SFW, DELETED, SKIP, RATE_LIMITED }

    record R18Result(ResultType type, String message) {}
}
