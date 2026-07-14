package top.sywyar.pixivdownload.douyin.db.history;

import org.springframework.transaction.annotation.Transactional;
import top.sywyar.pixivdownload.douyin.download.DouyinDownloadedFile;
import top.sywyar.pixivdownload.douyin.model.DouyinMedia;
import top.sywyar.pixivdownload.douyin.model.DouyinMediaType;
import top.sywyar.pixivdownload.douyin.model.DouyinWork;
import top.sywyar.pixivdownload.douyin.model.DouyinWorkKind;
import top.sywyar.pixivdownload.douyin.source.DouyinSourceTypes;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

public class DouyinHistoryService {

    private static final long EPOCH_MILLIS_THRESHOLD = 1_000_000_000_000L;

    private final DouyinHistoryRepository repository;
    private final AtomicLong lastIssuedTime;

    public DouyinHistoryService(DouyinHistoryRepository repository) {
        this.repository = repository;
        Long maxTime = repository.findMaxTime();
        this.lastIssuedTime = new AtomicLong(maxTime == null ? 0L : maxTime);
    }

    @Transactional
    public int backfillRelations() {
        return repository.backfillRelations();
    }

    @Transactional
    public boolean recordCompleted(DouyinWork work,
                                   Path folder,
                                   List<DouyinDownloadedFile> files) {
        return recordCompleted(work, folder, files, null, null, null, null,
                (DouyinSourceRelation) null);
    }

    @Transactional
    public boolean recordCompleted(DouyinWork work,
                                   Path folder,
                                   List<DouyinDownloadedFile> files,
                                   String sourceUrl,
                                   String collectionId,
                                   String collectionTitle,
                                   Integer collectionOrder) {
        return recordCompleted(work, folder, files, sourceUrl, collectionId, collectionTitle,
                collectionOrder, (DouyinSourceRelation) null);
    }

    @Transactional
    public boolean recordCompleted(DouyinWork work,
                                   Path folder,
                                   List<DouyinDownloadedFile> files,
                                   String sourceUrl,
                                   String collectionId,
                                   String collectionTitle,
                                   Integer collectionOrder,
                                   DouyinSourceRelation sourceRelation) {
        if (work == null || isBlank(work.id()) || folder == null || files == null || files.isEmpty()) {
            return false;
        }
        long time = nextUniqueTime(System.currentTimeMillis());
        String workId = work.id().trim();
        DouyinWorkRecord record = new DouyinWorkRecord(
                workId,
                defaultText(work.title(), workId),
                folder.toAbsolutePath().normalize().toString(),
                files.size(),
                extensions(files, work.media()),
                time,
                false,
                kindName(work.kind()),
                blankToNull(sourceUrl),
                blankToNull(work.pageUrl()),
                blankToNull(work.thumbnailUrl()),
                blankToNull(work.authorId()),
                blankToNull(work.authorName()),
                blankToNull(work.description()),
                blankToNull(work.itemTitle()),
                blankToNull(work.caption()),
                publishTimeMillis(work.publishTimeEpochSeconds()),
                firstNonBlank(collectionId, work.collectionId()),
                firstNonBlank(collectionTitle, work.collectionTitle()),
                collectionOrder
        );
        List<DouyinWorkFileRecord> fileRecords = fileRecords(workId, work.media(), files, time);
        DouyinSourceRelation relation = sourceRelation == null
                ? inferredRelation(workId, sourceUrl, collectionId, collectionTitle, collectionOrder, time)
                : sourceRelation.withWorkId(workId);
        return recordCompleted(record, fileRecords, relation);
    }

    @Transactional
    public boolean recordCompleted(DouyinWork work,
                                   Path folder,
                                   List<DouyinDownloadedFile> files,
                                   String sourceUrl,
                                   String collectionId,
                                   String collectionTitle,
                                   Integer collectionOrder,
                                   List<DouyinSourceRelation> sourceRelations) {
        if (work == null || isBlank(work.id()) || folder == null || files == null || files.isEmpty()) {
            return false;
        }
        long time = nextUniqueTime(System.currentTimeMillis());
        String workId = work.id().trim();
        DouyinWorkRecord record = new DouyinWorkRecord(
                workId,
                defaultText(work.title(), workId),
                folder.toAbsolutePath().normalize().toString(),
                files.size(),
                extensions(files, work.media()),
                time,
                false,
                kindName(work.kind()),
                blankToNull(sourceUrl),
                blankToNull(work.pageUrl()),
                blankToNull(work.thumbnailUrl()),
                blankToNull(work.authorId()),
                blankToNull(work.authorName()),
                blankToNull(work.description()),
                blankToNull(work.itemTitle()),
                blankToNull(work.caption()),
                publishTimeMillis(work.publishTimeEpochSeconds()),
                firstNonBlank(collectionId, work.collectionId()),
                firstNonBlank(collectionTitle, work.collectionTitle()),
                collectionOrder
        );
        return recordCompleted(record, fileRecords(workId, work.media(), files, time), sourceRelations);
    }

