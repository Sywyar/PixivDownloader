package top.sywyar.pixivdownload.tts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.i18n.AppMessages;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * 维护 Edge TTS 握手所需的 Chromium 版本号，并暂存到本地。
 *
 * <p>微软会校验 {@code Sec-MS-GEC-Version} 的新鲜度，版本过旧会握手 403。版本号的来源与更新策略：
 * <ul>
 *   <li>启动时优先读取本地缓存文件 {@code data/tts/chromium-version.txt}；无缓存则用内置默认值。</li>
 *   <li><b>不做周期性刷新</b>：仅当合成握手 403（{@link EdgeTtsClient} 调用 {@link #forceRefresh()}）时，
 *       才从微软官方 Edge 更新 API 拉取当前 Stable 版本，成功后写回缓存文件。</li>
 * </ul>
 * 这样在能正常工作时版本号保持稳定、不产生额外网络请求，过期失败时自动拉新并持久化。
 */
@Service
@Slf4j
public class EdgeTtsVersionService {

    private static final String EDGE_UPDATES_API =
            "https://edgeupdates.microsoft.com/api/products?view=enterprise";
    /**
     * 内置默认版本（无本地缓存时使用）。
     */
    static final String DEFAULT_FULL_VERSION = "148.0.3967.70";
    private static final Pattern VERSION_PATTERN = Pattern.compile("\\d+(\\.\\d+)+");

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final AppMessages messages;

    private volatile String fullVersion = DEFAULT_FULL_VERSION;

    public EdgeTtsVersionService(RestTemplate restTemplate, ObjectMapper objectMapper,
                                 AppMessages messages) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.messages = messages;
    }

    @PostConstruct
    void loadFromDisk() {
        Path versionFile = versionFile();
        try {
            if (Files.isRegularFile(versionFile)) {
                String cached = Files.readString(versionFile, StandardCharsets.UTF_8).trim();
                if (isValidVersion(cached)) {
                    fullVersion = cached;
                    log.info(logMessage("tts.log.version-loaded-from-cache", cached));
                }
            }
        } catch (Exception e) {
            log.warn(logMessage("tts.log.version-cache-read-failed", fullVersion, e.getMessage()));
        }
    }

    /**
     * 当前 Chromium 完整版本，如 {@code 148.0.3967.70}。不触发网络请求。
     */
    public String chromiumFullVersion() {
        return fullVersion;
    }

    /**
     * {@code Sec-MS-GEC-Version} 查询参数值，如 {@code 1-148.0.3967.70}。
     */
    public String secMsGecVersion() {
        return "1-" + fullVersion;
    }

    /**
     * 与版本匹配的 Chrome/Edge User-Agent。
     */
    public String userAgent() {
        String major = fullVersion.split("\\.", 2)[0];
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                + "Chrome/" + major + ".0.0.0 Safari/537.36 Edg/" + major + ".0.0.0";
    }

    /**
     * 合成握手 403 时调用：从微软官方 API 拉取当前 Stable 版本，成功且有变化时更新并写回本地缓存。
     */
    public synchronized void forceRefresh() {
        String latest = fetchLatest();
        if (latest == null || latest.equals(fullVersion)) {
            return;
        }
        log.info(logMessage("tts.log.version-updated", fullVersion, latest));
        fullVersion = latest;
        persist(latest);
    }

    private String fetchLatest() {
        try {
            // 按仓库约束：UTF-8 JSON 用 byte[] 取回再解析。
            ResponseEntity<byte[]> resp = restTemplate.exchange(
                    EDGE_UPDATES_API, HttpMethod.GET, new HttpEntity<>(null), byte[].class);
            byte[] body = resp.getBody();
            if (body == null || body.length == 0) {
                return null;
            }
            return parseStableWindowsVersion(objectMapper.readTree(body));
        } catch (Exception e) {
            log.warn(logMessage("tts.log.version-fetch-failed", fullVersion, e.getMessage()));
            return null;
        }
    }

    private void persist(String version) {
        Path versionFile = versionFile();
        try {
            Path parent = versionFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(versionFile, version, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn(logMessage("tts.log.version-cache-write-failed", e.getMessage()));
        }
    }

    private static Path versionFile() {
        return RuntimeFiles.resolveEdgeTtsVersionPath();
    }

    private static boolean isValidVersion(String v) {
        return v != null && VERSION_PATTERN.matcher(v).matches();
    }

    /**
     * 从 edgeupdates API 响应中取 Stable 频道 Windows x64 的最高 ProductVersion。
     */
    private static String parseStableWindowsVersion(JsonNode root) {
        if (!root.isArray()) {
            return null;
        }
        String best = null;
        for (JsonNode product : root) {
            if (!"Stable".equalsIgnoreCase(product.path("Product").asText(""))) {
                continue;
            }
            for (JsonNode rel : product.path("Releases")) {
                if (!"Windows".equalsIgnoreCase(rel.path("Platform").asText(""))) {
                    continue;
                }
                if (!"x64".equalsIgnoreCase(rel.path("Architecture").asText(""))) {
                    continue;
                }
                String version = rel.path("ProductVersion").asText("");
                if (isValidVersion(version) && (best == null || compareVersions(version, best) > 0)) {
                    best = version;
                }
            }
        }
        return best;
    }

    /**
     * 比较点分版本号：返回正数表示 a > b。
     */
    private static int compareVersions(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int va = i < pa.length ? parseIntSafe(pa[i]) : 0;
            int vb = i < pb.length ? parseIntSafe(pb[i]) : 0;
            if (va != vb) {
                return Integer.compare(va, vb);
            }
        }
        return 0;
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String logMessage(String code, Object... args) {
        return messages.getForLog(code, args);
    }
}
