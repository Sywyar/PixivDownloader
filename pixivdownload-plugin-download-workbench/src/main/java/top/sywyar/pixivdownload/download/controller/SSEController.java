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
import top.sywyar.pixivdownload.download.DownloadProgressEvent;
import top.sywyar.pixivdownload.download.DownloadStatus;
import top.sywyar.pixivdownload.download.response.DownloadResponse;
import top.sywyar.pixivdownload.download.response.SseStatusData;
import top.sywyar.pixivdownload.plugin.api.stream.PluginStream;
import top.sywyar.pixivdownload.plugin.api.stream.PluginStreamRegistrar;
import top.sywyar.pixivdownload.plugin.api.task.PluginRuntimeTask;
import top.sywyar.pixivdownload.plugin.api.task.PluginRuntimeTaskRegistrar;
import top.sywyar.pixivdownload.plugin.api.task.PluginRuntimeTaskRejectedException;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentity;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentityResolver;
import top.sywyar.pixivdownload.i18n.MessageResolver;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.lang.ref.WeakReference;
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
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@RestController
@RequestMapping("/api/sse")
public class SSEController {

    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final long CLOSE_TOKEN_MAX_AGE_MILLIS = Duration.ofHours(25).toMillis();

    private final TaskScheduler taskScheduler;
    private final RequestOwnerIdentityResolver requestOwnerIdentityResolver;
    private final MessageResolver messages;
    private final PluginStreamRegistrar pluginStreamRegistrar;
    private final PluginRuntimeTaskRegistrar pluginRuntimeTaskRegistrar;
    private final ExecutorService sseProgressExecutor;
    private final SecureRandom secureRandom = new SecureRandom();
    private final byte[] closeTokenKey = new byte[32];

    private final ConcurrentHashMap<String, ArtworkSubscription> emitters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PluginRuntimeTask> heartbeatTasks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AggregatedSubscription> aggregatedEmitters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PluginRuntimeTask> aggregatedHeartbeats = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PendingProgress> pendingProgress = new ConcurrentHashMap<>();
    private final Object progressFlushMonitor = new Object();
    private ProgressFlushHandle progressFlushHandle;

