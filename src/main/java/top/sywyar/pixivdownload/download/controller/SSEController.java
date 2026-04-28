package top.sywyar.pixivdownload.download.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.bind.annotation.*;
import top.sywyar.pixivdownload.common.UuidUtils;
import top.sywyar.pixivdownload.download.DownloadProgressEvent;
import top.sywyar.pixivdownload.download.DownloadStatus;
import top.sywyar.pixivdownload.download.response.DownloadResponse;
import top.sywyar.pixivdownload.download.response.SseStatusData;
import top.sywyar.pixivdownload.i18n.AppLocale;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.setup.SetupService;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@RestController
@RequestMapping("/api/sse")
public class SSEController {

    private final TaskScheduler taskScheduler;
    private final SetupService setupService;
    private final AppMessages messages;
    private final ExecutorService sseProgressExecutor;

    private final ConcurrentHashMap<Long, ArtworkSubscription> emitters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, ScheduledFuture<?>> heartbeatTasks = new ConcurrentHashMap<>();

    /** 聚合订阅：key 为该连接的随机 ID，value 持有 emitter 和该连接的归属信息。
     *  单条连接接收所有作品的 download-status 事件，前端按 artworkId 路由。
     *  在多人模式下，会按 owner UUID 过滤，避免跨用户事件泄漏。 */
    private final ConcurrentHashMap<String, AggregatedSubscription> aggregatedEmitters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ScheduledFuture<?>> aggregatedHeartbeats = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, PendingProgress> pendingProgress = new ConcurrentHashMap<>();
    private final AtomicBoolean progressFlushRunning = new AtomicBoolean(false);

