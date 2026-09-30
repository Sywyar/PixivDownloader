package top.sywyar.pixivdownload.core.download;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.sywyar.pixivdownload.common.PlainFilePathGuard;
import top.sywyar.pixivdownload.core.artwork.download.*;
import top.sywyar.pixivdownload.core.asset.ExternalWorkFiles;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.core.metadata.ArtworkMetadataQuality;
import top.sywyar.pixivdownload.core.pixiv.filename.PixivWorkFileNameFormatter;
import top.sywyar.pixivdownload.core.work.importing.*;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.work.service.AuthorObservationService;
import top.sywyar.pixivdownload.plugin.api.download.lifecycle.*;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityOwner;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 文件只读核验；类型 owner 的写入和文件引用在同一数据库事务提交。 */
@Component
public final class LocalWorkFileImporter implements WorkFileImporter {
    private final ArtworkDownloadHistory history;
    private final PixivDatabase database;
    private final ExternalWorkFiles files;
    private final TransactionTemplate transaction;
    private final DownloadLifecycle lifecycle;
    private final AuthorObservationService authors;
    private final Map<ExternalCapabilityOwner, Map<WorkType, WorkFileImportHandler>> owners = new LinkedHashMap<>();
    private volatile Map<WorkType, WorkFileImportHandler> handlers = Map.of();
    private final java.util.concurrent.locks.ReentrantLock admission = new java.util.concurrent.locks.ReentrantLock();

