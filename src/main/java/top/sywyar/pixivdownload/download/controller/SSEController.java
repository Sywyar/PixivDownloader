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
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

@Slf4j
@RestController
@RequestMapping("/api/sse")
public class SSEController {

    private final TaskScheduler taskScheduler;
    private final SetupService setupService;
    private final AppMessages messages;
    private final ExecutorService sseProgressExecutor;
    private final SecureRandom secureRandom = new SecureRandom();
    private final byte[] closeTokenKey = new byte[32];

    private final ConcurrentHashMap<Long, ArtworkSubscription> emitters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, ScheduledFuture<?>> heartbeatTasks = new ConcurrentHashMap<>();

    /** 聚合订阅：key 为该连接的随机 ID，value 持有 emitter 和该连接的归属信息。
     *  单条连接接收所有作品的 download-status 事件，前端按 artworkId 路由。
     *  在多人模式下，会按 owner UUID 过滤，避免跨用户事件泄漏。 */
    private final ConcurrentHashMap<String, AggregatedSubscription> aggregatedEmitters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ScheduledFuture<?>> aggregatedHeartbeats = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, PendingProgress> pendingProgress = new ConcurrentHashMap<>();
    private final AtomicBoolean progressFlushRunning = new AtomicBoolean(false);
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final long CLOSE_TOKEN_MAX_AGE_MILLIS = Duration.ofHours(25).toMillis();

