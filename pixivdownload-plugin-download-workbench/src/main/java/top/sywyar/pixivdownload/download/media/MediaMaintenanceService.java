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

/** 只有持有最近一次预览凭据的主动请求才能启动；一个实例同时只运行一项任务。 */
public final class MediaMaintenanceService implements AutoCloseable {
    static final int MAX_WORKS = 500;
    static final int MAX_FILES = 10_000;
    private final WorkAssetService assets;
    private final WorkMetadataRepository metadata;
    private final WorkQueryService query;
    private final ArtworkMediaStore mediaStore;
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

    public MediaMaintenanceService(WorkAssetService assets, WorkMetadataRepository metadata, WorkQueryService query, ImageOutputService images,
                                   top.sywyar.pixivdownload.download.UgoiraService animations, ArtworkMediaStore mediaStore) {
        this.assets = assets;
        this.metadata = metadata;
        this.query = query;
        this.images = images;
        this.animations = animations;
        this.mediaStore = mediaStore;
    }

    public record Request(String imageFormats, String ugoiraFormats, boolean repairThumbnails) {}
    public record Item(long artworkId, int page, String fileName, List<String> missingFormats, boolean missingThumbnail) {}
    public record Preview(String token, List<Item> files, int scanned, int skipped, boolean limited) {}
    public record Failure(long artworkId, int page) {}
    public record Status(String state, int total, int completed, int failed, List<Failure> failures) {}
    private record Candidate(long artworkId, WorkAssetFile file, long bytes, long modified,
                             List<String> formats, boolean thumbnail, boolean animation, boolean unavailable) {}
    private record Plan(String token, List<Candidate> files) {}

    public synchronized Preview preview(Request request) throws IOException {
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
                if (page == 0) pages = batch.totalPages();
                for (var work : batch.content()) {
                    checkCancelled();
                    scanned++;
                    int before = files.size();
                    long id = work.workId();
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
                            } catch (IOException unavailable) { skipped++; }
                        }
                    } else skipped++;
                    status = new Status("scanning", (int) Math.min(Integer.MAX_VALUE, batch.totalElements()), scanned, skipped, List.of());
                    if (files.size() > before && ++matched >= MAX_WORKS) {
                        limited = scanned < batch.totalElements();
                        break scan;
                    }
                }
            }
            checkCancelled();
            preview = files.isEmpty() ? null : new Plan(UUID.randomUUID().toString(), List.copyOf(files));
            return new Preview(preview == null ? "" : preview.token(), files.stream()
                    .map(item -> new Item(item.artworkId(), item.file().page(), item.file().path().getFileName().toString(),
                            item.formats(), item.thumbnail())).toList(), scanned, skipped, limited);
        } finally {
            status = new Status(cancelled ? "cancelled" : "idle", 0, 0, 0, List.of());
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

    private void checkCancelled() {
        if (cancelled || Thread.currentThread().isInterrupted()) {
            cancelled = true;
            throw new CancellationException();
        }
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
                    if (!candidate.formats().isEmpty()) {
                        String formats = String.join(",", candidate.formats());
                        if (candidate.animation()) {
                            if (!animations.addMissingFormats(candidate.artworkId(), file.path(), formats, () -> cancelled)) {
                                throw new IOException("Animation source unavailable");
                            }
                        } else images.addMissingFormats(candidate.artworkId(), file.page(), file.path(), formats, () -> cancelled);
                    }
                    if (candidate.thumbnail()
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
        if (closed || status.state().equals("running") || status.state().equals("scanning")) {
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
        cancelled = true;
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
