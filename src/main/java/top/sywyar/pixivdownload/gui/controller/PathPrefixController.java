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
import top.sywyar.pixivdownload.core.db.PathPrefixMigrationService;
import top.sywyar.pixivdownload.core.db.PathPrefixMigrationService.PathPrefixMigrationResult;
import top.sywyar.pixivdownload.core.db.PathPrefixMigrationService.PathPrefixUpdate;
import top.sywyar.pixivdownload.core.db.PathPrefixMigrationService.PathPrefixView;

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
        PathPrefixMigrationService.SymbolicRootStatus symbolic = migrationService.symbolicRootStatus();
        return ResponseEntity.ok(new PathPrefixListResponse(
                migrationService.list(), migrationService.appRootAbsolute(),
                symbolic.referenced(), symbolic.orphan(), symbolic.suggestedOldPath()));
    }

    /**
     * 把全部符号根 {@code {0}} 引用固定为指向给定路径的 {@code {N}} 前缀（pin）。
     * 两个调用场景：GUI 配置页修改下载根目录前冻结旧记录（路径 = 符号根当前解析结果）；
     * GUI 启动检查发现孤儿 {@code {0}} 行后按使用者输入的旧路径修复。
     */
    @PostMapping("/pin")
    public ResponseEntity<PathPrefixMigrationResult> pin(@RequestBody(required = false) PinRequest body,
                                                         HttpServletRequest req) {
        if (!NetworkUtils.isTrustedLocalRequest(req)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(migrationService.pinSymbolicRoot(body == null ? null : body.path()));
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

    /**
     * {@code appRoot}：软件根目录（后端工作目录）绝对路径，供 GUI 判断新下载根是否仍在软件目录内。
     * {@code symbolicReferenced}：数据库是否存在 {@code {0}} 引用行（决定改下载根目录时是否需要弹「固定旧记录」确认）。
     * {@code symbolicOrphan}：root-folder 已不满足符号根条件但仍有 {@code {0}} 行（GUI 启动检查据此引导修复）。
     * {@code symbolicOrphanSuggestedPath}：marker 记录的上次解析路径，作为修复时的预填建议（可能为 null）。
     */
    public record PathPrefixListResponse(List<PathPrefixView> prefixes, String appRoot,
                                         boolean symbolicReferenced, boolean symbolicOrphan,
                                         String symbolicOrphanSuggestedPath) {
    }

    public record PathPrefixMigrationRequest(List<PathPrefixUpdate> updates, List<String> registerPaths) {
    }

    public record PinRequest(String path) {
    }
}
