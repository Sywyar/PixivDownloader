package top.sywyar.pixivdownload.download.media;

import top.sywyar.pixivdownload.core.work.model.WorkAssetFile;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.work.service.WorkAssetService;
import top.sywyar.pixivdownload.core.work.service.WorkMetadataRepository;
import top.sywyar.pixivdownload.download.web.LocalizedException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 只有持有最近一次预览凭据的主动请求才能启动；一个实例同时只运行一项任务。 */
public final class MediaMaintenanceService implements AutoCloseable {
    static final int MAX_WORKS = 500;
    static final int MAX_FILES = 10_000;
    private final WorkAssetService assets;
    private final WorkMetadataRepository metadata;
    private final ImageOutputService images;
    private final top.sywyar.pixivdownload.download.UgoiraService animations;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "media-maintenance");
        thread.setDaemon(true);
        return thread;
    });
    private Plan preview;
    private volatile boolean cancelled;
    private volatile Status status = new Status("idle", 0, 0, 0, List.of());
    private boolean closed;

    public MediaMaintenanceService(WorkAssetService assets, WorkMetadataRepository metadata, ImageOutputService images,
                                   top.sywyar.pixivdownload.download.UgoiraService animations) {
        this.assets = assets;
        this.metadata = metadata;
        this.images = images;
        this.animations = animations;
    }

    public record Request(List<Long> artworkIds, String imageFormats, String ugoiraFormats, boolean repairThumbnails) {}
    public record Item(long artworkId, int page, String fileName) {}
    public record Preview(String token, List<Item> files) {}
    public record Failure(long artworkId, int page) {}
    public record Status(String state, int total, int completed, int failed, List<Failure> failures) {}
    private record Candidate(long artworkId, WorkAssetFile file, long bytes, long modified) {}
    private record Plan(String token, Request request, List<Candidate> files) {}

    public synchronized Preview preview(Request request) throws IOException {
        requireIdle();
        if (request == null || request.artworkIds() == null || request.artworkIds().isEmpty()
                || request.artworkIds().size() > MAX_WORKS || request.artworkIds().stream().anyMatch(id -> id == null || id <= 0)) {
            throw LocalizedException.badRequest("download.media.invalid-scope", null, MAX_WORKS);
        }
        try {
            MediaOutputSettings.parseFormats(request.imageFormats(), MediaOutputSettings.IMAGE_FORMATS);
            if (request.ugoiraFormats() != null) MediaOutputSettings.parseFormats(request.ugoiraFormats(), MediaOutputSettings.UGOIRA_FORMATS);
        }
        catch (IllegalArgumentException invalid) { throw LocalizedException.badRequest("download.media.invalid-formats", null); }
        Request snapshot = new Request(List.copyOf(request.artworkIds()), request.imageFormats(), request.ugoiraFormats(), request.repairThumbnails());
        List<Candidate> files = new ArrayList<>();
        for (Long id : new LinkedHashSet<>(snapshot.artworkIds())) {
            if (metadata.find(WorkType.ARTWORK, id).isEmpty()) continue;
            var asset = assets.findAsset(WorkType.ARTWORK, id);
            if (asset.isEmpty()) continue;
            for (WorkAssetFile file : asset.get().files()) {
                requirePlain(file.path());
                if (files.size() >= MAX_FILES) throw LocalizedException.badRequest("download.media.too-many-files", null, MAX_FILES);
                files.add(new Candidate(id, file, Files.size(file.path()), Files.getLastModifiedTime(file.path()).toMillis()));
            }
        }
        if (files.isEmpty()) throw LocalizedException.badRequest("download.media.empty-scope", null);
        preview = new Plan(UUID.randomUUID().toString(), snapshot, List.copyOf(files));
        return new Preview(preview.token(), files.stream()
                .map(item -> new Item(item.artworkId(), item.file().page(), item.file().path().getFileName().toString())).toList());
    }

    public synchronized Status start(String token) {
        requireIdle();
        if (preview == null || !preview.token().equals(token)) throw LocalizedException.badRequest("download.media.preview-required", null);
        Plan plan = preview;
        preview = null;
        cancelled = false;
        status = new Status("running", plan.files().size(), 0, 0, List.of());
        executor.execute(() -> run(plan));
        return status;
    }

    public Status status() { return status; }
    public void cancel() { cancelled = true; }

    private void run(Plan plan) {
        int completed = 0;
        List<Failure> failures = new ArrayList<>();
        try {
            for (Candidate candidate : plan.files()) {
                if (cancelled || Thread.currentThread().isInterrupted()) break;
                WorkAssetFile file = candidate.file();
                try {
                    requirePlain(file.path());
                    var current = assets.rawFile(WorkType.ARTWORK, candidate.artworkId(), file.page());
                    if (metadata.find(WorkType.ARTWORK, candidate.artworkId()).isEmpty() || current.isEmpty()
                            || !current.get().path().equals(file.path()) || Files.size(file.path()) != candidate.bytes()
                            || Files.getLastModifiedTime(file.path()).toMillis() != candidate.modified()) {
                        throw new IOException("Source changed after preview");
                    }
                    boolean animation = plan.request().ugoiraFormats() != null && animations.addMissingFormats(
                            candidate.artworkId(), file.path(), plan.request().ugoiraFormats(), () -> cancelled);
                    if (!animation && !plan.request().imageFormats().equals("original")) {
                        images.addMissingFormats(file.path(), plan.request().imageFormats(), () -> cancelled);
                    }
                    if (plan.request().repairThumbnails()
                            && assets.thumbnail(WorkType.ARTWORK, candidate.artworkId(), file.page()).isEmpty()) {
                        throw new IOException("Thumbnail unavailable");
                    }
                } catch (CancellationException stop) {
                    cancelled = true;
                    break;
                } catch (Exception failure) {
                    failures.add(new Failure(candidate.artworkId(), file.page()));
                }
                status = new Status("running", plan.files().size(), ++completed, failures.size(), List.copyOf(failures));
            }
        } finally {
            status = new Status(cancelled ? "cancelled" : "completed", plan.files().size(), completed, failures.size(), List.copyOf(failures));
        }
    }

    private void requireIdle() {
        if (closed || status.state().equals("running")) throw LocalizedException.badRequest("download.media.busy", null);
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
        synchronized (this) { closed = true; preview = null; cancelled = true; }
        executor.shutdownNow();
        boolean interrupted = Thread.interrupted();
        try {
            while (!executor.isTerminated()) {
                try { executor.awaitTermination(1, TimeUnit.SECONDS); }
                catch (InterruptedException ignored) { interrupted = true; }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
