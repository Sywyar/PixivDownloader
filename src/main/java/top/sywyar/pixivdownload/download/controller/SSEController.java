package top.sywyar.pixivdownload.download.controller;

import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import top.sywyar.pixivdownload.common.UuidUtils;
import top.sywyar.pixivdownload.download.DownloadProgressEvent;
import top.sywyar.pixivdownload.download.DownloadStatus;
import top.sywyar.pixivdownload.download.response.DownloadResponse;
import top.sywyar.pixivdownload.download.response.SseStatusData;
import top.sywyar.pixivdownload.i18n.AppLocale;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.setup.SetupService;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
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

@Slf4j
@RestController
@RequestMapping("/api/sse")
public class SSEController {

    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final long CLOSE_TOKEN_MAX_AGE_MILLIS = Duration.ofHours(25).toMillis();

    private final TaskScheduler taskScheduler;
    private final SetupService setupService;
    private final AppMessages messages;
    private final ExecutorService sseProgressExecutor;
    private final SecureRandom secureRandom = new SecureRandom();
    private final byte[] closeTokenKey = new byte[32];

    private final ConcurrentHashMap<String, ArtworkSubscription> emitters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ScheduledFuture<?>> heartbeatTasks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AggregatedSubscription> aggregatedEmitters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ScheduledFuture<?>> aggregatedHeartbeats = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PendingProgress> pendingProgress = new ConcurrentHashMap<>();
    private final AtomicBoolean progressFlushRunning = new AtomicBoolean(false);

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

    private record ArtworkSubscription(SseEmitter emitter, Long artworkId, String ownerUuid,
                                       boolean admin, Locale locale) {}

    private record AggregatedSubscription(SseEmitter emitter, String ownerUuid, boolean admin, Locale locale) {}

    private record PendingProgress(Long artworkId, DownloadStatus downloadStatus, String userUuid) {}

    private record CloseTokenPayload(String connectionId, String ownerUuid, boolean admin, long issuedAtMillis) {}

    @GetMapping(value = "/download/{artworkId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter createSSEConnection(@PathVariable Long artworkId, HttpServletRequest request) {
        SseEmitter emitter = new SseEmitter(300_000L);
        boolean admin = setupService.hasAdminScope(request);
        String ownerUuid = admin ? null : UuidUtils.extractOrGenerateUuid(request);
        String subscriptionKey = artworkSubscriptionKey(artworkId, ownerUuid, admin);
        emitters.put(subscriptionKey, new ArtworkSubscription(
                emitter, artworkId, ownerUuid, admin, currentRequestLocale()));

        emitter.onCompletion(() -> {
            cleanupArtworkEmitter(subscriptionKey);
            log.info(logMessage("sse.log.connection.completed", id(artworkId)));
        });
        emitter.onTimeout(() -> {
            cleanupArtworkEmitter(subscriptionKey);
            log.error(logMessage("sse.log.connection.timeout", id(artworkId)));
        });
        emitter.onError((e) -> {
            cleanupArtworkEmitter(subscriptionKey);
            log.debug(logMessage("sse.log.connection.error", id(artworkId), e.getMessage()));
        });

        if (!sendStatusUpdate(subscriptionKey)) {
            cleanupArtworkEmitter(subscriptionKey);
            return emitter;
        }

        ScheduledFuture<?> heartbeat = taskScheduler.scheduleAtFixedRate(() -> {
            if (isEmitterValid(subscriptionKey) && !sendEvent(emitter, SseEmitter.event()
                    .id(String.valueOf(System.currentTimeMillis()))
                    .name("heartbeat")
                    .data("ping"))) {
                cleanupArtworkEmitter(subscriptionKey);
            }
        }, Duration.ofSeconds(30));
        heartbeatTasks.put(subscriptionKey, heartbeat);

        return emitter;
    }

