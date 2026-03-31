package top.sywyar.pixivdownload.download.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.bind.annotation.*;
import top.sywyar.pixivdownload.download.DownloadProgressEvent;
import top.sywyar.pixivdownload.download.DownloadStatus;
import top.sywyar.pixivdownload.download.response.DownloadResponse;
import top.sywyar.pixivdownload.download.response.SseStatusData;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

@Slf4j
@RestController
@RequestMapping("/api/sse")
@RequiredArgsConstructor
public class SSEController {

    private final TaskScheduler taskScheduler;

    private final ConcurrentHashMap<Long, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, ScheduledFuture<?>> heartbeatTasks = new ConcurrentHashMap<>();

    /**
     * 为特定作品创建SSE连接，实时推送下载进度
     */
    @GetMapping(value = "/download/{artworkId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter createSSEConnection(@PathVariable Long artworkId) throws IOException {
        SseEmitter emitter = new SseEmitter(300000L); // 5分钟超时
        emitters.put(artworkId, emitter);

        // 设置完成和超时处理
        emitter.onCompletion(() -> {
            cancelHeartbeat(artworkId);
            safeRemoveEmitter(artworkId);
            log.info("SSE连接完成: {}", artworkId);
        });

        emitter.onTimeout(() -> {
            cancelHeartbeat(artworkId);
            safeRemoveEmitter(artworkId);
            log.error("SSE连接超时: {}", artworkId);
        });

        emitter.onError((e) -> {
            cancelHeartbeat(artworkId);
            safeRemoveEmitter(artworkId);
            log.error("SSE连接错误: {}, {}", artworkId, e.getMessage());
        });

        // 立即发送初始状态
        sendStatusUpdate(artworkId);

        // 定期发送心跳，记录 Future 以便连接关闭时取消
        ScheduledFuture<?> heartbeat = taskScheduler.scheduleAtFixedRate(() -> {
            try {
                if (isEmitterValid(artworkId)) {
                    emitter.send(SseEmitter.event()
                            .id(String.valueOf(System.currentTimeMillis()))
                            .name("heartbeat")
                            .data("ping"));
                }
            } catch (IOException e) {
                cancelHeartbeat(artworkId);
                safeRemoveEmitter(artworkId);
            }
        }, Duration.ofSeconds(30));
        heartbeatTasks.put(artworkId, heartbeat);

        return emitter;
    }

    /**
     * 安全关闭SSE连接
     */
    @PostMapping("/close/{artworkId}")
    public ResponseEntity<DownloadResponse> closeSSEConnection(@PathVariable Long artworkId) {
        safeRemoveEmitter(artworkId);
        log.info("SSE连接安全关闭: {}", artworkId);
        return ResponseEntity.ok(DownloadResponse.builder().success(true).message("SSE连接已安全关闭").build());
    }

    private void cancelHeartbeat(Long artworkId) {
        ScheduledFuture<?> task = heartbeatTasks.remove(artworkId);
        if (task != null) task.cancel(false);
    }

    private void safeRemoveEmitter(Long artworkId) {
        SseEmitter emitter = emitters.remove(artworkId);
        if (emitter != null) {
            try {
                emitter.complete();
            } catch (IllegalStateException ignored) {
                // emitter 已经完成或关闭，无需再次完成，属于预期情况
            }
        }
    }

    private boolean isEmitterValid(Long artworkId) {
        return emitters.containsKey(artworkId);
    }

    /**
     * 发送初始状态更新到前端
     */
    public void sendStatusUpdate(Long artworkId) throws IOException {
        SseEmitter emitter = emitters.get(artworkId);
        if (emitter == null) return;
        SseStatusData data = SseStatusData.builder()
                .artworkId(artworkId)
                .status("连接中")
                .message("SSE连接已建立")
                .success(true)
                .build();
        emitter.send(SseEmitter.event()
                .id(String.valueOf(System.currentTimeMillis()))
                .name("download-status")
                .data(data));
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
                DownloadStatus downloadStatus = event.getDownloadStatus();

                SseStatusData.SseStatusDataBuilder builder = SseStatusData.builder()
                        .artworkId(artworkId)
                        .status("进度更新")
                        .message("下载进度已更新")
                        .success(true);

                if (downloadStatus != null) {
                    builder.currentImageIndex(downloadStatus.getCurrentImageIndex())
                            .totalImages(downloadStatus.getTotalImages())
                            .downloadedCount(downloadStatus.getDownloadedCount())
                            .completed(downloadStatus.isCompleted())
                            .failed(downloadStatus.isFailed())
                            .cancelled(downloadStatus.isCancelled())
                            .folderName(downloadStatus.getFolderName());

                    if (downloadStatus.getTotalImages() > 0) {
                        int progress = (int) ((double) downloadStatus.getDownloadedCount()
                                / downloadStatus.getTotalImages() * 100);
                        builder.progress(progress);
                    }
                }

                emitter.send(SseEmitter.event()
                        .id(String.valueOf(System.currentTimeMillis()))
                        .name("download-status")
                        .data(builder.build()));
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