    public SSEController(TaskScheduler taskScheduler,
                         SetupService setupService,
                         AppMessages messages) {
        this.taskScheduler = taskScheduler;
        this.setupService = setupService;
        this.messages = messages;
        this.sseProgressExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "sse-progress-flush");
            thread.setDaemon(true);
            return thread;
        });
    }

    @PreDestroy
    public void shutdownProgressExecutor() {
        sseProgressExecutor.shutdownNow();
    }

    private record ArtworkSubscription(SseEmitter emitter, Locale locale) {}

    /** 聚合连接的归属信息：admin 可见所有事件；非 admin 仅可见自己 UUID 的事件。 */
    private record AggregatedSubscription(SseEmitter emitter, String ownerUuid, boolean admin, Locale locale) {}

    private record PendingProgress(Long artworkId, DownloadStatus downloadStatus, String userUuid) {}

    /**
     * 为特定作品创建SSE连接，实时推送下载进度
     */
    @GetMapping(value = "/download/{artworkId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter createSSEConnection(@PathVariable Long artworkId) throws IOException {
        SseEmitter emitter = new SseEmitter(300000L); // 5分钟超时
        emitters.put(artworkId, new ArtworkSubscription(emitter, currentRequestLocale()));

        // 设置完成和超时处理
        emitter.onCompletion(() -> {
            cancelHeartbeat(artworkId);
            safeRemoveEmitter(artworkId);
            log.info(logMessage("sse.log.connection.completed", id(artworkId)));
        });

        emitter.onTimeout(() -> {
            cancelHeartbeat(artworkId);
            safeRemoveEmitter(artworkId);
            log.error(logMessage("sse.log.connection.timeout", id(artworkId)));
        });

        emitter.onError((e) -> {
            cancelHeartbeat(artworkId);
            safeRemoveEmitter(artworkId);
            log.error(logMessage("sse.log.connection.error", id(artworkId), e.getMessage()));
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
     * 聚合 SSE 端点：单条连接接收所有进行中作品的下载状态事件，
     * 前端根据事件载荷里的 artworkId 自行路由分发。
     * 用于解决浏览器对每作品 1 条 SSE 时的连接数瓶颈。
     *
     * 多人模式下按 owner UUID 过滤事件，避免跨用户进度泄漏；
     * 已登录的 admin（含 solo 模式下的登录用户）可看到所有事件。
     */
    @GetMapping(value = "/download", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter createAggregatedSSEConnection(HttpServletRequest request) {
        // 使用一个长得多的超时（24h），让前端在批量下载期间始终保持单条连接
        SseEmitter emitter = new SseEmitter(86_400_000L);
        String connectionId = UUID.randomUUID().toString();
        boolean admin = setupService.isAdminLoggedIn(request);
        // 与 AuthFilter.ensureUserUuidCookie 保持一致：当请求尚未携带 pixiv_user_id cookie 时
        // （首次连接时 AuthFilter 通过 Set-Cookie 设置的值不会出现在当前 request 上），
        // 用同样的 fingerprint 算法兜底，确保 owner UUID 与 DownloadController 看到的 UUID 一致
        String ownerUuid = admin ? null : UuidUtils.extractOrGenerateUuid(request);
        aggregatedEmitters.put(connectionId, new AggregatedSubscription(
                emitter, ownerUuid, admin, currentRequestLocale()));

        emitter.onCompletion(() -> {
            cancelAggregatedHeartbeat(connectionId);
            aggregatedEmitters.remove(connectionId);
            log.debug(logMessage("sse.log.aggregated.completed", connectionId));
        });
        emitter.onTimeout(() -> {
            cancelAggregatedHeartbeat(connectionId);
            safeRemoveAggregatedEmitter(connectionId);
            log.debug(logMessage("sse.log.aggregated.timeout", connectionId));
        });
        emitter.onError((e) -> {
            cancelAggregatedHeartbeat(connectionId);
            aggregatedEmitters.remove(connectionId);
            log.debug(logMessage("sse.log.aggregated.error", connectionId, e.getMessage()));
        });

        // 立即发送一个握手事件，便于前端确认连接成功
        try {
            emitter.send(SseEmitter.event()
                    .id(String.valueOf(System.currentTimeMillis()))
                    .name("aggregated-ready")
                    .data(connectionId));
        } catch (IOException e) {
            aggregatedEmitters.remove(connectionId);
            log.warn(logMessage("sse.log.aggregated.initial-send-failed", connectionId, e.getMessage()));
        }

        ScheduledFuture<?> heartbeat = taskScheduler.scheduleAtFixedRate(() -> {
            try {
                AggregatedSubscription sub = aggregatedEmitters.get(connectionId);
                if (sub != null) {
                    sub.emitter().send(SseEmitter.event()
                            .id(String.valueOf(System.currentTimeMillis()))
                            .name("heartbeat")
                            .data("ping"));
                }
            } catch (IOException e) {
                cancelAggregatedHeartbeat(connectionId);
                aggregatedEmitters.remove(connectionId);
            }
        }, Duration.ofSeconds(30));
        aggregatedHeartbeats.put(connectionId, heartbeat);

        return emitter;
    }

    /**
     * 安全关闭SSE连接
     */
    @PostMapping("/close/{artworkId}")
    public ResponseEntity<DownloadResponse> closeSSEConnection(@PathVariable Long artworkId) {
        safeRemoveEmitter(artworkId);
        log.info(logMessage("sse.log.connection.closed", id(artworkId)));
        return ResponseEntity.ok(DownloadResponse.builder()
                .success(true)
                .message(messages.get("sse.connection.closed"))
                .build());
    }

    private void cancelHeartbeat(Long artworkId) {
        ScheduledFuture<?> task = heartbeatTasks.remove(artworkId);
        if (task != null) task.cancel(false);
    }

    private void cancelAggregatedHeartbeat(String connectionId) {
        ScheduledFuture<?> task = aggregatedHeartbeats.remove(connectionId);
        if (task != null) task.cancel(false);
    }

    private void safeRemoveEmitter(Long artworkId) {
        ArtworkSubscription subscription = emitters.remove(artworkId);
        if (subscription != null) {
            try {
                subscription.emitter().complete();
            } catch (IllegalStateException ignored) {
                // emitter 已经完成或关闭，无需再次完成，属于预期情况
            }
        }
    }

    private void safeRemoveAggregatedEmitter(String connectionId) {
        AggregatedSubscription sub = aggregatedEmitters.remove(connectionId);
        if (sub != null) {
            try {
                sub.emitter().complete();
            } catch (IllegalStateException ignored) {
                // 已完成，属于预期情况
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
        ArtworkSubscription subscription = emitters.get(artworkId);
        if (subscription == null) return;
        subscription.emitter().send(SseEmitter.event()
                .id(String.valueOf(System.currentTimeMillis()))
                .name("download-status")
                .data(buildConnectionEstablishedPayload(artworkId, subscription.locale())));
    }

    /**
     * 监听下载进度事件并推送实时更新
     */
    @EventListener
    public void handleDownloadProgressEvent(DownloadProgressEvent event) {
        Long artworkId = event.getArtworkId();
        if (artworkId == null) {
            return;
        }

        pendingProgress.put(artworkId,
                new PendingProgress(artworkId, event.getDownloadStatus(), event.getUserUuid()));
        scheduleProgressFlush();
    }

    private void scheduleProgressFlush() {
        if (!progressFlushRunning.compareAndSet(false, true)) {
            return;
        }
        try {
            sseProgressExecutor.execute(this::flushPendingProgress);
        } catch (RejectedExecutionException e) {
            progressFlushRunning.set(false);
            log.warn("SSE progress flush task rejected: {}", e.getMessage());
        }
    }

    private void flushPendingProgress() {
        try {
            while (!pendingProgress.isEmpty()) {
                List<PendingProgress> batch = new ArrayList<>(pendingProgress.values());
                for (PendingProgress progress : batch) {
                    if (pendingProgress.remove(progress.artworkId(), progress)) {
                        try {
                            sendProgressUpdate(progress);
                        } catch (RuntimeException e) {
                            log.warn("SSE progress update failed: artworkId={}, error={}",
                                    progress.artworkId(), e.getMessage());
                        }
                    }
                }
            }
        } finally {
            progressFlushRunning.set(false);
            if (!pendingProgress.isEmpty()) {
                scheduleProgressFlush();
            }
        }
    }

    private void sendProgressUpdate(PendingProgress progress) {
        Long artworkId = progress.artworkId();
        DownloadStatus downloadStatus = progress.downloadStatus();

        // 1) 发送给订阅了该作品的旧式 per-artwork emitter（向后兼容）
        ArtworkSubscription perArtworkSubscription = emitters.get(artworkId);
        if (perArtworkSubscription != null) {
            try {
                perArtworkSubscription.emitter().send(SseEmitter.event()
                        .id(String.valueOf(System.currentTimeMillis()))
                        .name("download-status")
                        .data(buildProgressPayload(
                                artworkId,
                                downloadStatus,
                                perArtworkSubscription.locale()
                        )));
            } catch (IOException | IllegalStateException e) {
                cancelHeartbeat(artworkId);
                safeRemoveEmitter(artworkId);
            }
        }

        // 2) 按 owner UUID 过滤后发送给聚合 emitter
        //    admin 订阅看到全部；非 admin 仅看到自己 UUID 的事件；事件无 owner 时回退为全员可见
        if (!aggregatedEmitters.isEmpty()) {
            String eventOwner = progress.userUuid();
            for (var entry : aggregatedEmitters.entrySet()) {
                String connectionId = entry.getKey();
                AggregatedSubscription sub = entry.getValue();
                if (!shouldDeliverToSubscription(sub, eventOwner)) continue;
                try {
                    sub.emitter().send(SseEmitter.event()
                            .id(String.valueOf(System.currentTimeMillis()))
                            .name("download-status")
                            .data(buildProgressPayload(artworkId, downloadStatus, sub.locale())));
                } catch (IOException | IllegalStateException e) {
                    cancelAggregatedHeartbeat(connectionId);
                    safeRemoveAggregatedEmitter(connectionId);
                }
            }
        }
    }

    private boolean shouldDeliverToSubscription(AggregatedSubscription sub, String eventOwnerUuid) {
        if (sub.admin()) return true;          // admin 看全部
        if (eventOwnerUuid == null) return true; // 事件无归属信息时回退为全员可见（向后兼容）
        return eventOwnerUuid.equals(sub.ownerUuid());
    }

    /**
     * 从DownloadService调用此方法来推送实时更新
     */
    public void notifyProgressUpdate(Long artworkId) {
        handleDownloadProgressEvent(new DownloadProgressEvent(this, artworkId));
    }

    private Locale currentRequestLocale() {
        return AppLocale.normalize(LocaleContextHolder.getLocale());
    }

    private SseStatusData buildConnectionEstablishedPayload(Long artworkId, Locale locale) {
        return SseStatusData.builder()
                .artworkId(artworkId)
                .status(messages.get(locale, "sse.connection.connecting"))
                .message(messages.get(locale, "sse.connection.established"))
                .success(true)
                .build();
    }

    private SseStatusData buildProgressPayload(Long artworkId, DownloadStatus downloadStatus, Locale locale) {
        SseStatusData.SseStatusDataBuilder builder = SseStatusData.builder()
                .artworkId(artworkId)
                .status(messages.get(locale, "sse.progress.status"))
                .message(messages.get(locale, "sse.progress.updated"))
                .success(true);

        if (downloadStatus != null) {
            builder.currentImageIndex(downloadStatus.getCurrentImageIndex())
                    .totalImages(downloadStatus.getTotalImages())
                    .downloadedCount(downloadStatus.getDownloadedCount())
                    .completed(downloadStatus.isCompleted())
                    .failed(downloadStatus.isFailed())
                    .cancelled(downloadStatus.isCancelled())
                    .folderName(downloadStatus.getFolderName())
                    .bookmarkResult(downloadStatus.getBookmarkResult())
                    .collectionResult(downloadStatus.getCollectionResult())
                    .ugoiraProgress(downloadStatus.getUgoiraProgress())
                    .imageProgress(downloadStatus.getImageProgress());

            if (downloadStatus.getTotalImages() > 0) {
                int progress = (int) ((double) downloadStatus.getDownloadedCount()
                        / downloadStatus.getTotalImages() * 100);
                builder.progress(progress);
            }
        }

        return builder.build();
    }

    private String logMessage(String code, Object... args) {
        return messages.getForLog(code, args);
    }

    private String id(Long value) {
        return value == null ? "null" : String.valueOf(value);
    }
}
