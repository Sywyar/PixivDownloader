package top.sywyar.pixivdownload.maintenance;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.common.NetworkUtils;
import top.sywyar.pixivdownload.i18n.LocalizedException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 维护协调器的本地管理员控制端点。供管理员排错与人工触发维护窗口。
 *
 * <p>{@code maintenance.enabled} 在运行时被读取（支持热重载）：
 * 当配置为 {@code false} 时，{@link #status} 仍返回开关状态以便 GUI 展示，
 * {@link #run} 直接拒绝请求。
 */
@RestController
@RequestMapping("/api/admin/maintenance")
@RequiredArgsConstructor
public class MaintenanceController {

    private final MaintenanceCoordinator coordinator;

    @GetMapping("/status")
    public Map<String, Object> status(HttpServletRequest request) {
        requireLocal(request);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", coordinator.isEnabled());
        out.put("paused", coordinator.isPaused());
        out.put("lastStartedAt", coordinator.getLastStartedAt());
        out.put("lastFinishedAt", coordinator.getLastFinishedAt());
        out.put("lastTriggeredBy", coordinator.getLastTriggeredBy());
        return out;
    }

    @PostMapping("/run")
    public Map<String, Object> run(HttpServletRequest request) {
        requireLocal(request);
        if (!coordinator.isEnabled()) {
            throw new LocalizedException(HttpStatus.SERVICE_UNAVAILABLE,
                    "maintenance.disabled", "维护框架未启用");
        }
        boolean started = coordinator.runManually();
        return Map.of("started", started);
    }

    private void requireLocal(HttpServletRequest request) {
        if (!NetworkUtils.isTrustedLocalRequest(request)) {
            throw new LocalizedException(HttpStatus.FORBIDDEN,
                    "auth.local-only", "Forbidden: local access only");
        }
    }
}
