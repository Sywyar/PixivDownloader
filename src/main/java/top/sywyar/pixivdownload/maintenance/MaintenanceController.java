package top.sywyar.pixivdownload.maintenance;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
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
 */
@RestController
@RequestMapping("/api/admin/maintenance")
@RequiredArgsConstructor
public class MaintenanceController {

    private final ObjectProvider<MaintenanceCoordinator> coordinatorProvider;

    @GetMapping("/status")
    public Map<String, Object> status(HttpServletRequest request) {
        requireLocal(request);
        MaintenanceCoordinator c = coordinatorProvider.getIfAvailable();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", c != null);
        if (c != null) {
            out.put("paused", c.isPaused());
            out.put("lastStartedAt", c.getLastStartedAt());
            out.put("lastFinishedAt", c.getLastFinishedAt());
            out.put("lastTriggeredBy", c.getLastTriggeredBy());
        }
        return out;
    }

    @PostMapping("/run")
    public Map<String, Object> run(HttpServletRequest request) {
        requireLocal(request);
        MaintenanceCoordinator c = coordinatorProvider.getIfAvailable();
        if (c == null) {
            throw new LocalizedException(HttpStatus.SERVICE_UNAVAILABLE,
                    "maintenance.disabled", "维护框架未启用");
        }
        boolean started = c.runManually();
        return Map.of("started", started);
    }

    private void requireLocal(HttpServletRequest request) {
        if (!NetworkUtils.isLocalRequest(request)) {
            throw new LocalizedException(HttpStatus.FORBIDDEN,
                    "auth.local-only", "Forbidden: local access only");
        }
    }
}
