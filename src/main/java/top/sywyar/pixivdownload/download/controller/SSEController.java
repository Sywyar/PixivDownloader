package top.sywyar.pixivdownload.download.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import top.sywyar.pixivdownload.download.DownloadProgressEvent;
import top.sywyar.pixivdownload.download.DownloadStatus;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@RestController
@RequestMapping("/api/sse")
@CrossOrigin(origins = "*")
public class SSEController {

    private final ConcurrentHashMap<Long, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(5);

    // 不再直接依赖DownloadService，通过事件监听器处理进度更新

    /**
     * 为特定作品创建SSE连接，实时推送下载进度
     */
    @GetMapping(value = "/download/{artworkId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter createSSEConnection(@PathVariable Long artworkId) {
        SseEmitter emitter = new SseEmitter(300000L); // 5分钟超时
        emitters.put(artworkId, emitter);

        // 设置完成和超时处理
        emitter.onCompletion(() -> {
            safeRemoveEmitter(artworkId);
            log.info("SSE连接完成: {}", artworkId);
        });

        emitter.onTimeout(() -> {
            safeRemoveEmitter(artworkId);
            log.error("SSE连接超时: {}", artworkId);
        });

        emitter.onError((e) -> {
            safeRemoveEmitter(artworkId);
            log.error("SSE连接错误: {}, {}", artworkId, e.getMessage());
        });

        // 立即发送初始状态
        sendStatusUpdate(artworkId);

        // 定期发送心跳
        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (isEmitterValid(artworkId)) {
                    emitter.send(SseEmitter.event()
                            .id(String.valueOf(System.currentTimeMillis()))
                            .name("heartbeat")
                            .data("ping"));
                }
            } catch (IOException e) {
                safeRemoveEmitter(artworkId);
            }
        }, 30, 30, TimeUnit.SECONDS);

        return emitter;
    }

    /**
     * 安全关闭SSE连接
     */
    @PostMapping("/close/{artworkId}")
    public ResponseEntity<String> closeSSEConnection(@PathVariable Long artworkId) {
        try {
            safeRemoveEmitter(artworkId);
            log.info("SSE连接安全关闭: {}", artworkId);
            return ResponseEntity.ok("SSE连接已安全关闭");
        } catch (Exception e) {
            log.error("关闭SSE连接时出错: {},", artworkId, e);
            return ResponseEntity.status(500).body("关闭连接时出错: " + e.getMessage());
        }
    }

    /**
     * 安全移除emitter，避免重复移除和空指针异常
     */
    private void safeRemoveEmitter(Long artworkId) {
        SseEmitter emitter = emitters.remove(artworkId);
        if (emitter != null) {
            try {
                emitter.complete();
            } catch (Exception e) {
                // 忽略完成时的异常
                log.error("完成emitter时出现异常", e);
            }
        }
    }

    /**
     * 检查emitter是否有效
     */
    private boolean isEmitterValid(Long artworkId) {
        SseEmitter emitter = emitters.get(artworkId);
        return emitter != null;
    }

    /**
     * 发送状态更新到前端
     */
    public void sendStatusUpdate(Long artworkId) {
        SseEmitter emitter = emitters.get(artworkId);
        if (emitter != null) {
            try {
                // 发送简单的心跳状态更新
                Map<String, Object> statusData = new HashMap<>();
                statusData.put("artworkId", artworkId);
                statusData.put("status", "连接中");
                statusData.put("message", "SSE连接已建立");
                statusData.put("success", true);

                emitter.send(SseEmitter.event()
                        .id(String.valueOf(System.currentTimeMillis()))
                        .name("download-status")
                        .data(statusData));
            } catch (IOException e) {
                emitters.remove(artworkId);
            }
        }
    }

    /**
     * 监听下载进度事件并推送实时更新
     */
    @EventListener
    public void handleDownloadProgressEvent(DownloadProgressEvent event) {
        Long artworkId = event.getArtworkId();
        SseEmitter emitter = emitters.get(artworkId);
        if (emitter != null) {
            try {
                // 获取下载状态信息
                DownloadStatus downloadStatus = event.getDownloadStatus();

                // 发送进度更新事件
                Map<String, Object> statusData = new HashMap<>();
                statusData.put("artworkId", artworkId);
                statusData.put("status", "进度更新");
                statusData.put("message", "下载进度已更新");
                statusData.put("success", true);

                // 如果存在下载状态，添加详细信息
                if (downloadStatus != null) {
                    statusData.put("currentImageIndex", downloadStatus.getCurrentImageIndex());
                    statusData.put("totalImages", downloadStatus.getTotalImages());
                    statusData.put("downloadedCount", downloadStatus.getDownloadedCount());
                    statusData.put("completed", downloadStatus.isCompleted());
                    statusData.put("failed", downloadStatus.isFailed());
                    statusData.put("cancelled", downloadStatus.isCancelled());
                    statusData.put("folderName", downloadStatus.getFolderName());

                    // 计算进度百分比
                    if (downloadStatus.getTotalImages() > 0) {
                        int progress = (int) ((double) downloadStatus.getDownloadedCount() / downloadStatus.getTotalImages() * 100);
                        statusData.put("progress", progress);
                    }
                }

                emitter.send(SseEmitter.event()
                        .id(String.valueOf(System.currentTimeMillis()))
                        .name("download-status")
                        .data(statusData));
            } catch (IOException e) {
                emitters.remove(artworkId);
            }
        }
    }

    /**
     * 从DownloadService调用此方法来推送实时更新
     */
    public void notifyProgressUpdate(Long artworkId) {
        handleDownloadProgressEvent(new DownloadProgressEvent(this, artworkId));
    }
}