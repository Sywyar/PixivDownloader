package top.sywyar.pixivdownload.download;

import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/download")
@CrossOrigin(origins = "*") // 允许跨域请求
public class DownloadController {

    @Autowired
    private DownloadService downloadService;

    @PostMapping("/pixiv")
    public ResponseEntity<DownloadResponse> downloadPixivImages(@Valid @RequestBody DownloadRequest request) {
        try {
            // 异步处理下载任务
            downloadService.downloadImages(
                    request.getArtworkId(),
                    request.getImageUrls(),
                    request.getReferer(),
                    request.getCookie()  // 传递Cookie
            );

            return ResponseEntity.ok(new DownloadResponse(
                    true,
                    "下载任务已开始处理",
                    "正在下载到作品 " + request.getArtworkId() + " 文件夹",
                    request.getImageUrls().size()
            ));

        } catch (Exception e) {
            return ResponseEntity.badRequest().body(
                    new DownloadResponse(false, "下载请求处理失败: " + e.getMessage())
            );
        }
    }

    @GetMapping("/status")
    public ResponseEntity<DownloadResponse> getStatus() {
        return ResponseEntity.ok(new DownloadResponse(true, "服务运行正常"));
    }
}