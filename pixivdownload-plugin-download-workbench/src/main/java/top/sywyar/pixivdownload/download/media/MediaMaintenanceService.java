package top.sywyar.pixivdownload.download.media;

import top.sywyar.pixivdownload.core.asset.ArtworkMediaStore;
import top.sywyar.pixivdownload.core.work.model.WorkAssetFile;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.work.service.WorkAssetService;
import top.sywyar.pixivdownload.core.work.service.WorkMetadataRepository;
import top.sywyar.pixivdownload.core.work.service.WorkQueryService;
import top.sywyar.pixivdownload.core.work.query.WorkQuery;
import top.sywyar.pixivdownload.core.asset.ArtworkMediaManifest;
import top.sywyar.pixivdownload.download.web.LocalizedException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import top.sywyar.pixivdownload.plugin.api.task.PluginRuntimeTask;
import top.sywyar.pixivdownload.plugin.api.task.PluginRuntimeTaskRegistrar;

/** 只有持有最近一次预览凭据的主动请求才能启动；一个实例同时只运行一项任务。 */
public final class MediaMaintenanceService implements AutoCloseable {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(MediaMaintenanceService.class);
    static final int MAX_WORKS = 500;
    static final int MAX_FILES = 10_000;
    private final WorkAssetService assets;
    private final WorkMetadataRepository metadata;
    private final WorkQueryService query;
    private final ArtworkMediaStore mediaStore;
    private final top.sywyar.pixivdownload.core.database.DatabaseAvailability database;
    private final top.sywyar.pixivdownload.i18n.MessageResolver messages;
    private final ImageOutputService images;
    private final top.sywyar.pixivdownload.download.UgoiraService animations;
    private final PluginRuntimeTaskRegistrar tasks;
    private PluginRuntimeTask pendingTask;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "media-maintenance");
        thread.setDaemon(true);
        return thread;
    });
    private Plan preview;
    private volatile boolean cancelled;
    private volatile Status status = new Status("idle", 0, 0, 0, List.of());
    private boolean closed;
    private Thread worker;
    private java.util.function.Consumer<Status> completion;
    private java.util.concurrent.CompletableFuture<Preview> pendingPreview;

    public MediaMaintenanceService(WorkAssetService assets, WorkMetadataRepository metadata, WorkQueryService query, ImageOutputService images,
                                   top.sywyar.pixivdownload.download.UgoiraService animations, ArtworkMediaStore mediaStore,
                                   top.sywyar.pixivdownload.core.database.DatabaseAvailability database,
                                   top.sywyar.pixivdownload.i18n.MessageResolver messages, PluginRuntimeTaskRegistrar tasks) {
        this.assets = assets;
        this.metadata = metadata;
        this.query = query;
        this.images = images;
        this.animations = animations;
        this.mediaStore = mediaStore;
        this.database = java.util.Objects.requireNonNull(database);
        this.messages = messages;
        this.tasks = java.util.Objects.requireNonNull(tasks);
    }

    public record Request(String imageFormats, String ugoiraFormats, boolean repairThumbnails) {}
    public record Item(long artworkId, int page, String fileName, List<String> missingFormats, boolean missingThumbnail) {}
    public record Preview(String token, List<Item> files, int scanned, int skipped, boolean limited) {}
    public record Failure(long artworkId, int page) {}
    public record Status(String state, int total, int completed, int failed, List<Failure> failures) {}
    private record Candidate(long artworkId, WorkAssetFile file, long bytes, long modified,
                             List<String> formats, boolean thumbnail, boolean animation, boolean unavailable) {}
    private record Plan(String token, List<Candidate> files) {}

    public synchronized java.util.concurrent.CompletableFuture<Preview> preview(Request request) {
        database.requireAvailable();
        requireIdle();
        preview = null;
        if (request == null) throw LocalizedException.badRequest("download.media.invalid-formats", null);
        try {
            MediaOutputSettings.parseFormats(request.imageFormats(), MediaOutputSettings.IMAGE_FORMATS);
            if (request.ugoiraFormats() != null) MediaOutputSettings.parseFormats(request.ugoiraFormats(), MediaOutputSettings.UGOIRA_FORMATS);
        }
        catch (IllegalArgumentException invalid) { throw LocalizedException.badRequest("download.media.invalid-formats", null); }
        cancelled = false;
        status = new Status("scanning", 0, 0, 0, List.of());
        var result = new java.util.concurrent.CompletableFuture<Preview>();
        pendingPreview = result;
        try { submit(() -> {
            synchronized (this) { worker = Thread.currentThread(); }
            Preview value = null;
            Throwable error = null;
            try { value = scan(request); }
            catch (VirtualMachineError | ThreadDeath fatal) { result.completeExceptionally(fatal); throw fatal; }
            catch (Throwable failure) { error = cancelled ? new CancellationException() : failure; }
            finally { synchronized (this) { worker = null; pendingPreview = null; pendingTask = null; } }
            if (error == null) result.complete(value);
            else result.completeExceptionally(error);
        }, () -> {
            synchronized (this) {
                pendingPreview = null;
                pendingTask = null;
                status = new Status("cancelled", 0, 0, 0, List.of());
            }
            result.completeExceptionally(new CancellationException());
        }); } catch (RuntimeException | Error failure) {
            pendingPreview = null;
            status = new Status("idle", 0, 0, 0, List.of());
            throw failure;
        }
        return result;
    }

    private Preview scan(Request request) throws IOException {
        List<Candidate> files = new ArrayList<>();
        int scanned = 0;
        int skipped = 0;
        int matched = 0;
        boolean limited = false;
        try {
            // 固定首批总页数，避免持续下载使本次只读扫描永不结束。
            int pages = 1;
            scan: for (int page = 0; page < pages; page++) {
                checkCancelled();
                var batch = query.search(WorkQuery.builder(WorkType.ARTWORK).page(page).size(100)
                        .sort("artworkId").order("asc").build());
                checkCancelled();
                if (page == 0) pages = batch.totalPages();
                for (var work : batch.content()) {
                    checkCancelled();
                    scanned++;
                    int before = files.size();
                    long id = work.workId();
                    if (assets.isReadOnly(WorkType.ARTWORK, id)) { skipped++; continue; }
                    var asset = assets.findAsset(WorkType.ARTWORK, id);
                    if (asset.isPresent() && !asset.get().files().isEmpty()) {
                        for (WorkAssetFile file : asset.get().files()) {
                            checkCancelled();
                            if (files.size() >= MAX_FILES) { limited = true; break scan; }
                            try {
                                Candidate candidate = inspect(id, file, request);
                                if (candidate != null) {
                                    if (candidate.unavailable()) skipped++;
                                    if (!candidate.formats().isEmpty() || candidate.thumbnail()) files.add(candidate);
                                }
                            } catch (IOException unavailable) {
                                if (sharedFailure(unavailable)) throw unavailable;
                                LOG.warn("{} artwork={} page={}", messages.getForLog("desktop.ui.tools.operation-failed"),
                                        id, file.page(), unavailable);
                                skipped++;
                            }
                        }
                    } else skipped++;
                    status = new Status("scanning", (int) Math.min(Integer.MAX_VALUE, batch.totalElements()), scanned, skipped, List.of());
                    if (files.size() > before && ++matched >= MAX_WORKS) {
                        limited = scanned < batch.totalElements();
                        break scan;
                    }
                }
            }
            synchronized (this) {
                checkCancelled();
                preview = files.isEmpty() ? null : new Plan(UUID.randomUUID().toString(), List.copyOf(files));
                return new Preview(preview == null ? "" : preview.token(), files.stream()
                        .map(item -> new Item(item.artworkId(), item.file().page(), item.file().path().getFileName().toString(),
                                item.formats(), item.thumbnail())).toList(), scanned, skipped, limited);
            }
        } finally {
            status = new Status(!database.available() ? "unavailable" : cancelled ? "cancelled" : "idle", 0, 0, 0, List.of());
        }
    }

    private Candidate inspect(long id, WorkAssetFile file, Request request) throws IOException {
        requirePlain(file.path());
        String name = file.path().getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 1) throw new IOException("Invalid media filename");
        String base = name.substring(0, dot).replaceFirst("_thumb$", "");
        Path stem = file.path().resolveSibling(base);
        var manifest = mediaStore.find(id, file.page());
        checkCancelled();
        boolean animation = manifest.map(value -> value.originalExtension().equals("zip")).orElse(false)
                || List.of("gif", "apng", "mp4", "zip").contains(file.extension());
        if (!animation && file.extension().equals("webp")) {
            try (var input = Files.newInputStream(file.path())) {
                byte[] header = input.readNBytes(21);
                animation = header.length == 21
                        && new String(header, 12, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("VP8X")
                        && (header[20] & 2) != 0;
            }
        }
        List<String> missing = new ArrayList<>();
        String formats = animation ? request.ugoiraFormats() : request.imageFormats();
        if (formats != null) {
            for (String format : MediaOutputSettings.parseFormats(formats,
                    animation ? MediaOutputSettings.UGOIRA_FORMATS : MediaOutputSettings.IMAGE_FORMATS)) {
                // 已移除的原图不可从有损副本恢复；原始格式选项只要求保留已有原图。
                if (format.equals("original")) continue;
                if (!Files.exists(stem.resolveSibling(base + "." + format))
                        && !(format.equals("jpg") && Files.exists(stem.resolveSibling(base + ".jpeg")))) missing.add(format);
            }
        }
        boolean unavailable = false;
        if (!missing.isEmpty() && animation) {
            Path zip = stem.resolveSibling(base + ".zip");
            Path timing = stem.resolveSibling(base + ".frames.properties");
            if (manifest.isEmpty() || !manifest.get().originalExtension().equals("zip")
                    || !Files.isRegularFile(zip, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isRegularFile(timing, LinkOption.NOFOLLOW_LINKS)) {
                unavailable = true;
                missing.clear();
            }
        }
        boolean thumbnail = request.repairThumbnails() && assets.existingThumbnail(WorkType.ARTWORK, id, file.page()).isEmpty();
        if (missing.isEmpty() && !thumbnail && !unavailable) return null;
        return new Candidate(id, file, Files.size(file.path()), Files.getLastModifiedTime(file.path()).toMillis(),
                List.copyOf(missing), thumbnail, animation, unavailable);
    }

    private boolean shouldStop() { return cancelled || !database.available(); }

    private void checkCancelled() {
        database.requireAvailable();
        if (cancelled || Thread.currentThread().isInterrupted()) {
            cancelled = true;
            throw new CancellationException();
        }
    }

    public synchronized Status start(String token) {
        return start(token, ignored -> {});
    }

    public synchronized Status start(String token, java.util.function.Consumer<Status> completion) {
        database.requireAvailable();
        requireIdle();
        if (preview == null || !preview.token().equals(token)) throw LocalizedException.badRequest("download.media.preview-required", null);
        java.util.Objects.requireNonNull(completion);
        Plan plan = preview;
        preview = null;
        cancelled = false;
        this.completion = completion;
        status = new Status("running", plan.files().size(), 0, 0, List.of());
        Status accepted = status;
        try { submit(() -> {
            synchronized (this) { worker = Thread.currentThread(); }
            try { run(plan); }
            finally { completeRun(status); }
        }, () -> completeRun(new Status("cancelled", plan.files().size(), 0, 0, List.of())));
        } catch (RuntimeException | Error failure) {
            this.completion = null;
            preview = plan;
            status = new Status("idle", 0, 0, 0, List.of());
            throw failure;
        }
        return accepted;
    }

    private void submit(Runnable action, Runnable cancelledBeforeStart) {
        var claimed = new java.util.concurrent.atomic.AtomicBoolean();
        var task = tasks.registerOneShot(() -> {
            if (claimed.compareAndSet(false, true)) action.run();
        });
        var future = new java.util.concurrent.FutureTask<Void>(task, null) {
            @Override protected void setException(Throwable failure) {
                super.setException(failure);
                // 此 Future 只用于取消，没有 get 调用方负责传播进程级错误。
                if (failure instanceof VirtualMachineError fatal) throw fatal;
                if (failure instanceof ThreadDeath fatal) throw fatal;
            }

            @Override public boolean cancel(boolean mayInterruptIfRunning) {
                boolean unstarted = false;
                synchronized (MediaMaintenanceService.this) {
                    if (pendingTask == task) {
                        MediaMaintenanceService.this.cancel();
                        unstarted = claimed.compareAndSet(false, true);
                    }
                }
                // 排空会等待 preview 调用返回，不能把排队取消的结果留给 context.close。
                if (unstarted) cancelledBeforeStart.run();
                return super.cancel(false);
            }
        };
        pendingTask = task;
        try { executor.execute(future); }
        catch (RuntimeException | Error failure) {
            pendingTask = null;
            task.discardUnsubmitted();
            throw failure;
        }
        task.bindCancellation(future);
    }

    public Status status() { return status; }
    public synchronized void cancel() {
        cancelled = true;
        preview = null;
        if (worker != null) worker.interrupt();
    }

    private void run(Plan plan) {
        int completed = 0;
        List<Failure> failures = new ArrayList<>();
        boolean failed = true;
        try {
            for (Candidate candidate : plan.files()) {
                checkCancelled();
                WorkAssetFile file = candidate.file();
                try (var workFileLease = top.sywyar.pixivdownload.core.work.service.WorkFileLock.acquire(WorkType.ARTWORK, candidate.artworkId())) {
                    checkCancelled();
                    requirePlain(file.path());
                    var current = assets.rawFile(WorkType.ARTWORK, candidate.artworkId(), file.page());
                    if (assets.isReadOnly(WorkType.ARTWORK, candidate.artworkId())
                            || metadata.find(WorkType.ARTWORK, candidate.artworkId()).isEmpty() || current.isEmpty()
                            || !current.get().path().equals(file.path()) || Files.size(file.path()) != candidate.bytes()
                            || Files.getLastModifiedTime(file.path()).toMillis() != candidate.modified()) {
                        throw new IOException("Source changed after preview");
                    }
                    checkCancelled();
                    if (!candidate.formats().isEmpty()) {
                        String formats = String.join(",", candidate.formats());
                        if (candidate.animation()) {
                            if (!animations.addMissingFormats(candidate.artworkId(), file.path(), formats, this::shouldStop)) {
                                throw new IOException("Animation source unavailable");
                            }
                        } else images.addMissingFormats(candidate.artworkId(), file.page(), file.path(), formats, this::shouldStop);
                    }
                    checkCancelled();
                    if (candidate.thumbnail()
                            && assets.thumbnail(WorkType.ARTWORK, candidate.artworkId(), file.page()).isEmpty()) {
                        throw new IOException("Thumbnail unavailable");
                    }
                    checkCancelled();
                } catch (top.sywyar.pixivdownload.core.database.DatabaseUnavailableException unavailable) {
                    break;
                } catch (CancellationException stop) {
                    cancelled = true;
                    break;
                } catch (Exception failure) {
                    if (!database.available()) break;
                    if (cancelled || Thread.currentThread().isInterrupted()) {
                        cancelled = true;
                        break;
                    }
                    LOG.error("{} artwork={} page={}", messages.getForLog("desktop.ui.tools.operation-failed"),
                            candidate.artworkId(), file.page(), failure);
                    failures.add(new Failure(candidate.artworkId(), file.page()));
                    if (sharedFailure(failure)) {
                        completed++;
                        return;
                    }
                }
                status = new Status("running", plan.files().size(), ++completed, failures.size(), List.copyOf(failures));
            }
            failed = false;
        } catch (CancellationException stop) {
            cancelled = true;
        } finally {
            status = new Status(!database.available() ? "unavailable" : cancelled ? "cancelled" : failed ? "failed" : "completed",
                    plan.files().size(), completed, failures.size(), List.copyOf(failures));
            LOG.info("{} state={} processed={}/{} failed={}", messages.getForLog("gui.tools.history.tool.media-maintenance"),
                    status.state(), completed, plan.files().size(), failures.size());
        }
    }

    private static boolean sharedFailure(Exception failure) {
        return failure instanceof top.sywyar.pixivdownload.core.ffmpeg.FfmpegRunner.UnavailableException
                || failure instanceof RuntimeException
                || failure.getCause() instanceof org.springframework.dao.DataAccessException;
    }

    private void completeRun(Status terminal) {
        java.util.function.Consumer<Status> observer;
        synchronized (this) {
            worker = null;
            pendingTask = null;
            status = terminal;
            observer = completion;
            completion = null;
        }
        if (observer != null) {
            try { observer.accept(terminal); }
            catch (VirtualMachineError | ThreadDeath fatal) { throw fatal; }
            catch (Throwable failure) { LOG.error(messages.getForLog("desktop.ui.tools.operation-failed"), failure); }
        }
    }

    void logFailure(Exception failure) {
        LOG.error(messages.getForLog("desktop.ui.tools.operation-failed"), failure);
    }

    private void requireIdle() {
        if (closed || worker != null || pendingPreview != null || status.state().equals("running") || status.state().equals("scanning")) {
            throw LocalizedException.badRequest("download.media.busy", null);
        }
    }

    private static void requirePlain(Path path) throws IOException {
        Path current = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(current, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Source is not a regular file");
        while (current != null) {
            var attributes = Files.readAttributes(current, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || attributes.isOther()) throw new IOException("Linked media path refused");
            current = current.getParent();
        }
    }

    @Override
    public void close() {
        PluginRuntimeTask task;
        synchronized (this) {
            closed = true;
            cancelled = true;
            preview = null;
            executor.shutdownNow();
            task = pendingTask;
            if (pendingPreview != null && worker == null) {
                pendingPreview.completeExceptionally(new CancellationException());
                pendingPreview = null;
                status = new Status("cancelled", 0, 0, 0, List.of());
            }
        }
        if (task != null) task.cancel();
        boolean interrupted = Thread.interrupted();
        try {
            while (!executor.isTerminated()) {
                try { executor.awaitTermination(1, TimeUnit.SECONDS); }
                catch (InterruptedException ignored) { interrupted = true; }
            }
        } finally {
            if (completion != null) {
                completeRun(new Status("cancelled", status.total(), status.completed(), status.failed(), status.failures()));
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
