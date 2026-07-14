package top.sywyar.pixivdownload.douyin.download.work;

import top.sywyar.pixivdownload.douyin.client.DouyinClientErrorCode;
import top.sywyar.pixivdownload.douyin.client.DouyinClientException;
import top.sywyar.pixivdownload.douyin.db.history.DouyinHistoryService;
import top.sywyar.pixivdownload.douyin.db.history.DouyinSourceRelation;
import top.sywyar.pixivdownload.douyin.db.history.DouyinWorkFileRecord;
import top.sywyar.pixivdownload.douyin.db.history.DouyinWorkRecord;
import top.sywyar.pixivdownload.douyin.download.DouyinDownloadedFile;
import top.sywyar.pixivdownload.douyin.download.DouyinMediaDownloader;
import top.sywyar.pixivdownload.douyin.download.validation.DouyinMediaPayloadValidator;
import top.sywyar.pixivdownload.douyin.model.DouyinMedia;
import top.sywyar.pixivdownload.douyin.model.DouyinMediaType;
import top.sywyar.pixivdownload.douyin.model.DouyinWork;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/** 手动队列与计划任务共用的同步单作品下载接缝。 */
public final class DouyinWorkDownloadExecutor {

    private final DouyinHistoryService historyService;

    public DouyinWorkDownloadExecutor(DouyinHistoryService historyService) {
        this.historyService = historyService;
    }

    public Result execute(Request request) throws IOException, DouyinClientException {
        ensureNotCancelled(request.cancellationRequested());
        DouyinWork work = withOptionalCover(request.work(), request.includeCover());
        Path outputDirectory = outputDirectory(request, work);
        Optional<List<DouyinDownloadedFile>> existing = reuseExisting(request, work);
        if (existing.isPresent()) {
            return new Result(work, outputDirectory, existing.get(), true);
        }
        List<DouyinDownloadedFile> files = request.mediaDownloader().download(
                work.media(), outputDirectory, request.cancellationRequested(), request.cookie());
        ensureNotCancelled(request.cancellationRequested());
        if (files.isEmpty()) {
            throw new DouyinClientException(DouyinClientErrorCode.MEDIA_URL_MISSING,
                    "Douyin download produced no files");
        }
        if (historyService != null && !historyService.recordCompleted(
                work,
                outputDirectory,
                files,
                request.originalInput(),
                request.collectionId(),
                request.collectionTitle(),
                request.collectionOrder(),
                request.sourceRelations())) {
            throw new IllegalStateException("Douyin history could not be recorded for work " + work.id());
        }
        return new Result(work, outputDirectory, files, false);
    }

    private Optional<List<DouyinDownloadedFile>> reuseExisting(Request request, DouyinWork work)
            throws DouyinClientException {
        if (historyService == null || work.id() == null) {
            return Optional.empty();
        }
        Optional<DouyinWorkRecord> record = historyService.findById(work.id());
        if (record.isEmpty()) {
            return Optional.empty();
        }
        List<DouyinWorkFileRecord> storedFiles = historyService.findFilesByWorkId(work.id());
        if (storedFiles.isEmpty() || record.get().count() != storedFiles.size()
                || !matchesCurrentMedia(work.media(), storedFiles)) {
            return Optional.empty();
        }
        if (request.includeCover() && storedFiles.stream()
                .noneMatch(file -> DouyinMediaType.COVER.name().equals(file.mediaType()))) {
            return Optional.empty();
        }
        Path folder = Path.of(record.get().folder()).toAbsolutePath().normalize();
        List<DouyinDownloadedFile> files = new ArrayList<>();
        for (DouyinWorkFileRecord stored : storedFiles) {
            Path path = folder.resolve(stored.fileName()).normalize();
            if (!path.startsWith(folder) || !Files.isRegularFile(path)) {
                return Optional.empty();
            }
            long actualBytes;
            try {
                actualBytes = Files.size(path);
            } catch (IOException e) {
                return Optional.empty();
            }
            if (actualBytes <= 0) {
                return Optional.empty();
            }
            if (stored.bytes() != null && stored.bytes() > 0 && stored.bytes() != actualBytes) {
                return Optional.empty();
            }
            if (!DouyinMediaPayloadValidator.isReusableMediaFile(path, stored.contentType())) {
                return Optional.empty();
            }
            files.add(new DouyinDownloadedFile(path, actualBytes, stored.contentType()));
        }
        ensureNotCancelled(request.cancellationRequested());
        if (!historyService.recordRelations(work.id(), request.sourceRelations())) {
            throw new IllegalStateException("Douyin relation could not be recorded for active work " + work.id());
        }
        return Optional.of(List.copyOf(files));
    }