    public SSEController(TaskScheduler taskScheduler,
                         RequestOwnerIdentityResolver requestOwnerIdentityResolver,
                         MessageResolver messages,
                         PluginStreamRegistrar pluginStreamRegistrar,
                         PluginRuntimeTaskRegistrar pluginRuntimeTaskRegistrar) {
        this.taskScheduler = taskScheduler;
        this.requestOwnerIdentityResolver = requestOwnerIdentityResolver;
        this.messages = messages;
        this.pluginStreamRegistrar = pluginStreamRegistrar;
        this.pluginRuntimeTaskRegistrar = pluginRuntimeTaskRegistrar;
        this.secureRandom.nextBytes(closeTokenKey);
        this.sseProgressExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "sse-progress-flush");
            thread.setDaemon(true);
            return thread;
        });
    }

    @PreDestroy
    public void shutdownProgressExecutor() {
        Throwable failure = cancelBackgroundTasks();
        pendingProgress.clear();
        sseProgressExecutor.shutdownNow();
        boolean interrupted = false;
        try {
            while (!sseProgressExecutor.isTerminated()) {
                try {
                    if (sseProgressExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                        break;
                    }
                } catch (InterruptedException interruptedFailure) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        rethrowBackgroundFailure(failure);
    }

    private record ArtworkSubscription(SseEmitter emitter, Long artworkId, String ownerUuid,
                                       boolean admin, Locale locale, String streamToken) {
        private ArtworkSubscription(SseEmitter emitter, Long artworkId, String ownerUuid,
                                    boolean admin, Locale locale) {
            this(emitter, artworkId, ownerUuid, admin, locale, null);
        }
    }

    private record AggregatedSubscription(SseEmitter emitter, String ownerUuid, boolean admin,
                                          Locale locale, String streamToken) {
        private AggregatedSubscription(SseEmitter emitter, String ownerUuid, boolean admin, Locale locale) {
            this(emitter, ownerUuid, admin, locale, null);
        }
    }

    private record PendingProgress(Long artworkId, DownloadStatus downloadStatus, String userUuid) {}

    private record CloseTokenPayload(String connectionId, String ownerUuid, boolean admin, long issuedAtMillis) {}

    private record ProgressFlushHandle(PluginRuntimeTask task, Future<?> cancellation) {}

    @GetMapping(value = "/download/{artworkId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter createSSEConnection(@PathVariable Long artworkId, HttpServletRequest request) {
        SseEmitter emitter = new SseEmitter(300_000L);
        RequestOwnerIdentity identity = requestOwnerIdentityResolver.resolve(request);
        boolean admin = identity.admin();
        String ownerUuid = identity.ownerUuid();
        String connectionId = UUID.randomUUID().toString();
        String subscriptionKey = artworkSubscriptionKey(artworkId, ownerUuid, admin) + "#" + connectionId;
        String streamToken = "artwork:" + connectionId;
        ArtworkSubscription subscription = new ArtworkSubscription(
                emitter, artworkId, ownerUuid, admin, currentRequestLocale(), streamToken);
        emitters.put(subscriptionKey, subscription);

        emitter.onCompletion(() -> {
            cleanupArtworkEmitter(subscriptionKey, subscription);
            log.info(logMessage("sse.log.connection.completed", id(artworkId)));
        });
        emitter.onTimeout(() -> {
            cleanupArtworkEmitter(subscriptionKey, subscription);
            log.error(logMessage("sse.log.connection.timeout", id(artworkId)));
        });
        emitter.onError((e) -> {
            cleanupArtworkEmitter(subscriptionKey, subscription);
            log.debug(logMessage("sse.log.connection.error", id(artworkId), e.getMessage()));
        });
        pluginStreamRegistrar.register(streamToken, unavailableStream(
                emitter,
                messages.get(subscription.locale(), "plugin.unavailable.quiesced")));

        if (!sendStatusUpdate(subscriptionKey)) {
            cleanupArtworkEmitter(subscriptionKey, subscription);
            return emitter;
        }

        try {
            if (!scheduleHeartbeat(subscriptionKey, heartbeatTasks, () -> {
                if (isEmitterValid(subscriptionKey, subscription) && !sendEvent(emitter, SseEmitter.event()
                        .id(String.valueOf(System.currentTimeMillis()))
                        .name("heartbeat")
                        .data("ping"))) {
                    cleanupArtworkEmitter(subscriptionKey, subscription);
                }
            })) {
                cleanupArtworkEmitter(subscriptionKey, subscription);
                return emitter;
            }
        } catch (RuntimeException | Error failure) {
            cleanupArtworkEmitter(subscriptionKey, subscription);
            throw failure;
        }
        if (!isEmitterValid(subscriptionKey, subscription)) {
            cancelHeartbeat(subscriptionKey);
        }

        return emitter;
    }

    @GetMapping(value = "/download", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter createAggregatedSSEConnection(HttpServletRequest request) {
        SseEmitter emitter = new SseEmitter(86_400_000L);
        String connectionId = UUID.randomUUID().toString();
        String streamToken = "aggregated:" + connectionId;
        RequestOwnerIdentity identity = requestOwnerIdentityResolver.resolve(request);
        boolean admin = identity.admin();
        String ownerUuid = identity.ownerUuid();
        AggregatedSubscription subscription = new AggregatedSubscription(
                emitter, ownerUuid, admin, currentRequestLocale(), streamToken);
        aggregatedEmitters.put(connectionId, subscription);

        emitter.onCompletion(() -> {
            cleanupAggregatedEmitter(connectionId, subscription);
            log.debug(logMessage("sse.log.aggregated.completed", connectionId));
        });
        emitter.onTimeout(() -> {
            cleanupAggregatedEmitter(connectionId, subscription);
            log.debug(logMessage("sse.log.aggregated.timeout", connectionId));
        });
        emitter.onError((e) -> {
            cleanupAggregatedEmitter(connectionId, subscription);
            log.debug(logMessage("sse.log.aggregated.error", connectionId, e.getMessage()));
        });
        pluginStreamRegistrar.register(streamToken, unavailableStream(
                emitter,
                messages.get(subscription.locale(), "plugin.unavailable.quiesced")));

        String closeToken = createAggregatedCloseToken(connectionId, ownerUuid, admin, System.currentTimeMillis());
        if (!sendEvent(emitter, SseEmitter.event()
                .id(String.valueOf(System.currentTimeMillis()))
                .name("aggregated-ready")
                .data(closeToken))) {
            cleanupAggregatedEmitter(connectionId, subscription);
            log.debug(logMessage("sse.log.aggregated.initial-send-failed", connectionId, "client disconnected"));
            return emitter;
        }

        try {
            if (!scheduleHeartbeat(connectionId, aggregatedHeartbeats, () -> {
                AggregatedSubscription sub = aggregatedEmitters.get(connectionId);
                if (sub != null && !sendEvent(sub.emitter(), SseEmitter.event()
                        .id(String.valueOf(System.currentTimeMillis()))
                        .name("heartbeat")
                        .data("ping"))) {
                    cleanupAggregatedEmitter(connectionId, subscription);
                }
            })) {
                cleanupAggregatedEmitter(connectionId, subscription);
                return emitter;
            }
        } catch (RuntimeException | Error failure) {
            cleanupAggregatedEmitter(connectionId, subscription);
            throw failure;
        }
        if (aggregatedEmitters.get(connectionId) != subscription) {
            // The connection was already closed while the scheduler was returning its handle.
            cancelAggregatedHeartbeat(connectionId);
        }

        return emitter;
    }

    @PostMapping("/close/{artworkId}")
    public ResponseEntity<DownloadResponse> closeSSEConnection(@PathVariable Long artworkId,
                                                               HttpServletRequest request) {
        RequestOwnerIdentity identity = requestOwnerIdentityResolver.resolve(request);
        boolean admin = identity.admin();
        String ownerUuid = identity.ownerUuid();
        completeArtworkEmitters(artworkId, ownerUuid, admin);
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
        PluginRuntimeTask task = heartbeatTasks.remove(subscriptionKey);
        if (task != null) task.cancel();
    }

    private void cancelAggregatedHeartbeat(String connectionId) {
        PluginRuntimeTask task = aggregatedHeartbeats.remove(connectionId);
        if (task != null) task.cancel();
    }

    private void cleanupArtworkEmitter(String subscriptionKey) {
        ArtworkSubscription subscription = emitters.get(subscriptionKey);
        if (subscription == null) {
            cancelHeartbeat(subscriptionKey);
            return;
        }
        cleanupArtworkEmitter(subscriptionKey, subscription);
    }

    private void cleanupArtworkEmitter(String subscriptionKey, ArtworkSubscription subscription) {
        cancelHeartbeat(subscriptionKey);
        if (emitters.remove(subscriptionKey, subscription)) {
            unregisterPluginStream(subscription.streamToken());
            completeEmitter(subscription.emitter());
        }
    }

    private void completeArtworkEmitters(Long artworkId, String ownerUuid, boolean admin) {
        for (var entry : emitters.entrySet()) {
            ArtworkSubscription subscription = entry.getValue();
            if (Objects.equals(subscription.artworkId(), artworkId)
                    && subscription.admin() == admin
                    && Objects.equals(subscription.ownerUuid(), ownerUuid)) {
                completeArtworkEmitter(entry.getKey(), subscription);
            }
        }
    }

    private void completeArtworkEmitter(String subscriptionKey, ArtworkSubscription subscription) {
        cancelHeartbeat(subscriptionKey);
        if (emitters.remove(subscriptionKey, subscription)) {
            unregisterPluginStream(subscription.streamToken());
            try {
                sendClosingEvent(subscription.emitter(), id(subscription.artworkId()));
            } finally {
                completeEmitter(subscription.emitter());
            }
        }
    }

    private void completeArtworkEmitter(String subscriptionKey) {
        ArtworkSubscription subscription = emitters.get(subscriptionKey);
        if (subscription != null) {
            completeArtworkEmitter(subscriptionKey, subscription);
        }
    }

    private void cleanupAggregatedEmitter(String connectionId) {
        AggregatedSubscription subscription = aggregatedEmitters.get(connectionId);
        if (subscription == null) {
            cancelAggregatedHeartbeat(connectionId);
            return;
        }
        cleanupAggregatedEmitter(connectionId, subscription);
    }

    private void cleanupAggregatedEmitter(String connectionId, AggregatedSubscription subscription) {
        cancelAggregatedHeartbeat(connectionId);
        if (aggregatedEmitters.remove(connectionId, subscription)) {
            unregisterPluginStream(subscription.streamToken());
            completeEmitter(subscription.emitter());
        }
    }

    private void completeAggregatedEmitter(String connectionId) {
        cancelAggregatedHeartbeat(connectionId);
        AggregatedSubscription sub = aggregatedEmitters.remove(connectionId);
        if (sub != null) {
            unregisterPluginStream(sub.streamToken());
            try {
                sendClosingEvent(sub.emitter(), connectionId);
            } finally {
                completeEmitter(sub.emitter());
            }
        }
    }

    private void unregisterPluginStream(String streamToken) {
        if (streamToken != null) {
            pluginStreamRegistrar.unregister(streamToken);
        }
    }

    private boolean isEmitterValid(String subscriptionKey, ArtworkSubscription subscription) {
        return emitters.get(subscriptionKey) == subscription;
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
        if (artworkId == null || (emitters.isEmpty() && aggregatedEmitters.isEmpty())) {
            return;
        }

        pendingProgress.put(progressKey(artworkId, event.getUserUuid()),
                new PendingProgress(artworkId, event.getDownloadStatus(), event.getUserUuid()));
        scheduleProgressFlush();
    }

    private void scheduleProgressFlush() {
        synchronized (progressFlushMonitor) {
            if (progressFlushHandle != null && !progressFlushHandle.cancellation().isDone()) {
                return;
            }
            progressFlushHandle = null;
            if (!pluginRuntimeTaskRegistrar.acceptsNewTasks()) {
                pendingProgress.clear();
                return;
            }
            PluginRuntimeTask task = null;
            try {
                task = pluginRuntimeTaskRegistrar.registerOneShot(this::flushPendingProgress);
                FutureTask<Void> cancellation = new FutureTask<>(task, null);
                progressFlushHandle = new ProgressFlushHandle(task, cancellation);
                task.bindCancellation(cancellation);
                sseProgressExecutor.execute(cancellation);
            } catch (PluginRuntimeTaskRejectedException failure) {
                progressFlushHandle = null;
                pendingProgress.clear();
            } catch (RejectedExecutionException failure) {
                progressFlushHandle = null;
                if (task != null) {
                    try {
                        task.cancel();
                    } catch (Throwable cancellationFailure) {
                        rethrowBackgroundFailure(mergeBackgroundFailure(failure, cancellationFailure));
                    }
                }
                log.warn("SSE progress flush task rejected: {}", failure.getMessage());
            } catch (RuntimeException | Error failure) {
                progressFlushHandle = null;
                Throwable combinedFailure = failure;
                if (task != null) {
                    try {
                        task.cancel();
                    } catch (Throwable cancellationFailure) {
                        combinedFailure = mergeBackgroundFailure(combinedFailure, cancellationFailure);
                    }
                }
                rethrowBackgroundFailure(combinedFailure);
            }
        }
    }

    private boolean scheduleHeartbeat(
            String taskKey,
            ConcurrentHashMap<String, PluginRuntimeTask> tasks,
            Runnable delegate) {
        PluginRuntimeTask task;
        ScheduledFuture<?> cancellation = null;
        try {
            task = pluginRuntimeTaskRegistrar.registerPeriodic(delegate);
        } catch (PluginRuntimeTaskRejectedException ignored) {
            return false;
        }

        tasks.put(taskKey, task);
        try {
            cancellation = taskScheduler.scheduleAtFixedRate(
                    task, Duration.ofSeconds(30));
            if (cancellation == null) {
                throw new RejectedExecutionException("SSE heartbeat scheduler returned no cancellation handle");
            }
            task.bindCancellation(cancellation);
            return true;
        } catch (RuntimeException | Error failure) {
            tasks.remove(taskKey, task);
            Throwable combinedFailure = failure;
            try {
                task.cancel();
            } catch (Throwable cancellationFailure) {
                combinedFailure = mergeBackgroundFailure(combinedFailure, cancellationFailure);
            }
            if (cancellation == null) {
                try {
                    task.discardUnsubmitted();
                } catch (Throwable discardFailure) {
                    combinedFailure = mergeBackgroundFailure(combinedFailure, discardFailure);
                }
            }
            rethrowBackgroundFailure(combinedFailure);
            return false;
        }
    }

    private Throwable cancelBackgroundTasks() {
        Throwable failure = null;
        ProgressFlushHandle progressFlush;
        synchronized (progressFlushMonitor) {
            progressFlush = progressFlushHandle;
            progressFlushHandle = null;
        }
        if (progressFlush != null) {
            try {
                progressFlush.task().cancel();
            } catch (Throwable cancellationFailure) {
                failure = mergeBackgroundFailure(failure, cancellationFailure);
            }
        }
        failure = cancelBackgroundTasks(heartbeatTasks, failure);
        return cancelBackgroundTasks(aggregatedHeartbeats, failure);
    }

    private static Throwable cancelBackgroundTasks(
            ConcurrentHashMap<String, PluginRuntimeTask> tasks,
            Throwable currentFailure) {
        Throwable failure = currentFailure;
        for (var entry : List.copyOf(tasks.entrySet())) {
            try {
                entry.getValue().cancel();
                tasks.remove(entry.getKey(), entry.getValue());
            } catch (Throwable cancellationFailure) {
                failure = mergeBackgroundFailure(failure, cancellationFailure);
            }
        }
        return failure;
    }

    private static Throwable mergeBackgroundFailure(Throwable current, Throwable failure) {
        if (current == null) {
            return failure;
        }
        if (backgroundFailureRank(failure) > backgroundFailureRank(current)) {
            addBackgroundSuppressed(failure, current);
            return failure;
        }
        addBackgroundSuppressed(current, failure);
        return current;
    }

    private static int backgroundFailureRank(Throwable failure) {
        if (failure instanceof VirtualMachineError || failure instanceof ThreadDeath) {
            return 2;
        }
        return failure instanceof Error ? 1 : 0;
    }

    private static void addBackgroundSuppressed(Throwable target, Throwable failure) {
        if (target == failure) {
            return;
        }
        try {
            target.addSuppressed(failure);
        } catch (Throwable ignored) {
            // 诊断附加失败不得覆盖主失败对象。
        }
    }

    private static void rethrowBackgroundFailure(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
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
            synchronized (progressFlushMonitor) {
                progressFlushHandle = null;
            }
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

    private static boolean sendEvent(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
            return true;
        } catch (IOException | IllegalStateException e) {
            return false;
        }
    }

    private static boolean sendClosingEvent(SseEmitter emitter, String id) {
        return sendEvent(emitter, SseEmitter.event()
                .id(String.valueOf(System.currentTimeMillis()))
                .name("sse-closing")
                .data(id));
    }

    private static void completeEmitter(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (IllegalStateException ignored) {
            // The emitter may already be closed by the client.
        }
    }

    /**
     * 构造宿主关闭回调。回调只弱引用传输句柄并捕获已本地化文案；连接 map、heartbeat future、controller、
     * 消息解析器和插件上下文都由 emitter 自身的完成回调清理，不进入宿主注册中心的强引用链。
     * 客户端已断开导致的 send 失败视为传输已关闭，仍完成 emitter，避免宿主误保留回调等待重试。
     */
    private static PluginStream unavailableStream(SseEmitter emitter, String unavailableMessage) {
        WeakReference<SseEmitter> emitterReference = new WeakReference<>(emitter);
        return () -> {
            SseEmitter activeEmitter = emitterReference.get();
            if (activeEmitter == null) {
                return;
            }
            try {
                sendUnavailableEvent(activeEmitter, unavailableMessage);
            } finally {
                completeEmitter(activeEmitter);
            }
        };
    }

    private static boolean sendUnavailableEvent(SseEmitter emitter, String unavailableMessage) {
        return sendEvent(emitter, SseEmitter.event()
                .id(String.valueOf(System.currentTimeMillis()))
                .name("plugin-unavailable")
                .data(unavailableMessage));
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
        if (requestOwnerIdentityResolver.isAdminAuthenticated(request)) {
            return true;
        }
        if (sub.admin()) {
            return false;
        }
        RequestOwnerIdentity identity = requestOwnerIdentityResolver.resolve(request);
        return sub.ownerUuid() != null && sub.ownerUuid().equals(identity.ownerUuid());
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
        return messages.normalizeLocale(LocaleContextHolder.getLocale());
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
