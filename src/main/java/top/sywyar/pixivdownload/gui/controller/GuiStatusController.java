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
import top.sywyar.pixivdownload.setup.SetupService;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;

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
        if (!NetworkUtils.isLocalAddress(req.getRemoteAddr())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        String startTimeStr = LocalDateTime.ofInstant(startTime, ZoneId.systemDefault())
                .format(FORMATTER);

        GuiStatusResponse resp = GuiStatusResponse.builder()
                .port(resolvePort())
                .mode(setupService.getMode())
                .startTime(startTimeStr)
                .build();

        return ResponseEntity.ok(resp);
    }

    /**
     * POST /api/gui/restart
     * 触发自重启：启动新进程，然后退出当前 JVM。
     */
    @PostMapping("/restart")
    public ResponseEntity<Void> restart(HttpServletRequest req) {
        if (!NetworkUtils.isLocalAddress(req.getRemoteAddr())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        log.info("收到 GUI 重启请求，即将重启服务...");

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
                        log.info("重新启动进程: {}", command);
                        new ProcessBuilder(command).start();
                    } catch (Exception e) {
                        log.warn("重新启动进程失败: {}", e.getMessage());
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
}
