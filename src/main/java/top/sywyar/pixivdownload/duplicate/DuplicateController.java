package top.sywyar.pixivdownload.duplicate;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;

/**
 * 疑似重复检测接口。路径 {@code /api/duplicates/**} 在 {@code AuthFilter} 中按 monitor
 * 语义保护——solo / multi 两种模式下都仅限已登录管理员访问（不在访客邀请白名单内）。
 * <p>
 * {@code @RestController} 仅供 Spring MVC handler 检测；Bean 本身被
 * {@code @PluginManagedBean} 排除出根包扫描，由 {@link DuplicatePluginConfiguration} 提供。
 */
@PluginManagedBean
@RestController
@RequestMapping("/api/duplicates")
@RequiredArgsConstructor
public class DuplicateController {

    private final DuplicateService duplicateService;
    private final DuplicateScanService duplicateScanService;

    @GetMapping("/groups")
    public DuplicateDto.GroupsPage groups(
            @RequestParam(required = false) Integer threshold,
            @RequestParam(required = false) Integer ahashThreshold,
            @RequestParam(required = false, defaultValue = "cross-artwork") String scope,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        return duplicateService.groups(threshold, ahashThreshold, scope, page, size, duplicateScanService.status());
    }

    @PostMapping("/scan")
    public DuplicateDto.ScanStatus scan(@RequestParam(required = false, defaultValue = "false") boolean force) {
        return duplicateScanService.startScan(force);
    }

    @GetMapping("/scan/status")
    public DuplicateDto.ScanStatus scanStatus() {
        return duplicateScanService.status();
    }

    @PostMapping("/rescan")
    public DuplicateDto.GroupsPage rescan(
            @RequestParam(required = false) Integer threshold,
            @RequestParam(required = false) Integer ahashThreshold,
            @RequestParam(required = false, defaultValue = "cross-artwork") String scope,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        duplicateService.invalidate();
        return duplicateService.groups(threshold, ahashThreshold, scope, page, size, duplicateScanService.status());
    }
}
