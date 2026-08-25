package top.sywyar.pixivdownload.douyin.download;

import top.sywyar.pixivdownload.douyin.client.DouyinClient;
import top.sywyar.pixivdownload.douyin.db.history.DouyinHistoryService;
import top.sywyar.pixivdownload.douyin.db.history.DouyinSourceRelation;
import top.sywyar.pixivdownload.douyin.model.download.DouyinDownloadPhase;
import top.sywyar.pixivdownload.douyin.model.download.DouyinDownloadRequest;
import top.sywyar.pixivdownload.douyin.model.download.DouyinDownloadSnapshot;
import top.sywyar.pixivdownload.douyin.model.input.DouyinCanonicalDownload;
import top.sywyar.pixivdownload.douyin.model.input.DouyinCanonicalKind;
import top.sywyar.pixivdownload.douyin.model.work.DouyinWork;
import top.sywyar.pixivdownload.douyin.source.DouyinSourceRequest;
import top.sywyar.pixivdownload.douyin.source.DouyinSourceTypes;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;

final class DouyinDownloadTask {

    final String id;
    final Identity identity;
    final DouyinCanonicalKind kind;
    final String workId;
    volatile String originalInput;
    volatile String canonicalUrl;
    volatile String cookie;
    volatile DouyinDownloadPhase phase = DouyinDownloadPhase.QUEUED;
    volatile String messageKey = "douyin.status.queued";
    volatile String errorCode;
    volatile String title;
    volatile String fileName;
    volatile String collectionId;
    volatile String collectionTitle;
    volatile Runtime runtime;
    volatile Path downloadDirectory;
    volatile boolean includeCover;
    volatile DouyinWork preResolvedWork;
    private volatile boolean cancelled;
    private final LinkedHashMap<String, SourceContext> sources = new LinkedHashMap<>();
    private final LinkedHashMap<String, RecordedWork> recordedWorks = new LinkedHashMap<>();

    DouyinDownloadTask(String id,
                       Identity identity,
                       DouyinCanonicalKind kind,
                       String workId) {
        this.id = id;
        this.identity = identity;
        this.kind = kind;
        this.workId = workId;
    }

    synchronized void addSources(List<SourceContext> sourceContexts) {
        for (SourceContext sourceContext : sourceContexts) {
            addSourceContext(sources, sourceContext);
        }
    }

    synchronized boolean absorbSourcesIfRunning(List<SourceContext> sourceContexts) {
        if (!isRunning()) {
            return false;
        }
        addSources(sourceContexts);
        return true;
    }

    synchronized List<DouyinSourceRelation> sourceRelations(DouyinWork work,
                                                             Integer generatedOrder) {
        if (work == null) {
            return List.of();
        }
        return sourceRelations(work.id(), work.pageUrl(), generatedOrder);
    }

    synchronized void registerRecordedWork(DouyinWork work, Integer sourceOrder) {
        recordedWorks.put(work.id(), new RecordedWork(work.id(), work.pageUrl(), sourceOrder));
    }

    synchronized void complete(DouyinHistoryService historyService) {
        failIfCancelled();
        if (historyService != null) {
            for (RecordedWork work : recordedWorks.values()) {
                if (!historyService.recordRelations(work.workId(),
                        sourceRelations(work.workId(), work.pageUrl(), work.sourceOrder()))) {
                    throw new IllegalStateException(
                            "Douyin relations could not be finalized for active work " + work.workId());
                }
            }
        }
        failIfCancelled();
        phase = DouyinDownloadPhase.COMPLETED;
        messageKey = "douyin.status.completed";
    }

    synchronized void cancel() {
        cancelled = true;
        if (phase != DouyinDownloadPhase.COMPLETED && phase != DouyinDownloadPhase.FAILED) {
            phase = DouyinDownloadPhase.CANCELLED;
            messageKey = "douyin.status.cancelled";
        }
    }

    boolean ownedBy(String ownerScope) {
        return identity.ownerScope().equals(ownerScope);
    }

    boolean isRunning() {
        return phase != DouyinDownloadPhase.COMPLETED
                && phase != DouyinDownloadPhase.FAILED
                && phase != DouyinDownloadPhase.CANCELLED;
    }

    boolean isCancelled() {
        return cancelled;
    }

    void failIfCancelled() {
        if (cancelled) {
            throw new Cancelled();
        }
    }

