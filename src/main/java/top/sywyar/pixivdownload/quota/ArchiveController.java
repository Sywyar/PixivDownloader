package top.sywyar.pixivdownload.quota;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.sywyar.pixivdownload.common.UuidUtils;
import top.sywyar.pixivdownload.core.db.ArtworkRecord;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.common.ErrorResponse;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.quota.request.AdminPackRequest;
import top.sywyar.pixivdownload.quota.response.AdminArchiveTasksResponse;
import top.sywyar.pixivdownload.quota.response.ArchiveStatusResponse;
import top.sywyar.pixivdownload.quota.response.PackRateLimitResponse;
import top.sywyar.pixivdownload.quota.response.QuotaInitResponse;
import top.sywyar.pixivdownload.quota.response.TriggerPackResponse;
import top.sywyar.pixivdownload.setup.SetupService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@RestController
@Slf4j
@RequiredArgsConstructor
public class ArchiveController {

    private final UserQuotaService userQuotaService;
    private final MultiModeConfig multiModeConfig;
    private final SetupService setupService;
    private final PixivDatabase pixivDatabase;
    private final AppMessages messages;

    /**
     * 初始化配额会话：返回当前用户的 UUID 和配额状态。
     * 若用户没有 UUID cookie，则自动分配并写入 cookie。
     */
    @PostMapping("/api/quota/init")
    public ResponseEntity<QuotaInitResponse> initQuota(HttpServletRequest request) {
        if (setupService.isAdminLoggedIn(request)) {
            return ResponseEntity.ok(new QuotaInitResponse(false, true, null, null, null, null, null));
        }

        if (!"multi".equals(setupService.getMode())
                || !multiModeConfig.getQuota().isEnabled()) {
            return ResponseEntity.ok(new QuotaInitResponse(false, false, null, null, null, null, null));
        }

        String existingUuid = UuidUtils.extractExistingUuid(request);
        String uuid = existingUuid != null ? existingUuid : UuidUtils.extractOrGenerateUuid(request);
        UserQuotaService.QuotaStatusResult status = userQuotaService.getQuotaStatus(uuid);

        ResponseEntity.BodyBuilder responseBuilder = ResponseEntity.ok();
        if (existingUuid == null) {
            responseBuilder.header(HttpHeaders.SET_COOKIE, buildUuidCookie(uuid).toString());
        }

        return responseBuilder.body(new QuotaInitResponse(
                true,
                false,
                uuid,
                status.artworksUsed(),
                status.maxArtworks(),
                status.resetSeconds(),
                status.archive()
        ));
    }

    /**
     * 手动触发打包：队列全部完成后由前端调用，将当前用户已下载的文件打包。
     * 仅多人模式可用；必须提供有效 UUID（pixiv_user_id cookie 或 X-User-UUID 请求头）。
     * 在 archiveExpireMinutes 窗口内最多触发 maxArtworks 次。
     * 若用户无已记录的文件夹（如首次调用或已清空），返回 204。
     */
    @PostMapping("/api/quota/pack")
    public ResponseEntity<?> triggerPack(HttpServletRequest request) {

        if (!"multi".equals(setupService.getMode())
                || !multiModeConfig.getQuota().isEnabled()) {
            return ResponseEntity.status(403)
                    .body(new ErrorResponse(messages.get("quota.multi-mode.not-enabled")));
        }

        // UUID 必须已存在，不自动生成
        String uuid = UuidUtils.extractExistingUuid(request);
        if (uuid == null) {
            return ResponseEntity.status(401)
                    .body(new ErrorResponse(messages.get("pixiv.proxy.user-uuid.missing")));
        }

        // 频率限制：archiveExpireMinutes 窗口内最多 maxArtworks 次
        if (!userQuotaService.checkAndReservePack(uuid)) {
            int max = multiModeConfig.getQuota().getMaxArtworks();
            int windowMin = multiModeConfig.getQuota().getArchiveExpireMinutes();
            return ResponseEntity.status(429).body(new PackRateLimitResponse(
                    messages.get("archive.pack.rate-limit.exceeded"), max, windowMin));
        }

        UserQuotaService.UserQuota quota = userQuotaService.getQuotaForUser(uuid);

        if (quota == null || quota.getDownloadedFolders().isEmpty()) {
            return ResponseEntity.status(204).build();
        }

        String token = userQuotaService.triggerArchive(uuid);
        long expireSeconds = (long) multiModeConfig.getQuota().getArchiveExpireMinutes() * 60;
        return ResponseEntity.ok(new TriggerPackResponse(token, expireSeconds));
    }

    @PostMapping("/api/archive/pack-artworks")
    public ResponseEntity<?> triggerAdminPack(@Valid @RequestBody AdminPackRequest request,
                                              HttpServletRequest httpRequest) {
        if (!setupService.isAdminLoggedIn(httpRequest)) {
            return ResponseEntity.status(401).body(new ErrorResponse(messages.get("auth.unauthorized")));
        }

        if (request == null || request.getArtworkIds() == null || request.getArtworkIds().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse(messages.get("validation.archive.pack.artwork-ids.required")));
        }

