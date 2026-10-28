package top.sywyar.pixivdownload.core.download.web;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.i18n.MessageResolver;

/** 保留既有本机连通性探针，不依赖任何下载实现。 */
@RestController
@RequiredArgsConstructor
public final class DownloadHealthController {
    private final MessageResolver messages;

    @GetMapping("/api/download/status")
    public ResponseEntity<DownloadControlResponse> getStatus() {
        return ResponseEntity.ok(DownloadControlResponse.success(messages.get("download.service.healthy")));
    }
}
