package top.sywyar.pixivdownload.gui.controller;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.sywyar.pixivdownload.common.NetworkUtils;
import top.sywyar.pixivdownload.config.RuntimeConfigReloadService;
import top.sywyar.pixivdownload.config.SslConfig;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.setup.SetupService;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

/**
 * GUI 专用 REST 接口。
 * 仅接受来自 localhost 的请求（GUI 与 Spring Boot 同进程）。
 * 路径 /api/gui/** 已在 AuthFilter.isPublic() 中放行，无需 session。
 * 返回字段刻意精简：统计数据（总作品数、活跃队列等）由 monitor.html 专职展示，
 * 此接口只返回 monitor.html 不展示的服务器元信息（端口、模式、启动时间）。
 */
@RestController
@RequestMapping("/api/gui")
@RequiredArgsConstructor
@Slf4j
public class GuiStatusController {

    private final SetupService setupService;
    private final Environment environment;
    private final SslConfig sslConfig;
    private final RuntimeConfigReloadService runtimeConfigReloadService;
    private final AppMessages messages;

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private Instant startTime;

    @PostConstruct
    public void init() {
        startTime = Instant.now();
    }

    /**
     * GET /api/gui/status
     * 返回服务器元信息：端口、运行模式、启动时间。
     * 统计数据和下载队列请查看 /monitor.html。
     */
    @GetMapping("/status")
    public ResponseEntity<GuiStatusResponse> status(HttpServletRequest req) {
        if (!NetworkUtils.isTrustedLocalRequest(req)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        String startTimeStr = LocalDateTime.ofInstant(startTime, ZoneId.systemDefault())
                .format(FORMATTER);

        boolean https = isSslEnabled();
        GuiStatusResponse resp = GuiStatusResponse.builder()
                .port(resolvePort())
                .mode(setupService.getMode())
                .startTime(startTimeStr)
                .httpsEnabled(https)
                .domain(sslConfig.getDomain())
                .scheme(https ? "https" : "http")
                .build();

        return ResponseEntity.ok(resp);
    }

    /**
     * POST /api/gui/restart
     * 触发自重启：启动新进程，然后退出当前 JVM。
     */
    @PostMapping("/restart")
    public ResponseEntity<Void> restart(HttpServletRequest req) {
        if (!NetworkUtils.isTrustedLocalRequest(req)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        log.info(logMessage("gui.controller.log.restart-request.received"));

        Thread restartThread = new Thread(() -> {
            try {
                Thread.sleep(500);
                ProcessHandle current = ProcessHandle.current();
                current.info().command().ifPresent(cmd -> {
                    try {
                        java.util.List<String> command = new java.util.ArrayList<>();
                        command.add(cmd);
                        current.info().arguments()
                                .ifPresent(a -> command.addAll(Arrays.asList(a)));
                        log.info(logMessage("gui.controller.log.restart.command", command));
                        new ProcessBuilder(command).start();
                    } catch (Exception e) {
                        log.warn(logMessage("gui.controller.log.restart.command-failed", e.getMessage()));
                    }
                });
                Thread.sleep(1000);
                System.exit(0);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "gui-restart");
        restartThread.setDaemon(true);
        restartThread.start();

        return ResponseEntity.ok().build();
    }

    @PostMapping("/config/reload")
    public ResponseEntity<GuiConfigReloadResponse> reloadConfig(HttpServletRequest req) {
        if (!NetworkUtils.isTrustedLocalRequest(req)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        try {
            RuntimeConfigReloadService.ReloadResult result = runtimeConfigReloadService.reloadHotConfig();
            return ResponseEntity.ok(new GuiConfigReloadResponse(true, result.appliedKeys(), "ok"));
        } catch (IOException e) {
            log.warn(logMessage("gui.config.log.hot-reload-failed", e.getMessage()));
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new GuiConfigReloadResponse(false, List.of(), e.getMessage()));
        } catch (RuntimeException e) {
            log.warn(logMessage("gui.config.log.hot-reload-failed", e.getMessage()), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new GuiConfigReloadResponse(false, List.of(), e.getMessage()));
        }
    }

    // ── 私有工具 ──────────────────────────────────────────────────────────────────

    private int resolvePort() {
        try {
            String local = environment.getProperty("local.server.port");
            if (local != null) return Integer.parseInt(local);
            String configured = environment.getProperty("server.port");
            if (configured != null) return Integer.parseInt(configured);
        } catch (NumberFormatException ignored) {}
        return 6999;
    }

    /**
     * 检测 SSL 是否已配置并启用。
     * 优先 PEM（certificate + certificate-private-key），其次 JKS（key-store）。
     * 若 server.ssl.enabled 显式设为 false，则视为未启用。
     */
    private boolean isSslEnabled() {
        String enabled = environment.getProperty("server.ssl.enabled");
        if ("false".equalsIgnoreCase(enabled)) return false;

        String cert    = environment.getProperty("server.ssl.certificate");
        String certKey = environment.getProperty("server.ssl.certificate-private-key");
        if (cert != null && !cert.isBlank() && certKey != null && !certKey.isBlank()) return true;

        String keyStore = environment.getProperty("server.ssl.key-store");
        return keyStore != null && !keyStore.isBlank();
    }

    private String logMessage(String code, Object... args) {
        return messages.getForLog(code, args);
    }
}