        Set<Path> uniqueFolders = new LinkedHashSet<>();
        for (Long artworkId : request.getArtworkIds()) {
            if (artworkId == null) {
                continue;
            }
            ArtworkRecord artwork = pixivDatabase.getArtwork(artworkId);
            if (artwork == null) {
                log.info(message("archive.log.admin-pack.skip.artwork-missing", artworkId));
                continue;
            }

            String folderString = resolveArtworkFolder(artwork);
            if (folderString == null || folderString.isBlank()) {
                log.info(message("archive.log.admin-pack.skip.folder-missing", artworkId));
                continue;
            }

            Path folder = Path.of(folderString);
            if (!Files.isDirectory(folder)) {
                log.info(message("archive.log.admin-pack.skip.folder-not-found", artworkId, folder));
                continue;
            }
            uniqueFolders.add(folder);
        }

        if (uniqueFolders.isEmpty()) {
            return ResponseEntity.noContent().build();
        }

        List<Path> folders = new ArrayList<>(uniqueFolders);
        String token = userQuotaService.triggerAdminArchive(folders);
        long expireSeconds = (long) multiModeConfig.getQuota().getArchiveExpireMinutes() * 60;
        return ResponseEntity.ok(new TriggerPackResponse(token, expireSeconds));
    }

    /**
     * 管理员压缩任务列表：返回所有未过期的导出/打包任务，供任务列表模块展示。
     * 仅管理员可用（多人模式下访客请求会被拒绝）。
     */
    @GetMapping("/api/archive/list")
    public ResponseEntity<?> listAdminArchives(HttpServletRequest request) {
        if (!setupService.isAdminLoggedIn(request)) {
            return ResponseEntity.status(401).body(new ErrorResponse(messages.get("auth.unauthorized")));
        }
        long now = System.currentTimeMillis();
        List<AdminArchiveTasksResponse.Task> tasks = userQuotaService.listAdminArchives().stream()
                .map(entry -> new AdminArchiveTasksResponse.Task(
                        entry.getToken(),
                        entry.getStatus(),
                        entry.getExportType(),
                        entry.getWorkCount(),
                        entry.getProcessedWorks(),
                        entry.getFileCount(),
                        entry.getCreatedTime(),
                        Math.max(0, (entry.getExpireTime() - now) / 1000)))
                .toList();
        return ResponseEntity.ok(new AdminArchiveTasksResponse(tasks));
    }

    /**
     * 查询压缩包状态。
     */
    @GetMapping("/api/archive/status/{token}")
    public ResponseEntity<ArchiveStatusResponse> archiveStatus(@PathVariable String token) {
        UserQuotaService.ArchiveEntry entry = userQuotaService.getArchive(token);
        if (entry == null) {
            return ResponseEntity.ok(new ArchiveStatusResponse(null, "expired", null));
        }
        long now = System.currentTimeMillis();
        if (now > entry.getExpireTime()) {
            userQuotaService.deleteArchive(token);
            return ResponseEntity.ok(new ArchiveStatusResponse(null, "expired", null));
        }
        return ResponseEntity.ok(new ArchiveStatusResponse(
                token,
                entry.getStatus(),
                (entry.getExpireTime() - now) / 1000
        ));
    }

    /**
     * 下载压缩包文件。
     */
    @GetMapping("/api/archive/download/{token}")
    public ResponseEntity<?> downloadArchive(@PathVariable String token) {
        UserQuotaService.ArchiveEntry entry = userQuotaService.getArchive(token);
        if (entry == null || System.currentTimeMillis() > entry.getExpireTime()) {
            return ResponseEntity.status(410)
                    .body(new ErrorResponse(messages.get("archive.download.expired-or-missing")));
        }
        if (!"ready".equals(entry.getStatus())) {
            return ResponseEntity.status(202)
                    .body(new ErrorResponse(messages.get("archive.download.preparing")));
        }
        if (entry.getArchivePath() == null || !entry.getArchivePath().toFile().exists()) {
            if ("empty".equals(entry.getStatus())) {
                return ResponseEntity.status(204).build();
            }
            return ResponseEntity.status(404)
                    .body(new ErrorResponse(messages.get("archive.download.file.missing")));
        }

        String filename = "pixiv_download_" + token.substring(0, 8) + ".zip";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(entry.getArchivePath()));
    }

    // ---- Cookie 工具 ---------------------------------------------------------------

    private ResponseCookie buildUuidCookie(String uuid) {
        return ResponseCookie.from("pixiv_user_id", uuid)
                .path("/")
                .maxAge(Duration.ofDays(30))
                .sameSite("Strict")
                .httpOnly(true)
                .build();
    }

    private String resolveArtworkFolder(ArtworkRecord artwork) {
        if (artwork.moved() && artwork.moveFolder() != null && !artwork.moveFolder().isBlank()) {
            return artwork.moveFolder();
        }
        return artwork.folder();
    }

    private String message(String code, Object... args) {
        return messages.getForLog(code, args);
    }
}
