package top.sywyar.pixivdownload.gui.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.common.NetworkUtils;
import top.sywyar.pixivdownload.gui.controlcenter.DesktopControlCenterRegistry;

/** 读取宿主已物化控制中心快照的本机 GUI 受保护端点。 */
@RestController
@RequestMapping("/api/gui/control-center")
public final class GuiControlCenterController {

    private final DesktopControlCenterRegistry registry;

    public GuiControlCenterController(DesktopControlCenterRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    public ResponseEntity<DesktopControlCenterRegistry.Snapshot> snapshot(HttpServletRequest request) {
        if (!NetworkUtils.isTrustedLocalRequest(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        registry.refresh();
        return ResponseEntity.ok(registry.snapshot());
    }
}