    @Transactional
    public boolean recordCompleted(DouyinWorkRecord work, List<DouyinWorkFileRecord> files) {
        DouyinSourceRelation relation = inferredRelation(work.workId(), work.sourceUrl(),
                work.collectionId(), work.collectionTitle(), work.collectionOrder(), work.time());
        return recordCompleted(work, files, relation);
    }

    @Transactional
    public boolean recordCompleted(DouyinWorkRecord work,
                                   List<DouyinWorkFileRecord> files,
                                   DouyinSourceRelation relation) {
        return recordCompleted(work, files, relation == null ? List.of() : List.of(relation));
    }

    @Transactional
    public boolean recordCompleted(DouyinWorkRecord work,
                                   List<DouyinWorkFileRecord> files,
                                   List<DouyinSourceRelation> relations) {
        if (work == null || isBlank(work.workId()) || files == null || files.isEmpty()) {
            return false;
        }
        String workId = work.workId().trim();
        if (files.stream().anyMatch(file -> file == null || !workId.equals(file.workId()))) {
            throw new IllegalArgumentException("Douyin history files must belong to the completed work");
        }
        repository.deleteIfMarkedDeleted(work.workId());
        int inserted = repository.insertWork(work);
        if (inserted > 0) {
            repository.insertFiles(files);
        } else if (!repository.replaceActiveWork(work, files)) {
            throw new IllegalStateException("Douyin active history could not be refreshed: " + workId);
        }
        persistRelations(workId, relations);
        return true;
    }

    public Optional<DouyinWorkRecord> findById(String workId) {
        if (isBlank(workId)) {
            return Optional.empty();
        }
        return repository.findById(workId.trim());
    }

    public List<DouyinWorkFileRecord> findFilesByWorkId(String workId) {
        if (isBlank(workId)) {
            return List.of();
        }
        return repository.findFilesByWorkId(workId.trim());
    }

    public List<DouyinSourceRelation> findRelationsByWorkId(String workId) {
        if (isBlank(workId)) {
            return List.of();
        }
        return repository.findRelationsByWorkId(workId.trim());
    }

    @Transactional
    public boolean recordRelation(DouyinSourceRelation relation) {
        if (relation == null) {
            return false;
        }
        return recordRelations(relation.workId(), List.of(relation));
    }

    @Transactional
    public boolean recordRelations(String workId, List<DouyinSourceRelation> relations) {
        if (isBlank(workId) || !repository.hasActiveWork(workId.trim())) {
            return false;
        }
        persistRelations(workId.trim(), relations);
        return true;
    }

    @Transactional(readOnly = true)
    public DouyinHistoryPage search(DouyinHistoryQuery query) {
        return repository.search(query);
    }

    @Transactional(readOnly = true)
    public List<DouyinAuthorSummary> authorFacets(DouyinHistoryQuery query) {
        return repository.authorFacets(query);
    }

    public List<DouyinAuthorSummary> authorFacets(String search, int limit) {
        return authorFacets(new DouyinHistoryQuery(0, limit, null, null, search, List.of(), List.of()));
    }

    public boolean hasWork(String workId) {
        return !isBlank(workId) && repository.hasWork(workId.trim());
    }

    public boolean hasActiveWork(String workId) {
        return !isBlank(workId) && repository.hasActiveWork(workId.trim());
    }

    public boolean isDeleted(String workId) {
        return !isBlank(workId) && repository.isDeleted(workId.trim());
    }

    @Transactional
    public boolean markDeleted(String workId) {
        return !isBlank(workId) && repository.markDeleted(workId.trim()) > 0;
    }

    @Transactional
    public boolean deleteIfMarkedDeleted(String workId) {
        return !isBlank(workId) && repository.deleteIfMarkedDeleted(workId.trim());
    }

    private void persistRelations(String workId, List<DouyinSourceRelation> relations) {
        LinkedHashMap<String, DouyinSourceRelation> unique = new LinkedHashMap<>();
        if (relations != null) {
            for (DouyinSourceRelation relation : relations) {
                if (relation == null) {
                    continue;
                }
                DouyinSourceRelation normalized = relation.withWorkId(workId);
                unique.put(normalized.sourceType() + "\u0000" + normalized.sourceId(), normalized);
            }
        }
        for (DouyinSourceRelation relation : unique.values()) {
            if (repository.upsertRelation(relation) <= 0) {
                throw new IllegalStateException("Douyin source relation could not be persisted: " + workId);
            }
        }
    }