    DouyinDownloadSnapshot snapshot() {
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

    static List<SourceContext> sourceContexts(DouyinDownloadRequest request,
                                              DouyinCanonicalDownload canonical,
                                              String input) {
        LinkedHashMap<String, SourceContext> contexts = new LinkedHashMap<>();
        if (request != null) {
            for (DouyinSourceRequest relation : request.sourceRelations()) {
                if (relation != null) {
                    addSourceContext(contexts, new SourceContext(
                            relation.sourceType(), relation.sourceId(), relation.sourceTitle(),
                            relation.sourceUrl(), relation.sourceOrder()));
                }
            }
            if (firstNonBlank(request.sourceType(), request.sourceId(), request.sourceTitle(),
                    request.sourceUrl()) != null || request.sourceOrder() != null) {
                addSourceContext(contexts, new SourceContext(
                        firstNonBlank(request.sourceType(), inferredSourceType(canonical.kind())),
                        firstNonBlank(request.sourceId(), canonical.stableId()),
                        firstNonBlank(request.sourceTitle(), request.title()),
                        firstNonBlank(request.sourceUrl(), canonical.canonicalUrl(), input),
                        request.sourceOrder()));
            }
        }
        if (contexts.isEmpty()) {
            addSourceContext(contexts, new SourceContext(
                    inferredSourceType(canonical.kind()),
                    canonical.stableId(),
                    request == null ? null : request.title(),
                    firstNonBlank(canonical.canonicalUrl(), input),
                    null));
        }
        return List.copyOf(contexts.values());
    }

    static String safeTitle(String title, String fallbackId) {
        return title == null || title.isBlank() ? fallbackId : title.trim();
    }

    private List<DouyinSourceRelation> sourceRelations(String currentWorkId,
                                                       String pageUrl,
                                                       Integer generatedOrder) {
        long discoveredAt = System.currentTimeMillis();
        return sources.values().stream()
                .map(source -> new DouyinSourceRelation(
                        currentWorkId,
                        firstNonBlank(source.sourceType(), DouyinSourceTypes.SINGLE),
                        firstNonBlank(source.sourceId(), currentWorkId),
                        firstNonBlank(source.sourceTitle(), collectionTitle),
                        firstNonBlank(source.sourceUrl(), originalInput, pageUrl),
                        source.sourceOrder() == null ? generatedOrder : source.sourceOrder(),
                        discoveredAt))
                .toList();
    }

    private static void addSourceContext(LinkedHashMap<String, SourceContext> contexts,
                                         SourceContext candidate) {
        String sourceType = limitedText(candidate.sourceType(), 80);
        String sourceId = limitedText(candidate.sourceId(), 512);
        if (sourceType == null || sourceId == null) {
            return;
        }
        SourceContext normalized = new SourceContext(
                sourceType,
                sourceId,
                limitedText(candidate.sourceTitle(), 500),
                limitedText(candidate.sourceUrl(), 2_048),
                candidate.sourceOrder());
        String key = normalized.identityKey();
        SourceContext existing = contexts.get(key);
        if (existing != null) {
            contexts.put(key, existing.merge(normalized));
        } else if (contexts.size() < DouyinDownloadRequest.MAX_SOURCE_RELATIONS) {
            contexts.put(key, normalized);
        }
    }

    private static String limitedText(String value, int maxLength) {
        String normalized = firstNonBlank(value);
        if (normalized == null) {
            return null;
        }
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private static String inferredSourceType(DouyinCanonicalKind kind) {
        return switch (kind) {
            case SINGLE_WORK -> DouyinSourceTypes.SINGLE;
            case COLLECTION -> DouyinSourceTypes.COLLECTION;
            case USER_SOURCE -> DouyinSourceTypes.USER;
            case MUSIC_SOURCE -> DouyinSourceTypes.MUSIC;
        };
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

    record Identity(String ownerScope, String stableKey) {
    }

    record Runtime(DouyinClient client, DouyinMediaDownloader mediaDownloader) {

        Runtime {
            if (client == null || mediaDownloader == null) {
                throw new IllegalArgumentException("Douyin runtime pair must be complete");
            }
        }
    }

    record SourceContext(String sourceType,
                         String sourceId,
                         String sourceTitle,
                         String sourceUrl,
                         Integer sourceOrder) {

        private String identityKey() {
            return sourceType + '\u0000' + sourceId;
        }

        private SourceContext merge(SourceContext other) {
            return new SourceContext(
                    sourceType,
                    sourceId,
                    firstNonBlank(sourceTitle, other.sourceTitle),
                    firstNonBlank(sourceUrl, other.sourceUrl),
                    sourceOrder == null ? other.sourceOrder : sourceOrder);
        }
    }

    private record RecordedWork(String workId, String pageUrl, Integer sourceOrder) {
    }

    static final class Cancelled extends RuntimeException {
    }
}