    private static boolean matchesCurrentMedia(List<DouyinMedia> media,
                                               List<DouyinWorkFileRecord> storedFiles) {
        if (media == null || media.isEmpty()) {
            return false;
        }
        if (media.size() != storedFiles.size()) {
            return false;
        }
        for (int index = 0; index < media.size(); index++) {
            DouyinMedia expected = media.get(index);
            if (expected == null) {
                return false;
            }
            int expectedIndex = index;
            boolean matched = storedFiles.stream().anyMatch(stored ->
                    stored.fileIndex() == expectedIndex
                            && Objects.equals(normalizeMediaId(stored.mediaId()), normalizeMediaId(expected.id()))
                            && expected.type().name().equals(stored.mediaType()));
            if (!matched) {
                return false;
            }
        }
        return true;
    }

    private static String normalizeMediaId(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static DouyinWork withOptionalCover(DouyinWork work, boolean includeCover) {
        if (!includeCover || work.thumbnailUrl() == null || work.thumbnailUrl().isBlank()
                || work.media().stream().anyMatch(media -> media.type() == DouyinMediaType.COVER)) {
            return work;
        }
        URI coverUri;
        try {
            coverUri = URI.create(work.thumbnailUrl());
        } catch (IllegalArgumentException ignored) {
            return work;
        }
        List<DouyinMedia> media = new ArrayList<>(work.media());
        media.add(new DouyinMedia(work.id() + "-cover", DouyinMediaType.COVER, coverUri,
                work.id() + "-cover", mediaExtension(coverUri, "jpg"), null, null));
        return new DouyinWork(work.id(), work.title(), work.description(), work.itemTitle(), work.caption(),
                work.authorId(), work.authorName(), work.pageUrl(), work.thumbnailUrl(), work.mediaUrl(),
                media, work.kind(), work.publishTimeEpochSeconds(), work.collectionId(), work.collectionTitle());
    }

    private static Path outputDirectory(Request request, DouyinWork work) {
        Path ownerDirectory = request.downloadDirectory().resolve(sanitize(request.ownerScope())).normalize();
        String collectionTitle = firstNonBlank(request.collectionTitle(), work.collectionTitle());
        if (collectionTitle != null) {
            ownerDirectory = ownerDirectory.resolve(
                    sanitize(firstNonBlank(request.collectionId(), work.collectionId(), "collection"))
                            + "-" + sanitize(collectionTitle)).normalize();
        }
        String title = sanitize(firstNonBlank(work.title(), request.titleFallback(), work.id()));
        return ownerDirectory.resolve(sanitize(work.id()) + "-" + title).normalize();
    }

    private static String mediaExtension(URI uri, String fallback) {
        String path = uri == null ? null : uri.getPath();
        if (path != null) {
            int slash = path.lastIndexOf('/');
            int dot = path.lastIndexOf('.');
            if (dot > slash && dot + 1 < path.length()) {
                String extension = path.substring(dot + 1).toLowerCase(Locale.ROOT);
                if (extension.matches("[a-z0-9]{1,8}")) {
                    return extension;
                }
            }
        }
        return fallback;
    }

    private static void ensureNotCancelled(BooleanSupplier cancellationRequested)
            throws DouyinClientException {
        if (cancellationRequested.getAsBoolean()) {
            throw new DouyinClientException(DouyinClientErrorCode.CANCELLED, "Douyin download was cancelled");
        }
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

    public record Request(
            DouyinWork work,
            DouyinMediaDownloader mediaDownloader,
            Path downloadDirectory,
            String ownerScope,
            String titleFallback,
            String cookie,
            boolean includeCover,
            String originalInput,
            String collectionId,
            String collectionTitle,
            Integer collectionOrder,
            List<DouyinSourceRelation> sourceRelations,
            BooleanSupplier cancellationRequested
    ) {

        public Request {
            if (work == null || mediaDownloader == null || downloadDirectory == null
                    || ownerScope == null || ownerScope.isBlank()) {
                throw new IllegalArgumentException("Douyin work execution request is incomplete");
            }
            sourceRelations = sourceRelations == null ? List.of() : List.copyOf(sourceRelations);
            cancellationRequested = cancellationRequested == null ? () -> false : cancellationRequested;
        }
    }

    public record Result(
            DouyinWork work,
            Path outputDirectory,
            List<DouyinDownloadedFile> files,
            boolean alreadyDownloaded
    ) {

        public Result {
            files = files == null ? List.of() : List.copyOf(files);
        }
    }
}
