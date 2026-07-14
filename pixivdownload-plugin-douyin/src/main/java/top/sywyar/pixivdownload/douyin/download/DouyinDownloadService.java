package top.sywyar.pixivdownload.douyin.download;

import org.springframework.core.task.TaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.core.download.queue.QueueGenerationDrain;
import top.sywyar.pixivdownload.core.download.queue.QueueNotAcceptingException;
import top.sywyar.pixivdownload.core.download.queue.QueueTaskTracker;
import top.sywyar.pixivdownload.douyin.client.DouyinClient;
import top.sywyar.pixivdownload.douyin.client.DouyinCookieValidator;
import top.sywyar.pixivdownload.douyin.client.DouyinClientErrorCode;
import top.sywyar.pixivdownload.douyin.client.DouyinClientException;
import top.sywyar.pixivdownload.douyin.db.history.DouyinHistoryService;
import top.sywyar.pixivdownload.douyin.model.DouyinCanonicalDownload;
import top.sywyar.pixivdownload.douyin.model.DouyinCanonicalKind;
import top.sywyar.pixivdownload.douyin.model.DouyinDownloadPhase;
import top.sywyar.pixivdownload.douyin.model.DouyinDownloadRequest;
import top.sywyar.pixivdownload.douyin.model.DouyinDownloadSnapshot;
import top.sywyar.pixivdownload.douyin.model.DouyinListing;
import top.sywyar.pixivdownload.douyin.model.DouyinParsedInput;
import top.sywyar.pixivdownload.douyin.model.DouyinStartResponse;
import top.sywyar.pixivdownload.douyin.model.DouyinWork;
import top.sywyar.pixivdownload.douyin.parse.DouyinUrlParser;
import top.sywyar.pixivdownload.douyin.settings.DouyinPluginSettingsService;
import top.sywyar.pixivdownload.douyin.settings.DouyinProxyMode;
import top.sywyar.pixivdownload.douyin.settings.DouyinRuntimeSettings;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class DouyinDownloadService {

    private static final Logger log = LoggerFactory.getLogger(DouyinDownloadService.class);
    public static final String QUEUE_TYPE = "douyin";
    private static final int DEFAULT_PAGE_SIZE = 24;

    private final DouyinUrlParser parser;
    private final RuntimePair inheritRuntime;
    private final RuntimePair proxyRuntime;
    private final RuntimePair customRuntime;
    private final RuntimePair directRuntime;
    private final TaskExecutor downloadTaskExecutor;
    private final DouyinPluginSettingsService settingsService;
    private final DouyinHistoryService historyService;
    private final ConcurrentMap<String, MutableStatus> statuses = new ConcurrentHashMap<>();
    private final ConcurrentMap<TaskIdentity, String> runningStatusIds = new ConcurrentHashMap<>();
    private final Object runningLock = new Object();
    private final QueueTaskTracker taskTracker = new QueueTaskTracker(QUEUE_TYPE);

    public DouyinDownloadService(DouyinUrlParser parser,
                                 DouyinClient client,
                                 DouyinMediaDownloader mediaDownloader,
                                 TaskExecutor downloadTaskExecutor) {
        this(parser, client, mediaDownloader, downloadTaskExecutor,
                RuntimeFiles.dataDirectory().resolve("douyin").resolve("downloads").normalize());
    }

    public DouyinDownloadService(DouyinUrlParser parser,
                                 DouyinClient inheritClient,
                                 DouyinClient proxyClient,
                                 DouyinClient directClient,
                                 DouyinMediaDownloader inheritMediaDownloader,
                                 DouyinMediaDownloader proxyMediaDownloader,
                                 DouyinMediaDownloader directMediaDownloader,
                                 TaskExecutor downloadTaskExecutor,
                                 DouyinPluginSettingsService settingsService) {
        this(parser, inheritClient, proxyClient, directClient,
                inheritMediaDownloader, proxyMediaDownloader, directMediaDownloader,
                downloadTaskExecutor, settingsService, null);
    }

    public DouyinDownloadService(DouyinUrlParser parser,
                                 DouyinClient inheritClient,
                                 DouyinClient proxyClient,
                                 DouyinClient directClient,
                                 DouyinMediaDownloader inheritMediaDownloader,
                                 DouyinMediaDownloader proxyMediaDownloader,
                                 DouyinMediaDownloader directMediaDownloader,
                                 TaskExecutor downloadTaskExecutor,
                                 DouyinPluginSettingsService settingsService,
                                 DouyinHistoryService historyService) {
        this(parser, inheritClient, proxyClient, proxyClient, directClient,
                inheritMediaDownloader, proxyMediaDownloader, proxyMediaDownloader, directMediaDownloader,
                downloadTaskExecutor, settingsService, historyService);
    }

    public DouyinDownloadService(DouyinUrlParser parser,
                                 DouyinClient inheritClient,
                                 DouyinClient proxyClient,
                                 DouyinClient customClient,
                                 DouyinClient directClient,
                                 DouyinMediaDownloader inheritMediaDownloader,
                                 DouyinMediaDownloader proxyMediaDownloader,
                                 DouyinMediaDownloader customMediaDownloader,
                                 DouyinMediaDownloader directMediaDownloader,
                                 TaskExecutor downloadTaskExecutor,
                                 DouyinPluginSettingsService settingsService,
                                 DouyinHistoryService historyService) {
        this.parser = parser;
        this.inheritRuntime = new RuntimePair(inheritClient, inheritMediaDownloader);
        this.proxyRuntime = new RuntimePair(proxyClient, proxyMediaDownloader);
        this.customRuntime = new RuntimePair(customClient, customMediaDownloader);
        this.directRuntime = new RuntimePair(directClient, directMediaDownloader);
        this.downloadTaskExecutor = downloadTaskExecutor;
        this.settingsService = settingsService;
        this.historyService = historyService;
    }

    DouyinDownloadService(DouyinUrlParser parser,
                          DouyinClient client,
                          DouyinMediaDownloader mediaDownloader,
                          TaskExecutor downloadTaskExecutor,
                          Path downloadDirectory) {
        this(parser, client, client, client, mediaDownloader, mediaDownloader, mediaDownloader,
                downloadTaskExecutor,
                DouyinPluginSettingsService.fixed(downloadDirectory, DouyinProxyMode.INHERIT),
                null);
    }

    public Optional<DouyinParsedInput> parse(String input) {
        return parser.parse(input);
    }

    public DouyinStartResponse start(DouyinDownloadRequest request, String ownerUuid) throws DouyinClientException {
        String ownerScope = normalizeOwnerScope(ownerUuid);
        QueueTaskTracker.Task task = taskTracker.beginRunning(ownerScope);
        boolean submitted = false;
        try {
            String input = request == null ? "" : request.input();
            String cookie = request == null ? null : request.cookie();
            DouyinCookieValidator.ensureUsable(cookie);
            DouyinRuntimeSettings runtimeSettings = settingsService.runtimeSettings();
            RuntimePair runtime = runtimeFor(runtimeSettings.proxyMode());
            DouyinCanonicalDownload canonical = runtime.client().resolveDownload(input, cookie);
            TaskIdentity identity = new TaskIdentity(ownerScope, canonical.stableKey());
            MutableStatus status;
            synchronized (runningLock) {
                String runningStatusId = runningStatusIds.get(identity);
                MutableStatus running = runningStatusId == null ? null : statuses.get(runningStatusId);
                if (running != null && running.isRunning()) {
                    if (task.isCancellationRequested()) {
                        throw new QueueNotAcceptingException(QUEUE_TYPE);
                    }
                    return new DouyinStartResponse(true, running.id, running.workId, running.messageKey);
                }
                if (runningStatusId != null) {
                    runningStatusIds.remove(identity, runningStatusId);
                }
                String statusId = UUID.randomUUID().toString();
                status = new MutableStatus(statusId, identity, canonical.kind(),
                        canonical.stableId(), numericId(canonical.stableId()));
                status.title = safeTitle(request == null ? null : request.title(), canonical.stableId());
                status.originalInput = input;
                status.canonicalUrl = canonical.canonicalUrl();
                status.cookie = cookie;
                status.collectionId = canonical.kind() == DouyinCanonicalKind.COLLECTION
                        ? canonical.stableId()
                        : request == null ? null : request.collectionId();
                status.collectionTitle = request == null ? null : request.collectionTitle();
                status.preResolvedWork = canonical.preResolvedWork();
                status.runtime = runtime;
                status.downloadDirectory = runtimeSettings.downloadDirectory();
                task.onCancellation(() -> cancelTrackedStatus(status));
                if (!task.publishIfActive(() -> {
                    statuses.put(statusId, status);
                    runningStatusIds.put(identity, statusId);
                })) {
                    throw new QueueNotAcceptingException(QUEUE_TYPE);
                }
            }
            if (!task.handoff(() -> run(status))) {
                throw new QueueNotAcceptingException(QUEUE_TYPE);
            }
            try {
                downloadTaskExecutor.execute(task);
            } catch (RuntimeException | Error failure) {
                task.rejectSubmission();
                removeStatus(status);
                throw failure;
            }
            submitted = true;
            if (task.isCancellationRequested()) {
                throw new QueueNotAcceptingException(QUEUE_TYPE);
            }
            return new DouyinStartResponse(true, status.id, status.workId, "douyin.status.queued");
        } finally {
            if (!submitted) {
                task.completeRunning();
            }
        }
    }

    public Optional<DouyinDownloadSnapshot> status(String id, String ownerUuid, boolean admin) {
        MutableStatus status = statuses.get(id);
        return status == null || (!admin && !status.ownedBy(normalizeOwnerScope(ownerUuid)))
                ? Optional.empty()
                : Optional.of(status.snapshot());
    }

    public List<DouyinDownloadSnapshot> active(String ownerUuid, boolean admin) {
        return statuses.values().stream()
                .filter(MutableStatus::isRunning)
                .filter(status -> admin || status.ownedBy(normalizeOwnerScope(ownerUuid)))
                .map(MutableStatus::snapshot)
                .toList();
    }

    public DouyinListing listUserWorks(String userId, int offset, int limit, String cookie) throws DouyinClientException {
        DouyinCookieValidator.ensureUsable(cookie);
        return currentRuntime().client().listUserWorks(userId, Math.max(0, offset), positiveLimit(limit), cookie);
    }

    public DouyinListing listSeriesWorks(String seriesId, int page, int pageSize, String cookie) throws DouyinClientException {
        DouyinCookieValidator.ensureUsable(cookie);
        return currentRuntime().client().listSeriesWorks(seriesId, Math.max(1, page), positiveLimit(pageSize), cookie);
    }

    public DouyinListing searchPublic(String word, int page, int pageSize, String cookie) throws DouyinClientException {
        DouyinCookieValidator.ensureUsable(cookie);
        return currentRuntime().client().searchPublic(word == null ? "" : word,
                Math.max(1, page), positiveLimit(pageSize), cookie);
    }

    public DouyinListing quickPublic(int page, int pageSize, String cookie) throws DouyinClientException {
        DouyinCookieValidator.ensureUsable(cookie);
        return currentRuntime().client().searchPublic("", Math.max(1, page), positiveLimit(pageSize), cookie);
    }

    public int clearAll() {
        int count = statuses.size();
        int cancelledTasks = 0;
        Throwable failure = null;
        try {
            cancelledTasks = taskTracker.cancelActive();
        } catch (Throwable error) {
            failure = error;
        }
        try {
            cancelAndClearStatuses();
        } catch (Throwable error) {
            failure = mergeFailure(failure, error);
        }
        rethrow(failure);
        return count > 0 ? count : cancelledTasks;
    }

    public int clearForOwner(String ownerUuid) {
        String ownerScope = normalizeOwnerScope(ownerUuid);
        int cancelledTasks = 0;
        int cleared = 0;
        Throwable failure = null;
        try {
            cancelledTasks = taskTracker.cancelForOwner(ownerScope);
        } catch (Throwable error) {
            failure = error;
        }
        for (MutableStatus status : List.copyOf(statuses.values())) {
            if (!status.ownedBy(ownerScope)) {
                continue;
            }
            cleared++;
            try {
                status.cancel();
            } catch (Throwable error) {
                failure = mergeFailure(failure, error);
            }
            try {
                removeStatus(status);
            } catch (Throwable error) {
                failure = mergeFailure(failure, error);
            }
        }
        rethrow(failure);
        return cleared > 0 ? cleared : cancelledTasks;
    }

    /** 先停止接收并取得唯一 drain；本方法不执行插件 callback。 */
    public QueueGenerationDrain prepareQuiesceDownloads() {
        return taskTracker.prepareQuiesce();
    }

    /** drain 已由生命周期保存后，再取消本代任务并清理状态。 */
    public void cancelQuiescedDownloads() {
        Throwable failure = null;
        try {
            taskTracker.cancelQuiescedTasks();
        } catch (Throwable error) {
            failure = error;
        }
        try {
            cancelAndClearStatuses();
        } catch (Throwable error) {
            failure = mergeFailure(failure, error);
        }
        rethrow(failure);
    }

    private void cancelAndClearStatuses() {
        Throwable failure = null;
        for (MutableStatus status : List.copyOf(statuses.values())) {
            try {
                status.cancel();
            } catch (Throwable error) {
                failure = mergeFailure(failure, error);
            }
        }
        statuses.clear();
        runningStatusIds.clear();
        rethrow(failure);
    }

    public void cancel(long numericWorkId, String ownerUuid, boolean admin) {
        String ownerScope = normalizeOwnerScope(ownerUuid);
        statuses.values().stream()
                .filter(status -> status.numericId == numericWorkId)
                .forEach(status -> {
                    if (admin || status.ownedBy(ownerScope)) {
                        status.cancel();
                        runningStatusIds.remove(status.identity, status.id);
                    }
                });
    }

    private void run(MutableStatus status) {
        try {
            failIfCancelled(status);
            List<DouyinDownloadedFile> files = status.kind == DouyinCanonicalKind.COLLECTION
                    ? downloadCollection(status)
                    : downloadSingleWork(status);
            if (files.isEmpty()) {
                throw new DouyinClientException(DouyinClientErrorCode.MEDIA_URL_MISSING,
                        "Douyin download produced no files");
            }
            status.fileName = files.size() == 1
                    ? files.get(0).path().getFileName().toString()
                    : files.get(0).path().getParent().getFileName().toString();
            status.phase = DouyinDownloadPhase.COMPLETED;
            status.messageKey = "douyin.status.completed";
        } catch (DouyinClientException e) {
            if (e.code() == DouyinClientErrorCode.CANCELLED) {
                status.phase = DouyinDownloadPhase.CANCELLED;
                status.messageKey = "douyin.status.cancelled";
                return;
            }
            status.phase = DouyinDownloadPhase.FAILED;
            status.errorCode = e.code().name();
            status.messageKey = messageKey(e.code());
            log.info("Douyin download failed: statusId={}, code={}, message={}",
                    status.id, e.code(), e.getMessage());
        } catch (IOException e) {
            status.phase = DouyinDownloadPhase.FAILED;
            status.errorCode = DouyinClientErrorCode.NETWORK_ERROR.name();
            status.messageKey = messageKey(DouyinClientErrorCode.NETWORK_ERROR);
            log.warn("Douyin media download failed: statusId={}", status.id, e);
        } catch (Cancelled ignored) {
            status.phase = DouyinDownloadPhase.CANCELLED;
            status.messageKey = "douyin.status.cancelled";
        } catch (RuntimeException e) {
            status.phase = DouyinDownloadPhase.FAILED;
            status.errorCode = "UNKNOWN";
            status.messageKey = "douyin.error.unknown";
            log.warn("Douyin download failed unexpectedly: statusId={}", status.id, e);
        } finally {
            runningStatusIds.remove(status.identity, status.id);
        }
    }

    private List<DouyinDownloadedFile> downloadSingleWork(MutableStatus status) throws IOException, DouyinClientException {
        status.phase = DouyinDownloadPhase.RESOLVING;
        status.messageKey = "douyin.status.resolving";
        DouyinWork work = status.preResolvedWork == null
                ? status.runtime.client().resolvePublicWork(status.canonicalUrl, status.cookie)
                : status.preResolvedWork;
        status.title = safeTitle(work.title(), status.workId);
        failIfCancelled(status);
        status.phase = DouyinDownloadPhase.DOWNLOADING;
        status.messageKey = "douyin.status.downloading";
        Path outputDirectory = outputDirectory(status, work);
        List<DouyinDownloadedFile> files = status.runtime.mediaDownloader().download(work.media(), outputDirectory,
                status::isCancelled, status.cookie);
        failIfCancelled(status);
        recordHistory(status, work, outputDirectory, files, null);
        return files;
    }

    private List<DouyinDownloadedFile> downloadCollection(MutableStatus status)
            throws IOException, DouyinClientException {
        status.phase = DouyinDownloadPhase.RESOLVING;
        status.messageKey = "douyin.status.resolving";
        failIfCancelled(status);
        status.phase = DouyinDownloadPhase.DOWNLOADING;
        status.messageKey = "douyin.status.downloading";
        List<DouyinDownloadedFile> all = new ArrayList<>();
        Set<String> downloadedWorkIds = new LinkedHashSet<>();
        int page = 1;
        int collectionOrder = 0;
        boolean last = false;
        while (!last && downloadedWorkIds.size() < 100) {
            failIfCancelled(status);
            DouyinListing listing = status.runtime.client().listSeriesWorks(status.workId, page, 20, status.cookie);
            status.collectionId = status.workId;
            if (listing.title() != null && !listing.title().isBlank()) {
                status.collectionTitle = listing.title();
                status.title = listing.title();
            }
            int downloadedBeforePage = downloadedWorkIds.size();
            for (DouyinWork work : listing.items()) {
                if (work == null || work.id() == null || work.id().isBlank()
                        || !downloadedWorkIds.add(work.id())) {
                    continue;
                }
                failIfCancelled(status);
                Path outputDirectory = outputDirectory(status, work);
                List<DouyinDownloadedFile> files = status.runtime.mediaDownloader().download(
                        work.media(), outputDirectory, status::isCancelled, status.cookie);
                failIfCancelled(status);
                recordHistory(status, work, outputDirectory, files, collectionOrder);
                collectionOrder++;
                all.addAll(files);
                if (downloadedWorkIds.size() >= 100) {
                    break;
                }
            }
            last = listing.lastPage() || listing.items().isEmpty()
                    || downloadedWorkIds.size() == downloadedBeforePage;
            page++;
        }
        return all;
    }

    private void recordHistory(MutableStatus status,
                               DouyinWork work,
                               Path outputDirectory,
                               List<DouyinDownloadedFile> files,
                               Integer collectionOrder) {
        if (historyService == null) {
            return;
        }
        try {
            historyService.recordCompleted(work, outputDirectory, files,
                    status.originalInput, status.collectionId, status.collectionTitle, collectionOrder);
        } catch (RuntimeException e) {
            log.warn("Douyin history record failed: statusId={}, workId={}", status.id, work == null ? null : work.id(), e);
        }
    }

    private Path outputDirectory(MutableStatus status, DouyinWork work) {
        String owner = sanitize(status.identity.ownerScope());
        Path ownerDirectory = status.downloadDirectory.resolve(owner).normalize();
        String collectionTitle = firstNonBlank(status.collectionTitle, work.collectionTitle());
        if (collectionTitle != null) {
            ownerDirectory = ownerDirectory.resolve(sanitize(firstNonBlank(status.collectionId, work.collectionId(), "collection"))
                    + "-" + sanitize(collectionTitle)).normalize();
        }
        String title = sanitize(firstNonBlank(work.title(), status.title, work.id()));
        return ownerDirectory.resolve(sanitize(work.id()) + "-" + title).normalize();
    }

    private static void failIfCancelled(MutableStatus status) {
        if (status.cancelled) {
            throw new Cancelled();
        }
    }

    private static int positiveLimit(int limit) {
        return limit > 0 ? Math.min(limit, 100) : DEFAULT_PAGE_SIZE;
    }

    private static long numericId(String workId) {
        String value = workId == null ? "" : workId.trim();
        if (value.matches("\\d{1,18}")) {
            return Long.parseLong(value);
        }
        return Integer.toUnsignedLong(value.hashCode());
    }

    private static String safeTitle(String title, String fallbackId) {
        if (title == null || title.isBlank()) {
            return "Douyin " + fallbackId;
        }
        return title.trim();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static String sanitize(String raw) {
        String value = raw == null ? "" : raw.trim();
        String sanitized = value.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]+", "_")
                .replaceAll("\\s+", " ")
                .trim();
        if (sanitized.isBlank()) {
            return "unknown";
        }
        return sanitized.length() > 80 ? sanitized.substring(0, 80) : sanitized;
    }

    private static String messageKey(DouyinClientErrorCode code) {
        return "douyin.error." + code.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private RuntimePair currentRuntime() {
        return runtimeFor(settingsService.runtimeSettings().proxyMode());
    }

    private RuntimePair runtimeFor(DouyinProxyMode proxyMode) {
        return switch (proxyMode == null ? DouyinProxyMode.INHERIT : proxyMode) {
            case DIRECT -> directRuntime;
            case PROXY -> proxyRuntime;
            case CUSTOM -> customRuntime;
            case INHERIT -> inheritRuntime;
        };
    }

    private void removeStatus(MutableStatus status) {
        statuses.remove(status.id, status);
        runningStatusIds.remove(status.identity, status.id);
    }

    private void cancelTrackedStatus(MutableStatus status) {
        status.cancel();
        removeStatus(status);
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    private static Throwable mergeFailure(Throwable current, Throwable failure) {
        if (current == null) {
            return failure;
        }
        if (failureRank(failure) > failureRank(current)) {
            addSuppressedSafely(failure, current);
            return failure;
        }
        addSuppressedSafely(current, failure);
        return current;
    }

    private static int failureRank(Throwable failure) {
        if (failure instanceof VirtualMachineError || failure instanceof ThreadDeath) {
            return 2;
        }
        return failure instanceof Error ? 1 : 0;
    }

    private static void addSuppressedSafely(Throwable target, Throwable failure) {
        if (target == failure) {
            return;
        }
        try {
            target.addSuppressed(failure);
        } catch (Throwable ignored) {
            // 诊断附加失败不得覆盖主失败对象。
        }
    }

    private static String normalizeOwnerScope(String ownerUuid) {
        return ownerUuid == null || ownerUuid.isBlank() ? "admin" : ownerUuid.trim();
    }

    private record TaskIdentity(String ownerScope, String stableKey) {
    }

    private record RuntimePair(DouyinClient client, DouyinMediaDownloader mediaDownloader) {

        private RuntimePair {
            if (client == null || mediaDownloader == null) {
                throw new IllegalArgumentException("Douyin runtime pair must be complete");
            }
        }
    }

    private static final class MutableStatus {
        private final String id;
        private final TaskIdentity identity;
        private final DouyinCanonicalKind kind;
        private final String workId;
        private final long numericId;
        private volatile String originalInput;
        private volatile String canonicalUrl;
        private volatile String cookie;
        private volatile DouyinDownloadPhase phase = DouyinDownloadPhase.QUEUED;
        private volatile String messageKey = "douyin.status.queued";
        private volatile String errorCode;
        private volatile String title;
        private volatile String fileName;
        private volatile String collectionId;
        private volatile String collectionTitle;
        private volatile RuntimePair runtime;
        private volatile Path downloadDirectory;
        private volatile DouyinWork preResolvedWork;
        private volatile boolean cancelled;

        private MutableStatus(String id,
                              TaskIdentity identity,
                              DouyinCanonicalKind kind,
                              String workId,
                              long numericId) {
            this.id = id;
            this.identity = identity;
            this.kind = kind;
            this.workId = workId;
            this.numericId = numericId;
        }

        private void cancel() {
            cancelled = true;
            if (phase != DouyinDownloadPhase.COMPLETED && phase != DouyinDownloadPhase.FAILED) {
                phase = DouyinDownloadPhase.CANCELLED;
                messageKey = "douyin.status.cancelled";
            }
        }

        private boolean ownedBy(String ownerScope) {
            return identity.ownerScope().equals(ownerScope);
        }

        private boolean isRunning() {
            return phase != DouyinDownloadPhase.COMPLETED
                    && phase != DouyinDownloadPhase.FAILED
                    && phase != DouyinDownloadPhase.CANCELLED;
        }

        private boolean isCancelled() {
            return cancelled;
        }

        private DouyinDownloadSnapshot snapshot() {
            return new DouyinDownloadSnapshot(
                    id,
                    workId,
                    phase,
                    phase == DouyinDownloadPhase.COMPLETED
                            || phase == DouyinDownloadPhase.FAILED
                            || phase == DouyinDownloadPhase.CANCELLED,
                    phase == DouyinDownloadPhase.FAILED,
                    phase == DouyinDownloadPhase.CANCELLED,
                    messageKey,
                    errorCode,
                    title,
                    fileName);
        }
    }

    private static final class Cancelled extends RuntimeException {
    }
}
