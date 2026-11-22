package top.sywyar.pixivdownload.gui.controller;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.SchemaProperty;
import io.swagger.v3.oas.annotations.media.Content;
import top.sywyar.pixivdownload.plugin.api.web.HttpApiGroup;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.common.NetworkUtils;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;
import top.sywyar.pixivdownload.update.UpdateCheckResult;
import top.sywyar.pixivdownload.update.UpdateService;

import java.io.IOException;
import java.util.Map;

/**
 * GUI 专用更新管理 REST 接口。
 * 与 {@link GuiStatusController} 同级，路径仍位于 {@code /api/gui/**} —— 已在 AuthFilter 放行；
 * 控制器内部通过 {@link NetworkUtils#isTrustedLocalRequest} 限制只接受本机请求 + Origin 校验。
 */
@HttpApiGroup("core-updates")
@RestController
@RequestMapping("/api/gui/update")
@RequiredArgsConstructor
@Slf4j
public class UpdateController {

    private final UpdateService updateService;
    private final AppMessages messages;

    /**
     * 触发一次检查并返回结果。{@code force=true} 跳过 24h 缓存窗口（手动按钮使用）。
     */
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "403", content = @Content)
    @GetMapping("/check")
    public ResponseEntity<UpdateCheckResult> check(HttpServletRequest req,
                                                   @RequestParam(value = "force", defaultValue = "false") boolean force) {
        if (!NetworkUtils.isTrustedLocalRequest(req)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(updateService.checkForUpdate(force));
    }

    /**
     * 返回上一次检查的缓存结果，不触发联网。GUI 上的轮询使用此端点。
     */
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "204", content = @Content)
    @ApiResponse(responseCode = "403", content = @Content)
    @GetMapping("/last")
    public ResponseEntity<UpdateCheckResult> last(HttpServletRequest req) {
        if (!NetworkUtils.isTrustedLocalRequest(req)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        UpdateCheckResult result = updateService.lastResult();
        return result == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(result);
    }

    /**
     * 启动安装包后台下载，立即返回 202。GUI 通过 {@code /download/progress} 轮询进度直到完成。
     * 若已有下载正在进行，返回 409。
     *
     * @param channel {@code nightly} 时下载每夜版替代选项，默认 {@code official}
     */
    @ApiResponse(responseCode = "202", description = "Accepted", content = @io.swagger.v3.oas.annotations.media.Content)
    @ApiResponse(responseCode = "403", content = @Content)
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @io.swagger.v3.oas.annotations.Parameter(name = "channel", schema = @Schema(allowableValues = {"official", "nightly"}, defaultValue = "official"))
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @PostMapping("/download")
    public ResponseEntity<?> download(HttpServletRequest req,
                                      @RequestParam(value = "channel", defaultValue = "official") String channel) {
        if (!NetworkUtils.isTrustedLocalRequest(req)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        try {
            updateService.startDownloadAsync(UpdateService.isNightlyDownload(channel));
            return ResponseEntity.accepted().build();
        } catch (IllegalStateException e) {
            log.warn("Update download could not start", e);
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiErrorResponse.of(
                    "update.download.start-failed", messages.get("update.download.start-failed")));
        }
    }

    /**
     * 返回最近一次安装包下载进度。尚未发起下载时返回 204，完成或失败后保留终态。
     */
    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json",
            schema = @Schema(implementation = UpdateService.DownloadProgress.class)))
    @ApiResponse(responseCode = "204", content = @Content)
    @ApiResponse(responseCode = "403", content = @Content)
    @GetMapping("/download/progress")
    public ResponseEntity<?> downloadProgress(HttpServletRequest req) {
        if (!NetworkUtils.isTrustedLocalRequest(req)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        UpdateService.DownloadProgress progress = updateService.getDownloadProgress();
        if (progress != null && progress.failed()) {
            progress = new UpdateService.DownloadProgress(progress.received(), progress.total(), progress.done(),
                    true, messages.get("update.download.failed"), null);
        }
        return progress == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(progress);
    }

    /** 启动服务端当前已验证的 installer 并请求当前 JVM 退出。 */
    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json",
            schema = @Schema(type = "object"),
            schemaProperties = @SchemaProperty(name = "started", schema = @Schema(type = "boolean", example = "true"))))
    @ApiResponse(responseCode = "403", content = @Content)
    @ApiResponse(responseCode = "500", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @PostMapping("/install")
    public ResponseEntity<?> install(HttpServletRequest req) {
        if (!NetworkUtils.isTrustedLocalRequest(req)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        try {
            updateService.launchInstallerAndExit();
            return ResponseEntity.ok(Map.of("started", true));
        } catch (IOException e) {
            log.warn(messages.getForLog("update.log.install.launch-failed", e.getMessage()), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiErrorResponse.of("update.install.failed", messages.get("update.install.failed")));
        }
    }
}
