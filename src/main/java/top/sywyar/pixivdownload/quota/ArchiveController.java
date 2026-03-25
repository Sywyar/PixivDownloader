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
import top.sywyar.pixivdownload.setup.SetupService;

import java.util.HashMap;
import java.util.Map;

@RestController
@CrossOrigin(origins = "*")
@Slf4j
public class ArchiveController {

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
     * 若用户无已记录的文件夹（如首次调用或已清空），返回 204。
     */
    @PostMapping("/api/quota/pack")
    public ResponseEntity<Map<String, Object>> triggerPack(
            HttpServletRequest request, HttpServletResponse response) {

        if (!"multi".equals(setupService.getMode())
                || !multiModeConfig.getQuota().isEnabled()) {
            return ResponseEntity.ok(Map.of("enabled", false));
        }

        String uuid = extractOrCreateUuid(request, response);
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
        // 2. 读取自定义请求头（油猴脚本场景）
        String headerUuid = request.getHeader("X-User-UUID");
        if (headerUuid != null && !headerUuid.isBlank()) {
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
        Cookie cookie = new Cookie("pixiv_user_id", uuid);
        cookie.setPath("/");
        cookie.setMaxAge(30 * 24 * 3600);
        response.addCookie(cookie);
    }
}
