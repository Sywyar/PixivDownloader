package top.sywyar.pixivdownload.duplicate;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