    public LocalWorkFileImporter(ArtworkDownloadHistory history, PixivDatabase database, ExternalWorkFiles files,
                                 PlatformTransactionManager transactions, DownloadLifecycle lifecycle,
                                 AuthorObservationService authors) {
        this.history = history; this.database = database; this.files = files; this.lifecycle = lifecycle;
        this.authors = authors;
        transaction = new TransactionTemplate(transactions);
        transaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public synchronized void register(ExternalCapabilityOwner owner, Map<WorkType, WorkFileImportHandler> contribution) {
        Map<WorkType, WorkFileImportHandler> next = new EnumMap<>(WorkType.class);
        owners.forEach((key, value) -> { if (!key.equals(owner)) next.putAll(value); });
        contribution.forEach((type, handler) -> {
            if (type == WorkType.ARTWORK || next.putIfAbsent(type, handler) != null)
                throw new IllegalArgumentException("Duplicate work import owner");
        });
        owners.put(owner, Map.copyOf(contribution)); handlers = Map.copyOf(next);
    }
    public synchronized void withdraw(ExternalCapabilityOwner owner) {
        owners.remove(owner);
        Map<WorkType, WorkFileImportHandler> next = new EnumMap<>(WorkType.class);
        owners.values().forEach(next::putAll); handlers = Map.copyOf(next);
    }
    @Override public boolean importFiles(WorkFileImportRequest request) throws IOException {
        // ponytail: 单次核验和登记，无排队大载荷；需要并行吞吐时再按作品加锁。
        if (!admission.tryLock()) throw new IOException("IMPORT_BUSY");
        try { return importOne(request); } finally { admission.unlock(); }
    }
    private boolean importOne(WorkFileImportRequest request) throws IOException {
        try (var workFileLease = top.sywyar.pixivdownload.core.work.service.WorkFileLock.acquire(request.workType(), request.workId())) {
            WorkFileImportHandler handler = handlers.get(request.workType());
            if (request.workType() != WorkType.ARTWORK && handler == null) throw new IOException("IMPORT_OWNER_UNAVAILABLE");
            if (request.workType() == WorkType.ARTWORK && !ArtworkMetadataQuality.isMeaningfulTitle(request.workId(), request.title()))
                throw new IllegalArgumentException("INVALID_WORK_TITLE");
            DownloadAttempt attempt = new DownloadAttempt(UUID.randomUUID(), request.workType().name().toLowerCase(Locale.ROOT), Long.toString(request.workId()));
            lifecycle.checkAdmission(attempt);
            Path root = request.sourceRoot().normalize();
            if (!PlainFilePathGuard.isPlainDirectory(root)) throw new IOException("INVALID_SOURCE_ROOT");
            Set<Path> unique = new HashSet<>();
            Set<String> extensions = new LinkedHashSet<>();
            List<BasicFileAttributes> attributes = new ArrayList<>();
            long total = 0;
            for (Path supplied : request.pageFiles()) {
                Path source = root.resolve(supplied).toAbsolutePath().normalize();
                if (!source.startsWith(root) || !unique.add(source)) throw new IOException("INVALID_SOURCE_PATH");
                PlainFilePathGuard.requirePlainRegularFile(source);
                BasicFileAttributes facts = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                total = Math.addExact(total, facts.size());
                if (facts.size() < 1 || facts.size() > 1024L * 1024 * 1024 || total > 8L * 1024 * 1024 * 1024)
                    throw new IOException("IMPORT_SIZE_LIMIT");
                extensions.add(validateFormat(source, request.workType())); attributes.add(facts);
            }
            lifecycle.publish(new DownloadEvent(attempt, DownloadEvent.Phase.ACCEPTED));
            lifecycle.publish(new DownloadEvent(attempt, DownloadEvent.Phase.STARTED));
            try {
                boolean written = Boolean.TRUE.equals(transaction.execute(status -> {
                    for (int page = 0; page < request.pageFiles().size(); page++) {
                        Path source = root.resolve(request.pageFiles().get(page)).normalize();
                        try {
                            PlainFilePathGuard.requirePlainRegularFile(source);
                            var now = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                            var before = attributes.get(page);
                            if (!Objects.equals(now.fileKey(), before.fileKey()) || now.size() != before.size()
                                    || !now.lastModifiedTime().equals(before.lastModifiedTime())) throw new IOException("SOURCE_CHANGED");
                        } catch (IOException changed) { throw new java.io.UncheckedIOException(changed); }
                    }
                    authors.observe(request.authorId(), request.authorName());
                    boolean created = request.workType() == WorkType.ARTWORK ? registerArtwork(request, extensions) : handler.register(request);
                    if (created) files.save(request);
                    return created;
                }));
                lifecycle.publish(new DownloadEvent(attempt, written ? DownloadEvent.Phase.COMPLETED : DownloadEvent.Phase.CANCELLED));
                return written;
            } catch (RuntimeException failure) {
                lifecycle.publish(new DownloadEvent(attempt, DownloadEvent.Phase.FAILED));
                if (failure instanceof java.io.UncheckedIOException io) throw io.getCause();
                throw failure;
            }
        }
    }
    private boolean registerArtwork(WorkFileImportRequest r, Set<String> extensions) {
        if (database.getArtwork(r.workId()) != null) return false;
        history.record(new ArtworkDownloadCompletion(r.workId(), r.title(), r.sourceRoot(), r.pageCount(), extensions,
                history.allocateRecordTime(System.currentTimeMillis()), r.restriction(), r.aiGenerated(), r.authorId(), r.description(),
                PixivWorkFileNameFormatter.DEFAULT_TEMPLATE, r.authorName(), r.seriesId(), r.seriesOrder(), r.tags()));
        return true;
    }
    private static String validateFormat(Path source, WorkType type) throws IOException {
        String name = source.getFileName().toString().toLowerCase(Locale.ROOT);
        String ext = name.substring(name.lastIndexOf('.') + 1);
        try (var input = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS)) {
            byte[] h = input.readNBytes(16);
            String header = new String(h, StandardCharsets.ISO_8859_1);
            boolean zip = h.length >= 4 && h[0] == 'P' && h[1] == 'K' && h[2] == 3 && h[3] == 4;
            if (type == WorkType.NOVEL) {
                if (ext.equals("epub") && zip) return ext;
                if (ext.equals("txt") && Files.size(source) <= 16L * 1024 * 1024 && !header.contains("\0")) return ext;
            } else {
                if (Set.of("jpg", "jpeg").contains(ext) && h.length >= 3 && (h[0]&255)==255 && (h[1]&255)==216 && (h[2]&255)==255) return ext;
                if (Set.of("png", "apng").contains(ext) && h.length >= 8 && Arrays.equals(Arrays.copyOf(h,8),new byte[]{(byte)137,80,78,71,13,10,26,10})) return ext;
                if (ext.equals("gif") && (header.startsWith("GIF87a") || header.startsWith("GIF89a"))) return ext;
                if (ext.equals("webp") && header.startsWith("RIFF") && header.length()>=12 && header.substring(8,12).equals("WEBP")) return ext;
                if (ext.equals("webm") && h.length>=4 && (h[0]&255)==26 && (h[1]&255)==69 && (h[2]&255)==223 && (h[3]&255)==163) return ext;
                if (ext.equals("mp4") && header.length()>=8 && header.substring(4,8).equals("ftyp")) return ext;
                if (ext.equals("zip") && zip) return ext;
            }
        }
        throw new IOException("UNSUPPORTED_FILE_FORMAT");
    }
}