    @GetMapping(value = "/download", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter createAggregatedSSEConnection(HttpServletRequest request) {
        SseEmitter emitter = new SseEmitter(86_400_000L);
        String connectionId = UUID.randomUUID().toString();
        boolean admin = setupService.hasAdminScope(request);
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

    @PostMapping("/close/{artworkId}")
    public ResponseEntity<DownloadResponse> closeSSEConnection(@PathVariable Long artworkId,
                                                               HttpServletRequest request) {
        boolean admin = setupService.hasAdminScope(request);
        String ownerUuid = admin ? null : UuidUtils.extractOrGenerateUuid(request);
        completeArtworkEmitter(artworkSubscriptionKey(artworkId, ownerUuid, admin));
        log.info(logMessage("sse.log.connection.closed", id(artworkId)));
        return ResponseEntity.ok(DownloadResponse.builder()
                .success(true)
                .message(messages.get("sse.connection.closed"))
                .build());
    }

    @PostMapping("/close/aggregated/{token}")
    public ResponseEntity<DownloadResponse> closeAggregatedSSEConnection(@PathVariable String token,
                                                                         HttpServletRequest request) {
        CloseTokenPayload payload = parseAggregatedCloseToken(token);
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

    private void cancelHeartbeat(String subscriptionKey) {
        ScheduledFuture<?> task = heartbeatTasks.remove(subscriptionKey);
        if (task != null) task.cancel(false);
    }

    private void cancelAggregatedHeartbeat(String connectionId) {
        ScheduledFuture<?> task = aggregatedHeartbeats.remove(connectionId);
        if (task != null) task.cancel(false);
    }

    private void cleanupArtworkEmitter(String subscriptionKey) {
        cancelHeartbeat(subscriptionKey);
        removeArtworkEmitter(subscriptionKey, false);
    }

    private void completeArtworkEmitter(String subscriptionKey) {
        cancelHeartbeat(subscriptionKey);
        ArtworkSubscription subscription = emitters.remove(subscriptionKey);
        if (subscription != null && sendClosingEvent(subscription.emitter(), id(subscription.artworkId()))) {
            completeEmitter(subscription.emitter());
        }
    }

    private void removeArtworkEmitter(String subscriptionKey, boolean complete) {
        ArtworkSubscription subscription = emitters.remove(subscriptionKey);
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

    private boolean isEmitterValid(String subscriptionKey) {
        return emitters.containsKey(subscriptionKey);
    }

    public boolean sendStatusUpdate(Long artworkId) {
        boolean sent = false;
        for (ArtworkSubscription subscription : emitters.values()) {
            if (Objects.equals(subscription.artworkId(), artworkId)) {
                sent |= sendConnectionEstablished(subscription);
            }
        }
        return sent;
    }

    private boolean sendStatusUpdate(String subscriptionKey) {
        ArtworkSubscription subscription = emitters.get(subscriptionKey);
        return subscription != null && sendConnectionEstablished(subscription);
    }

    private boolean sendConnectionEstablished(ArtworkSubscription subscription) {
        return sendEvent(subscription.emitter(), SseEmitter.event()
                .id(String.valueOf(System.currentTimeMillis()))
                .name("download-status")
                .data(buildConnectionEstablishedPayload(subscription.artworkId(), subscription.locale())));
    }

    @EventListener
    public void handleDownloadProgressEvent(DownloadProgressEvent event) {
        Long artworkId = event.getArtworkId();
        if (artworkId == null) {
            return;
        }

        pendingProgress.put(progressKey(artworkId, event.getUserUuid()),
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
                List<String> keys = new ArrayList<>(pendingProgress.keySet());
                for (String key : keys) {
                    PendingProgress progress = pendingProgress.remove(key);
                    if (progress == null) {
                        continue;
                    }
                    try {
                        sendProgressUpdate(progress);
                    } catch (RuntimeException e) {
                        log.warn("SSE progress update failed: artworkId={}, error={}",
                                progress.artworkId(), e.getMessage());
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
        String eventOwner = progress.userUuid();

        if (!emitters.isEmpty()) {
            for (var entry : emitters.entrySet()) {
                String subscriptionKey = entry.getKey();
                ArtworkSubscription sub = entry.getValue();
                if (!Objects.equals(sub.artworkId(), artworkId)
                        || !shouldDeliverToSubscription(sub.admin(), sub.ownerUuid(), eventOwner)) {
                    continue;
                }
                if (!sendEvent(sub.emitter(), SseEmitter.event()
                        .id(String.valueOf(System.currentTimeMillis()))
                        .name("download-status")
                        .data(buildProgressPayload(artworkId, downloadStatus, sub.locale())))) {
                    cleanupArtworkEmitter(subscriptionKey);
                }
            }
        }

        if (!aggregatedEmitters.isEmpty()) {
            for (var entry : aggregatedEmitters.entrySet()) {
                String connectionId = entry.getKey();
                AggregatedSubscription sub = entry.getValue();
                if (!shouldDeliverToSubscription(sub, eventOwner)) {
                    continue;
                }
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
            // The emitter may already be closed by the client.
        }
    }

    private boolean shouldDeliverToSubscription(AggregatedSubscription sub, String eventOwnerUuid) {
        return shouldDeliverToSubscription(sub.admin(), sub.ownerUuid(), eventOwnerUuid);
    }

    private boolean shouldDeliverToSubscription(boolean admin, String ownerUuid, String eventOwnerUuid) {
        if (admin) {
            return true;
        }
        return eventOwnerUuid != null && eventOwnerUuid.equals(ownerUuid);
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

    public void notifyProgressUpdate(Long artworkId) {
        handleDownloadProgressEvent(new DownloadProgressEvent(this, artworkId));
    }

    private Locale currentRequestLocale() {
        return AppLocale.normalize(LocaleContextHolder.getLocale());
    }

    private String artworkSubscriptionKey(Long artworkId, String ownerUuid, boolean admin) {
        return (admin ? "admin" : "user:" + ownerUuid) + ":" + artworkId;
    }

    private String progressKey(Long artworkId, String ownerUuid) {
        return (ownerUuid == null ? "admin" : ownerUuid) + ":" + artworkId;
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
