package top.sywyar.pixivdownload.gui.controller;

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
import top.sywyar.pixivdownload.update.UpdateCheckResult;
import top.sywyar.pixivdownload.update.UpdateDownloadResult;
import top.sywyar.pixivdownload.update.UpdateService;

import java.io.IOException;
import java.util.Map;

/**
 * GUI 专用更新管理 REST 接口。
 * 与 {@link GuiStatusController} 同级，路径仍位于 {@code /api/gui/**} —— 已在 AuthFilter 放行；
 * 控制器内部通过 {@link NetworkUtils#isTrustedLocalRequest} 限制只接受本机请求 + Origin 校验。
 */
@RestController
@RequestMapping("/api/gui/update")
@RequiredArgsConstructor
@Slf4j
public class UpdateController {

    private final UpdateService updateService;

    /**
     * 触发一次检查并返回结果。{@code force=true} 跳过 24h 缓存窗口（手动按钮使用）。
     */
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
    @GetMapping("/last")
    public ResponseEntity<UpdateCheckResult> last(HttpServletRequest req) {
        if (!NetworkUtils.isTrustedLocalRequest(req)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        UpdateCheckResult result = updateService.lastResult();
        return result == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(result);
    }

    /**
     * 下载已检查到的安装包到运行目录。需要 {@link UpdateCheckResult#isUpdateAvailable()} 为 true。
     */
    @PostMapping("/download")
    public ResponseEntity<?> download(HttpServletRequest req) {
        if (!NetworkUtils.isTrustedLocalRequest(req)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        try {
            UpdateDownloadResult result = updateService.downloadInstaller();
            return ResponseEntity.ok(result);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (IOException e) {
            log.warn("Update installer download failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 启动 installer 并请求当前 JVM 退出。请求体中传入下载阶段返回的 installerPath。
     */
    @PostMapping("/install")
    public ResponseEntity<?> install(HttpServletRequest req,
                                     @RequestParam("installerPath") String installerPath) {
        if (!NetworkUtils.isTrustedLocalRequest(req)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        try {
            updateService.launchInstallerAndExit(installerPath);
            return ResponseEntity.ok(Map.of("started", true));
        } catch (IOException e) {
            log.warn("Launching installer failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }
}