    private long nextUniqueTime(long preferredTime) {
        long base = preferredTime > 0 ? toMillis(preferredTime) : System.currentTimeMillis();
        long candidate;
        while (true) {
            long last = lastIssuedTime.get();
            candidate = Math.max(base, last + 1);
            if (lastIssuedTime.compareAndSet(last, candidate)) {
                break;
            }
        }
        while (repository.countByTime(candidate) > 0) {
            candidate = lastIssuedTime.incrementAndGet();
        }
        return candidate;
    }

    private static DouyinSourceRelation inferredRelation(String workId,
                                                         String sourceUrl,
                                                         String collectionId,
                                                         String collectionTitle,
                                                         Integer collectionOrder,
                                                         long discoveredTime) {
        String stableCollectionId = blankToNull(collectionId);
        return new DouyinSourceRelation(
                workId,
                stableCollectionId == null ? DouyinSourceTypes.SINGLE : DouyinSourceTypes.COLLECTION,
                stableCollectionId == null ? workId : stableCollectionId,
                stableCollectionId == null ? null : blankToNull(collectionTitle),
                blankToNull(sourceUrl),
                stableCollectionId == null ? null : collectionOrder,
                discoveredTime);
    }

    private static List<DouyinWorkFileRecord> fileRecords(String workId,
                                                          List<DouyinMedia> media,
                                                          List<DouyinDownloadedFile> files,
                                                          long createdTime) {
        List<DouyinWorkFileRecord> records = new ArrayList<>();
        List<DouyinMedia> safeMedia = media == null ? List.of() : media;
        for (int i = 0; i < files.size(); i++) {
            DouyinDownloadedFile file = files.get(i);
            DouyinMedia item = i < safeMedia.size() ? safeMedia.get(i) : null;
            String fileName = fileName(file, i);
            records.add(new DouyinWorkFileRecord(
                    workId,
                    i,
                    item == null ? null : blankToNull(item.id()),
                    mediaTypeName(item),
                    fileName,
                    extension(fileName, item),
                    file == null ? null : file.bytes(),
                    firstNonBlank(file == null ? null : file.contentType(),
                            item == null ? null : item.contentType()),
                    createdTime
            ));
        }
        return records;
    }

    private static String extensions(List<DouyinDownloadedFile> files, List<DouyinMedia> media) {
        Set<String> extensions = new LinkedHashSet<>();
        List<DouyinMedia> safeMedia = media == null ? List.of() : media;
        for (int i = 0; i < files.size(); i++) {
            DouyinDownloadedFile file = files.get(i);
            DouyinMedia item = i < safeMedia.size() ? safeMedia.get(i) : null;
            extensions.add(extension(fileName(file, i), item));
        }
        return String.join(",", extensions);
    }

    private static String fileName(DouyinDownloadedFile file, int index) {
        if (file != null && file.path() != null && file.path().getFileName() != null) {
            return file.path().getFileName().toString();
        }
        return "media-" + index;
    }

    private static String extension(String fileName, DouyinMedia media) {
        String ext = null;
        int dot = fileName == null ? -1 : fileName.lastIndexOf('.');
        if (dot >= 0 && dot < fileName.length() - 1) {
            ext = fileName.substring(dot + 1);
        }
        if (isBlank(ext) && media != null) {
            ext = media.extension();
        }
        String normalized = ext == null ? "" : ext.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return normalized.isBlank() ? "bin" : normalized;
    }

    private static String mediaTypeName(DouyinMedia media) {
        DouyinMediaType type = media == null ? null : media.type();
        return type == null ? DouyinMediaType.VIDEO.name() : type.name();
    }

    private static String kindName(DouyinWorkKind kind) {
        return kind == null ? DouyinWorkKind.UNSUPPORTED.name() : kind.name();
    }

    private static Long publishTimeMillis(Long value) {
        if (value == null || value <= 0) {
            return null;
        }
        return toMillis(value);
    }

    private static long toMillis(long value) {
        return value > 0 && value < EPOCH_MILLIS_THRESHOLD ? value * 1000L : value;
    }

    private static String defaultText(String value, String fallback) {
        return isBlank(value) ? fallback : value.trim();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            String clean = blankToNull(value);
            if (clean != null) {
                return clean;
            }
        }
        return null;
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
