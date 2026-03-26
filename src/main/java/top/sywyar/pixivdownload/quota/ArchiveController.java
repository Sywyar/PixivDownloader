package top.sywyar.pixivdownload.quota;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.regex.Pattern;
import top.sywyar.pixivdownload.setup.SetupService;

import java.util.HashMap;
import java.util.Map;

@RestController
@Slf4j
public class ArchiveController {

    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    @Autowired
    private UserQuotaService userQuotaService;

    @Autowired
    private MultiModeConfig multiModeConfig;

    @Autowired
    private SetupService setupService;

    /**
     * 初始化配额会话：返回当前用户的 UUID 和配额状态。
     * 若用户没有 UUID cookie，则自动分配并写入 cookie。
     */
    @GetMapping("/api/quota/init")
    public ResponseEntity<Map<String, Object>> initQuota(
            HttpServletRequest request, HttpServletResponse response) {

        if (!"multi".equals(setupService.getMode())
                || !multiModeConfig.getQuota().isEnabled()) {
            return ResponseEntity.ok(Map.of("enabled", false));
        }

        String uuid = extractOrCreateUuid(request, response);
        UserQuotaService.QuotaStatusResult status = userQuotaService.getQuotaStatus(uuid);

        Map<String, Object> result = new HashMap<>();
        result.put("enabled", true);
        result.put("uuid", uuid);
        result.put("artworksUsed", status.artworksUsed());
        result.put("maxArtworks", status.maxArtworks());
        result.put("resetSeconds", status.resetSeconds());

        if (status.archive() != null) {
            result.put("archive", Map.of(
                    "token", status.archive().token(),
                    "status", status.archive().status(),
                    "expireSeconds", status.archive().expireSeconds()
            ));
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 手动触发打包：队列全部完成后由前端调用，将当前用户已下载的文件打包。
     * 仅多人模式可用；必须提供有效 UUID（pixiv_user_id cookie 或 X-User-UUID 请求头）。
     * 在 archiveExpireMinutes 窗口内最多触发 maxArtworks 次。
     * 若用户无已记录的文件夹（如首次调用或已清空），返回 204。
     */
    @PostMapping("/api/quota/pack")
    public ResponseEntity<Map<String, Object>> triggerPack(
            HttpServletRequest request, HttpServletResponse response) {

        if (!"multi".equals(setupService.getMode())
                || !multiModeConfig.getQuota().isEnabled()) {
            return ResponseEntity.status(403).body(Map.of("error", "multi-mode quota not enabled"));
        }

        // UUID 必须已存在，不自动生成
        String uuid = extractExistingUuid(request);
        if (uuid == null) {
            return ResponseEntity.status(401).body(Map.of("error", "missing user UUID"));
        }

        // 频率限制：archiveExpireMinutes 窗口内最多 maxArtworks 次
        if (!userQuotaService.checkAndReservePack(uuid)) {
            int max = multiModeConfig.getQuota().getMaxArtworks();
            int windowMin = multiModeConfig.getQuota().getArchiveExpireMinutes();
            return ResponseEntity.status(429).body(Map.of(
                    "error", "pack rate limit exceeded",
                    "maxPacks", max,
                    "windowMinutes", windowMin
            ));
        }

        UserQuotaService.UserQuota quota = userQuotaService.getQuotaForUser(uuid);

        if (quota == null || quota.getDownloadedFolders().isEmpty()) {
            return ResponseEntity.status(204).build();
        }

        String token = userQuotaService.triggerArchive(uuid);
        long expireSeconds = (long) multiModeConfig.getQuota().getArchiveExpireMinutes() * 60;
        return ResponseEntity.ok(Map.of(
                "archiveToken", token,
                "archiveExpireSeconds", expireSeconds
        ));
    }

    /**
     * 查询压缩包状态。
     */
    @GetMapping("/api/archive/status/{token}")
    public ResponseEntity<Map<String, Object>> archiveStatus(@PathVariable String token) {
        UserQuotaService.ArchiveEntry entry = userQuotaService.getArchive(token);
        if (entry == null) {
            return ResponseEntity.ok(Map.of("status", "expired"));
        }
        long now = System.currentTimeMillis();
        if (now > entry.getExpireTime()) {
            userQuotaService.deleteArchive(token);
            return ResponseEntity.ok(Map.of("status", "expired"));
        }
        return ResponseEntity.ok(Map.of(
                "token", token,
                "status", entry.getStatus(),
                "expireSeconds", (entry.getExpireTime() - now) / 1000
        ));
    }

    /**
     * 下载压缩包文件。
     */
    @GetMapping("/api/archive/download/{token}")
    public ResponseEntity<?> downloadArchive(@PathVariable String token) {
        UserQuotaService.ArchiveEntry entry = userQuotaService.getArchive(token);
        if (entry == null || System.currentTimeMillis() > entry.getExpireTime()) {
            return ResponseEntity.status(410).body("压缩包已过期或不存在");
        }
        if (!"ready".equals(entry.getStatus())) {
            return ResponseEntity.status(202).body("压缩包正在准备中，请稍后再试");
        }
        if (entry.getArchivePath() == null || !entry.getArchivePath().toFile().exists()) {
            if ("empty".equals(entry.getStatus())) {
                return ResponseEntity.status(204).body("暂无已下载文件可打包");
            }
            return ResponseEntity.status(404).body("压缩包文件不存在");
        }

        String filename = "pixiv_download_" + token.substring(0, 8) + ".zip";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(entry.getArchivePath()));
    }

    // ---- UUID 工具 ---------------------------------------------------------------

    /**
     * 仅读取已存在的 UUID（cookie 或请求头），不自动生成。
     * 用于需要确认用户身份的操作（如触发打包）。
     * 返回 null 表示请求方未提供 UUID。
     */
    String extractExistingUuid(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if ("pixiv_user_id".equals(c.getName()) && c.getValue() != null
                        && !c.getValue().isBlank()) {
                    return c.getValue();
                }
            }
        }
        String headerUuid = request.getHeader("X-User-UUID");
        if (headerUuid != null && !headerUuid.isBlank() && UUID_PATTERN.matcher(headerUuid).matches()) {
            return headerUuid;
        }
        return null;
    }

    String extractOrCreateUuid(HttpServletRequest request, HttpServletResponse response) {
        // 1. 优先读取 cookie
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if ("pixiv_user_id".equals(c.getName()) && c.getValue() != null
                        && !c.getValue().isBlank()) {
                    return c.getValue();
                }
            }
        }
        // 2. 读取自定义请求头（油猴脚本场景），校验格式
        String headerUuid = request.getHeader("X-User-UUID");
        if (headerUuid != null && !headerUuid.isBlank() && UUID_PATTERN.matcher(headerUuid).matches()) {
            setUuidCookie(response, headerUuid);
            return headerUuid;
        }
        // 3. 基于 IP + UA 生成稳定 UUID
        String uuid = UserQuotaService.generateUuidFromFingerprint(
                request.getRemoteAddr(), request.getHeader("User-Agent"));
        setUuidCookie(response, uuid);
        return uuid;
    }

    private void setUuidCookie(HttpServletResponse response, String uuid) {
        response.addHeader(HttpHeaders.SET_COOKIE,
                "pixiv_user_id=" + uuid + "; Path=/; Max-Age=" + (30 * 24 * 3600) + "; SameSite=Strict; HttpOnly");
    }
}