    public SSEController(TaskScheduler taskScheduler,
                         SetupService setupService,
                         AppMessages messages) {
        this.taskScheduler = taskScheduler;
        this.setupService = setupService;
        this.messages = messages;
        this.secureRandom.nextBytes(closeTokenKey);
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

    private record CloseTokenPayload(String connectionId, String ownerUuid, boolean admin, long issuedAtMillis) {}

    /**
     * 为特定作品创建SSE连接，实时推送下载进度
     */
    @GetMapping(value = "/download/{artworkId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter createSSEConnection(@PathVariable Long artworkId) {
        SseEmitter emitter = new SseEmitter(300000L); // 5分钟超时
        emitters.put(artworkId, new ArtworkSubscription(emitter, currentRequestLocale()));

        // 设置完成和超时处理
        emitter.onCompletion(() -> {
            cleanupArtworkEmitter(artworkId);
            log.info(logMessage("sse.log.connection.completed", id(artworkId)));
        });

        emitter.onTimeout(() -> {
            cleanupArtworkEmitter(artworkId);
            log.error(logMessage("sse.log.connection.timeout", id(artworkId)));
        });

        emitter.onError((e) -> {
            cleanupArtworkEmitter(artworkId);
            log.debug(logMessage("sse.log.connection.error", id(artworkId), e.getMessage()));
        });

        // 立即发送初始状态
        if (!sendStatusUpdate(artworkId)) {
            cleanupArtworkEmitter(artworkId);
            return emitter;
        }

        // 定期发送心跳，记录 Future 以便连接关闭时取消
        ScheduledFuture<?> heartbeat = taskScheduler.scheduleAtFixedRate(() -> {
            if (isEmitterValid(artworkId) && !sendEvent(emitter, SseEmitter.event()
                    .id(String.valueOf(System.currentTimeMillis()))
                    .name("heartbeat")
                    .data("ping"))) {
                cleanupArtworkEmitter(artworkId);
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
            cleanupAggregatedEmitter(connectionId);
            log.debug(logMessage("sse.log.aggregated.completed", connectionId));
        });
        emitter.onTimeout(() -> {
            cleanupAggregatedEmitter(connectionId);
            log.debug(logMessage("sse.log.aggregated.timeout", connectionId));
        });
        emitter.onError((e) -> {
            cleanupAggregatedEmitter(connectionId);
            log.debug(logMessage("sse.log.aggregated.error", connectionId, e.getMessage()));
        });

        // 立即发送一个握手事件，便于前端确认连接成功
        String closeToken = createAggregatedCloseToken(connectionId, ownerUuid, admin, System.currentTimeMillis());
        if (!sendEvent(emitter, SseEmitter.event()
                .id(String.valueOf(System.currentTimeMillis()))
                .name("aggregated-ready")
                .data(closeToken))) {
            cleanupAggregatedEmitter(connectionId);
            log.debug(logMessage("sse.log.aggregated.initial-send-failed", connectionId, "client disconnected"));
            return emitter;
        }

        ScheduledFuture<?> heartbeat = taskScheduler.scheduleAtFixedRate(() -> {
            AggregatedSubscription sub = aggregatedEmitters.get(connectionId);
            if (sub != null && !sendEvent(sub.emitter(), SseEmitter.event()
                    .id(String.valueOf(System.currentTimeMillis()))
                    .name("heartbeat")
                    .data("ping"))) {
                cleanupAggregatedEmitter(connectionId);
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
        completeArtworkEmitter(artworkId);
        log.info(logMessage("sse.log.connection.closed", id(artworkId)));
        return ResponseEntity.ok(DownloadResponse.builder()
                .success(true)
                .message(messages.get("sse.connection.closed"))
                .build());
    }

    @PostMapping("/close/aggregated/{connectionId}")
    public ResponseEntity<DownloadResponse> closeAggregatedSSEConnection(@PathVariable String connectionId,
                                                                         HttpServletRequest request) {
        CloseTokenPayload payload = parseAggregatedCloseToken(connectionId);
        if (payload == null) {
            return ResponseEntity.status(403).body(DownloadResponse.builder()
                    .success(false)
                    .message(messages.get("auth.unauthorized"))
                    .build());
        }
        AggregatedSubscription sub = aggregatedEmitters.get(payload.connectionId());
        if (sub != null && !canCloseAggregatedSubscription(sub, payload, request)) {
            return ResponseEntity.status(403).body(DownloadResponse.builder()
                    .success(false)
                    .message(messages.get("auth.unauthorized"))
                    .build());
        }
        completeAggregatedEmitter(payload.connectionId());
        log.debug(logMessage("sse.log.aggregated.closed", payload.connectionId()));
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

    private void cleanupArtworkEmitter(Long artworkId) {
        cancelHeartbeat(artworkId);
        removeArtworkEmitter(artworkId, false);
    }

    private void completeArtworkEmitter(Long artworkId) {
        cancelHeartbeat(artworkId);
        ArtworkSubscription subscription = emitters.remove(artworkId);
        if (subscription != null && sendClosingEvent(subscription.emitter(), id(artworkId))) {
            completeEmitter(subscription.emitter());
        }
    }

    private void removeArtworkEmitter(Long artworkId, boolean complete) {
        ArtworkSubscription subscription = emitters.remove(artworkId);
        if (complete && subscription != null) {
            completeEmitter(subscription.emitter());
        }
    }

    private void cleanupAggregatedEmitter(String connectionId) {
        cancelAggregatedHeartbeat(connectionId);
        removeAggregatedEmitter(connectionId, false);
    }

    private void completeAggregatedEmitter(String connectionId) {
        cancelAggregatedHeartbeat(connectionId);
        AggregatedSubscription sub = aggregatedEmitters.remove(connectionId);
        if (sub != null && sendClosingEvent(sub.emitter(), connectionId)) {
            completeEmitter(sub.emitter());
        }
    }

    private void removeAggregatedEmitter(String connectionId, boolean complete) {
        AggregatedSubscription sub = aggregatedEmitters.remove(connectionId);
        if (complete && sub != null) {
            completeEmitter(sub.emitter());
        }
    }

    private boolean isEmitterValid(Long artworkId) {
        return emitters.containsKey(artworkId);
    }

    /**
     * 发送初始状态更新到前端
     */
    public boolean sendStatusUpdate(Long artworkId) {
        ArtworkSubscription subscription = emitters.get(artworkId);
        if (subscription == null) return false;
        return sendEvent(subscription.emitter(), SseEmitter.event()
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
            if (!sendEvent(perArtworkSubscription.emitter(), SseEmitter.event()
                    .id(String.valueOf(System.currentTimeMillis()))
                    .name("download-status")
                    .data(buildProgressPayload(
                            artworkId,
                            downloadStatus,
                            perArtworkSubscription.locale()
                    )))) {
                cleanupArtworkEmitter(artworkId);
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
                if (!sendEvent(sub.emitter(), SseEmitter.event()
                        .id(String.valueOf(System.currentTimeMillis()))
                        .name("download-status")
                        .data(buildProgressPayload(artworkId, downloadStatus, sub.locale())))) {
                    cleanupAggregatedEmitter(connectionId);
                }
            }
        }
    }

    private boolean sendEvent(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
            return true;
        } catch (IOException | IllegalStateException e) {
            return false;
        }
    }

    private boolean sendClosingEvent(SseEmitter emitter, String id) {
        return sendEvent(emitter, SseEmitter.event()
                .id(String.valueOf(System.currentTimeMillis()))
                .name("sse-closing")
                .data(id));
    }

    private void completeEmitter(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (IllegalStateException ignored) {
            // emitter 已经完成或关闭，无需再次完成，属于预期情况
        }
    }

    private boolean shouldDeliverToSubscription(AggregatedSubscription sub, String eventOwnerUuid) {
        if (sub.admin()) return true;          // admin 看全部
        if (eventOwnerUuid == null) return true; // 事件无归属信息时回退为全员可见（向后兼容）
        return eventOwnerUuid.equals(sub.ownerUuid());
    }

    private boolean canCloseAggregatedSubscription(AggregatedSubscription sub,
                                                   CloseTokenPayload payload,
                                                   HttpServletRequest request) {
        if (payload.admin() != sub.admin()
                || !Objects.equals(payload.ownerUuid(), sub.ownerUuid())
                || System.currentTimeMillis() - payload.issuedAtMillis() > CLOSE_TOKEN_MAX_AGE_MILLIS) {
            return false;
        }
        if (setupService.isAdminLoggedIn(request)) {
            return true;
        }
        if (sub.admin()) {
            return false;
        }
        return sub.ownerUuid() != null && sub.ownerUuid().equals(UuidUtils.extractOrGenerateUuid(request));
    }

    private String createAggregatedCloseToken(String connectionId, String ownerUuid, boolean admin, long issuedAtMillis) {
        String payload = String.join("|",
                "v1",
                connectionId,
                ownerUuid == null ? "" : ownerUuid,
                String.valueOf(admin),
                String.valueOf(issuedAtMillis));
        byte[] iv = new byte[GCM_IV_BYTES];
        secureRandom.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(closeTokenKey, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            byte[] token = ByteBuffer.allocate(iv.length + encrypted.length)
                    .put(iv)
                    .put(encrypted)
                    .array();
            return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to create SSE close token", e);
        }
    }

    private CloseTokenPayload parseAggregatedCloseToken(String token) {
        if (token == null || token.isBlank() || token.length() > 2048) {
            return null;
        }
        try {
            byte[] tokenBytes = Base64.getUrlDecoder().decode(token);
            if (tokenBytes.length <= GCM_IV_BYTES) {
                return null;
            }
            byte[] iv = new byte[GCM_IV_BYTES];
            byte[] encrypted = new byte[tokenBytes.length - GCM_IV_BYTES];
            System.arraycopy(tokenBytes, 0, iv, 0, GCM_IV_BYTES);
            System.arraycopy(tokenBytes, GCM_IV_BYTES, encrypted, 0, encrypted.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(closeTokenKey, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            String decoded = new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|", -1);
            if (parts.length != 5 || !"v1".equals(parts[0])) {
                return null;
            }
            return new CloseTokenPayload(
                    parts[1],
                    parts[2].isBlank() ? null : parts[2],
                    Boolean.parseBoolean(parts[3]),
                    Long.parseLong(parts[4]));
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            return null;
        }
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
