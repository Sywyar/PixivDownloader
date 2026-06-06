package top.sywyar.pixivdownload.gui.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.common.NetworkUtils;
import top.sywyar.pixivdownload.download.db.PathPrefixMigrationService;
import top.sywyar.pixivdownload.download.db.PathPrefixMigrationService.PathPrefixMigrationResult;
import top.sywyar.pixivdownload.download.db.PathPrefixMigrationService.PathPrefixUpdate;
import top.sywyar.pixivdownload.download.db.PathPrefixMigrationService.PathPrefixView;

import java.util.List;

/**
 * GUI 专用「迁移下载目录」REST 接口。
 * 与 {@link GuiStatusController} / {@link UpdateController} 同级，路径位于 {@code /api/gui/**}
 * （已在 AuthFilter 放行）；控制器内部通过 {@link NetworkUtils#isTrustedLocalRequest} 限制只接受本机请求。
 *
 * <p>只改写 {@code path_prefixes} 记录，不移动磁盘文件。
 */
@RestController
@RequestMapping("/api/gui/path-prefixes")
@RequiredArgsConstructor
@Slf4j
public class PathPrefixController {

    private final PathPrefixMigrationService migrationService;

    @GetMapping
    public ResponseEntity<PathPrefixListResponse> list(HttpServletRequest req) {
        if (!NetworkUtils.isTrustedLocalRequest(req)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(new PathPrefixListResponse(migrationService.list()));
    }

    @PostMapping
    public ResponseEntity<PathPrefixMigrationResult> apply(@RequestBody(required = false) PathPrefixMigrationRequest body,
                                                           HttpServletRequest req) {
        if (!NetworkUtils.isTrustedLocalRequest(req)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        List<PathPrefixUpdate> updates = body == null || body.updates() == null ? List.of() : body.updates();
        List<String> registerPaths = body == null || body.registerPaths() == null ? List.of() : body.registerPaths();
        return ResponseEntity.ok(migrationService.apply(updates, registerPaths));
    }

    public record PathPrefixListResponse(List<PathPrefixView> prefixes) {
    }

    public record PathPrefixMigrationRequest(List<PathPrefixUpdate> updates, List<String> registerPaths) {
    }
}
