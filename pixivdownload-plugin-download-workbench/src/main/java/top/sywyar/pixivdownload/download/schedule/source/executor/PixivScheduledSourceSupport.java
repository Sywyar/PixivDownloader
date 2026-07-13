package top.sywyar.pixivdownload.download.schedule.source.executor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import top.sywyar.pixivdownload.download.PixivFetchService;
import top.sywyar.pixivdownload.download.schedule.network.PixivScheduledRouteScope;
import top.sywyar.pixivdownload.download.schedule.source.definition.PixivScheduledDefinitionValidator;
import top.sywyar.pixivdownload.download.schedule.source.descriptor.PixivScheduledSourceDescriptors;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialRequirement;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionPlan;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledFailure;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardBinding;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardPoint;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledCheckpoint;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledDiscoveryResult;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceContext;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskDefinition;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskDraft;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWork;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkResult;
import top.sywyar.pixivdownload.schedule.persistence.PixivSchedulePersistenceCodec;
import top.sywyar.pixivdownload.schedule.snapshot.ScheduleTaskSnapshot;

import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntSupplier;

/** 七类 Pixiv 来源适配器共享的纯计划解析与有背压发现驱动。 */
@PluginManagedBean
public final class PixivScheduledSourceSupport {

    static final long SEARCH_PAGE_DELAY_MILLIS = 10_000L;

    private static final String USER_NEW = "user-new";
    private static final String USER_REQUEST = "user-request";
    private static final String SEARCH = "search";
    private static final String SERIES = "series";
    private static final String MY_BOOKMARKS = "my-bookmarks";
    private static final String FOLLOW_LATEST = "follow-latest";
    private static final String COLLECTION = "collection";
    private final ObjectMapper objectMapper;
    private final PixivFetchService fetchService;
    private final PixivSchedulePersistenceCodec persistenceCodec;
    private final PixivScheduledLocalWorkLookup localWorkLookup;
    private final IntSupplier overuseWorkBatchSize;
    private final long searchPageDelayMillis;

    public PixivScheduledSourceSupport(
            ObjectMapper objectMapper,
            PixivFetchService fetchService,
            PixivSchedulePersistenceCodec persistenceCodec,
            PixivScheduledLocalWorkLookup localWorkLookup,
            IntSupplier overuseWorkBatchSize) {
        this(objectMapper, fetchService, persistenceCodec, localWorkLookup,
                overuseWorkBatchSize, SEARCH_PAGE_DELAY_MILLIS);
    }

    PixivScheduledSourceSupport(
            ObjectMapper objectMapper,
            PixivFetchService fetchService,
            PixivSchedulePersistenceCodec persistenceCodec,
            PixivScheduledLocalWorkLookup localWorkLookup,
            int overuseWorkBatchSize,
            long searchPageDelayMillis) {
        this(objectMapper, fetchService, persistenceCodec, localWorkLookup,
                () -> overuseWorkBatchSize, searchPageDelayMillis);
    }

    PixivScheduledSourceSupport(
            ObjectMapper objectMapper,
            PixivFetchService fetchService,
            PixivSchedulePersistenceCodec persistenceCodec,
            PixivScheduledLocalWorkLookup localWorkLookup,
            IntSupplier overuseWorkBatchSize,
            long searchPageDelayMillis) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.fetchService = Objects.requireNonNull(fetchService, "fetchService");
        this.persistenceCodec = Objects.requireNonNull(persistenceCodec, "persistenceCodec");
        this.localWorkLookup = Objects.requireNonNull(localWorkLookup, "localWorkLookup");
        this.overuseWorkBatchSize = Objects.requireNonNull(
                overuseWorkBatchSize, "overuseWorkBatchSize");
        requirePositiveBatchSize(this.overuseWorkBatchSize.getAsInt());
        if (searchPageDelayMillis < 0) {
            throw new IllegalArgumentException("search page delay must not be negative");
        }
        this.searchPageDelayMillis = searchPageDelayMillis;
    }

    /** 用正式 Pixiv codec 规范化浏览器提交的不透明定义并生成安全展示快照。 */
    public ScheduledTaskDefinition prepare(ScheduledTaskDraft draft)
            throws ScheduledExecutionException {
        Objects.requireNonNull(draft, "schedule task draft");
        ScheduledTaskDefinition definition = persistenceCodec.createDefinition(
                draft.taskId(),
                draft.presentation().title(),
                draft.sourceType(),
                draft.definitionJson());
        parseDefinition(definition, draft.sourceType());
        return definition;
    }

    public ScheduledExecutionPlan planUserNew(ScheduledTaskDefinition task)
            throws ScheduledExecutionException {
        ParsedDefinition definition = parseSingleKind(task, USER_NEW, false);
        return plan(definition, false, true);
    }

    public ScheduledExecutionPlan planUserRequest(ScheduledTaskDefinition task)
            throws ScheduledExecutionException {
        ParsedDefinition definition = parseSingleKind(task, USER_REQUEST, true);
        return plan(definition, false, true);
    }

    public ScheduledExecutionPlan planSearch(ScheduledTaskDefinition task)
            throws ScheduledExecutionException {
        ParsedDefinition definition = parseSingleKind(task, SEARCH, false);
        return plan(definition, false, searchMode(definition) == SearchMode.WATERMARK);
    }

    public ScheduledExecutionPlan planSeries(ScheduledTaskDefinition task)
            throws ScheduledExecutionException {
        return plan(parseSingleKind(task, SERIES, false), false, false);
    }

    public ScheduledExecutionPlan planMyBookmarks(ScheduledTaskDefinition task)
            throws ScheduledExecutionException {
        return plan(parseSingleKind(task, MY_BOOKMARKS, false), true, false);
    }

    public ScheduledExecutionPlan planFollowLatest(ScheduledTaskDefinition task)
            throws ScheduledExecutionException {
        return plan(parseSingleKind(task, FOLLOW_LATEST, true), true, true);
    }

    public ScheduledExecutionPlan planCollection(ScheduledTaskDefinition task)
            throws ScheduledExecutionException {
        ParsedDefinition definition = parseDefinition(task, COLLECTION);
        return plan(definition, true, false, Set.of(
                PixivSchedulePersistenceCodec.WORK_TYPE_ILLUST,
                PixivSchedulePersistenceCodec.WORK_TYPE_NOVEL));
    }

    public ScheduledDiscoveryResult discoverUserNew(ScheduledSourceContext context)
            throws ScheduledExecutionException {
        ParsedDefinition definition = parseSingleKind(task(context), USER_NEW, false);
        long watermark = decodeWatermark(context.checkpoint());
        return discoverScoped(context, cookie -> {
            List<String> ids = definition.snapshot().novel()
                    ? fetchService.discoverUserNovelIds(text(definition.source(), "userId"), cookie)
                    : fetchService.discoverUserArtworkIds(text(definition.source(), "userId"), cookie);
            return watermarkScan(context, definition, workType(definition), watermark,
                    page -> page == 1 ? ids : List.of(), false);
        });
    }

    public ScheduledDiscoveryResult discoverUserRequest(ScheduledSourceContext context)
            throws ScheduledExecutionException {
        ParsedDefinition definition = parseSingleKind(task(context), USER_REQUEST, true);
        long watermark = decodeWatermark(context.checkpoint());
        return discoverScoped(context, cookie -> {
            List<String> ids = fetchService.discoverUserRequestArtworkIds(
                    text(definition.source(), "userId"), cookie);
            return watermarkScan(context, definition,
                    PixivSchedulePersistenceCodec.WORK_TYPE_ILLUST, watermark,
                    page -> page == 1 ? ids : List.of(), false);
        });
    }

    public ScheduledDiscoveryResult discoverSearch(ScheduledSourceContext context)
            throws ScheduledExecutionException {
        ParsedDefinition definition = parseSingleKind(task(context), SEARCH, false);
        SearchMode discoveryMode = searchMode(definition);
        long watermark = discoveryMode == SearchMode.WATERMARK
                ? decodeWatermark(context.checkpoint())
                : 0L;
        return discoverScoped(context, cookie -> {
            JsonNode source = definition.source();
            String word = text(source, "word");
            String order = source.path("order").asText("date_d");
            String mode = source.path("mode").asText("all");
            String searchMode = source.path("sMode").asText("s_tag");
            int maxPages = source.path("maxPages").asInt(3);
            String workType = workType(definition);
            return switch (discoveryMode) {
                case WATERMARK -> watermarkScan(
                        context, definition, workType, watermark,
                        page -> definition.snapshot().novel()
                                ? fetchService.discoverSearchNovelIdsPage(
                                        word, order, mode, searchMode, page, cookie)
                                : fetchService.discoverSearchArtworkIdsPage(
                                        word, order, mode, searchMode, page, cookie),
                        true);
                case DOWNLOADED_BOUNDARY -> downloadedBoundaryScan(
                        context, definition, workType,
                        page -> definition.snapshot().novel()
                                ? fetchService.discoverSearchNovelIdsPage(
                                        word, order, mode, searchMode, page, cookie)
                                : fetchService.discoverSearchArtworkIdsPage(
                                        word, order, mode, searchMode, page, cookie));
                case FULL -> {
                    List<String> ids = definition.snapshot().novel()
                            ? fetchService.discoverSearchNovelIds(
                                    word, order, mode, searchMode, maxPages, cookie)
                            : fetchService.discoverSearchArtworkIds(
                                    word, order, mode, searchMode, maxPages, cookie);
                    fullScan(context, definition, workType, ids, 0);
                    yield ScheduledDiscoveryResult.withoutCheckpoint();
                }
            };
        });
    }

    public ScheduledDiscoveryResult discoverSeries(ScheduledSourceContext context)
            throws ScheduledExecutionException {
        ParsedDefinition definition = parseSingleKind(task(context), SERIES, false);
        return discoverScoped(context, cookie -> {
            String seriesId = text(definition.source(), "seriesId");
            List<String> ids = definition.snapshot().novel()
                    ? fetchService.discoverNovelSeriesIds(seriesId, cookie)
                    : fetchService.discoverSeriesArtworkIds(seriesId, cookie);
            fullScan(context, definition, workType(definition), ids, 0);
            return ScheduledDiscoveryResult.withoutCheckpoint();
        });
    }

    public ScheduledDiscoveryResult discoverMyBookmarks(ScheduledSourceContext context)
            throws ScheduledExecutionException {
        ParsedDefinition definition = parseSingleKind(task(context), MY_BOOKMARKS, false);
        return discoverScoped(context, cookie -> {
            String rest = definition.source().path("rest").asText("show");
            List<String> ids = definition.snapshot().novel()
                    ? fetchService.discoverMyNovelBookmarkIds(rest, cookie)
                    : fetchService.discoverMyIllustBookmarkIds(rest, cookie);
            fullScan(context, definition, workType(definition), ids, definition.snapshot().fetchLimit());
            return ScheduledDiscoveryResult.withoutCheckpoint();
        });
    }

    public ScheduledDiscoveryResult discoverFollowLatest(ScheduledSourceContext context)
            throws ScheduledExecutionException {
        ParsedDefinition definition = parseSingleKind(task(context), FOLLOW_LATEST, true);
        long watermark = decodeWatermark(context.checkpoint());
        return discoverScoped(context, cookie -> {
            boolean[] reachedLastPage = {false};
            return watermarkScan(
                    context,
                    definition,
                    PixivSchedulePersistenceCodec.WORK_TYPE_ILLUST,
                    watermark,
                    page -> {
                        if (reachedLastPage[0]) {
                            return List.of();
                        }
                        PixivFetchService.FollowLatestPage result =
                                fetchService.fetchFollowLatestPage(page, cookie);
                        reachedLastPage[0] = result.lastPage();
                        return result.ids();
                    },
                    false);
        });
    }

    public ScheduledDiscoveryResult discoverCollection(ScheduledSourceContext context)
            throws ScheduledExecutionException {
        ParsedDefinition definition = parseDefinition(task(context), COLLECTION);
        return discoverScoped(context, cookie -> {
            PixivFetchService.CollectionWorkIds ids = fetchService.discoverCollectionWorkIds(
                    text(definition.source(), "collectionId"), cookie);
            int budget = definition.snapshot().fetchLimit() > 0
                    ? definition.snapshot().fetchLimit()
                    : -1;
            budget = collectionPass(context, definition,
                    PixivSchedulePersistenceCodec.WORK_TYPE_ILLUST, ids.illustIds(), budget);
            context.workSink().drain();
            collectionPass(context, definition,
                    PixivSchedulePersistenceCodec.WORK_TYPE_NOVEL, ids.novelIds(), budget);
            return ScheduledDiscoveryResult.withoutCheckpoint();
        });
    }

    private ScheduledExecutionPlan plan(
            ParsedDefinition definition,
            boolean accountScoped,
            boolean watermark) {
        return plan(definition, accountScoped, watermark, Set.of(workType(definition)));
    }

    private ScheduledExecutionPlan plan(
            ParsedDefinition definition,
            boolean accountScoped,
            boolean watermark,
            Set<String> workTypes) {
        boolean credentialRequired = accountScoped || definition.snapshot().cookieDependent();
        ScheduledCredentialRequirement credentialRequirement = credentialRequired
                ? ScheduledCredentialRequirement.REQUIRED
                : ScheduledCredentialRequirement.OPTIONAL;
        Long interval = definition.snapshot().download().intervalMs();
        long politeDelay = interval == null ? 0L : Math.max(0L, interval);
        return new ScheduledExecutionPlan(
                workTypes,
                PixivSchedulePersistenceCodec.CREDENTIAL_POLICY_ID,
                credentialRequirement,
                !credentialRequired,
                List.of(new ScheduledGuardBinding(
                        PixivScheduledSourceDescriptors.OVERUSE_GUARD_ID,
                        Set.of(
                                ScheduledGuardPoint.RUN_START,
                                ScheduledGuardPoint.WORK_BATCH,
                                ScheduledGuardPoint.RUN_END,
                                ScheduledGuardPoint.RUN_FAILURE),
                        requirePositiveBatchSize(overuseWorkBatchSize.getAsInt()))),
                watermark ? PixivSchedulePersistenceCodec.CHECKPOINT_SCHEMA : null,
                watermark ? PixivSchedulePersistenceCodec.CHECKPOINT_VERSION : 0,
                definition.snapshot().download().concurrent(),
                politeDelay);
    }

    private ScheduledDiscoveryResult watermarkScan(
            ScheduledSourceContext context,
            ParsedDefinition definition,
            String workType,
            long watermark,
            PageLoader pages,
            boolean delayBetweenPages) throws Exception {
        int queueLimit = watermark == 0L && definition.snapshot().fetchLimit() > 0
                ? definition.snapshot().fetchLimit()
                : 0;
        int queued = 0;
        long newestSeen = 0L;
        for (int page = 1; ; page++) {
            context.cancellation().throwIfCancellationRequested();
            List<String> ids = pages.load(page);
            if (ids == null || ids.isEmpty()) {
                break;
            }
            boolean wholePageAlreadyCompleted = true;
            for (String id : ids) {
                context.cancellation().throwIfCancellationRequested();
                ScheduledWork work = createWork(workType, id);
                if (work == null) {
                    continue;
                }
                long numericId = Long.parseLong(work.key().id());
                newestSeen = Math.max(newestSeen, numericId);
                if (watermark > 0L && numericId <= watermark) {
                    return checkpoint(newestSeen);
                }
                queued++;
                boolean reachedQueueLimit = queueLimit > 0 && queued >= queueLimit;
                if (alreadyCompleted(definition, work)) {
                    context.workSink().completeLocally(work, ScheduledWorkResult.alreadyCompleted());
                } else {
                    wholePageAlreadyCompleted = false;
                    context.workSink().submit(work);
                }
                if (reachedQueueLimit) {
                    return checkpoint(newestSeen);
                }
            }
            if (wholePageAlreadyCompleted) {
                break;
            }
            if (delayBetweenPages) {
                delaySearchPage(context);
            }
        }
        return checkpoint(newestSeen);
    }

    private ScheduledDiscoveryResult downloadedBoundaryScan(
            ScheduledSourceContext context,
            ParsedDefinition definition,
            String workType,
            PageLoader pages) throws Exception {
        int queueLimit = definition.snapshot().fetchLimit();
        int queued = 0;
        for (int page = 1; ; page++) {
            context.cancellation().throwIfCancellationRequested();
            List<String> ids = pages.load(page);
            if (ids == null || ids.isEmpty()) {
                break;
            }
            for (String id : ids) {
                context.cancellation().throwIfCancellationRequested();
                ScheduledWork work = createWork(workType, id);
                if (work == null) {
                    continue;
                }
                if (alreadyCompleted(definition, work)) {
                    context.workSink().completeLocally(work, ScheduledWorkResult.alreadyCompleted());
                    return ScheduledDiscoveryResult.withoutCheckpoint();
                }
                context.workSink().submit(work);
                queued++;
                if (queueLimit > 0 && queued >= queueLimit) {
                    return ScheduledDiscoveryResult.withoutCheckpoint();
                }
            }
            delaySearchPage(context);
        }
        return ScheduledDiscoveryResult.withoutCheckpoint();
    }

    private void fullScan(
            ScheduledSourceContext context,
            ParsedDefinition definition,
            String workType,
            List<String> ids,
            int queueLimit) throws ScheduledExecutionException {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        int queued = 0;
        for (String id : ids) {
            context.cancellation().throwIfCancellationRequested();
            ScheduledWork work = createWork(workType, id);
            if (work == null) {
                continue;
            }
            if (alreadyCompleted(definition, work)) {
                context.workSink().completeLocally(work, ScheduledWorkResult.alreadyCompleted());
                continue;
            }
            context.workSink().submit(work);
            queued++;
            if (queueLimit > 0 && queued >= queueLimit) {
                break;
            }
        }
    }

    private int collectionPass(
            ScheduledSourceContext context,
            ParsedDefinition definition,
            String workType,
            List<String> ids,
            int budget) throws ScheduledExecutionException {
        if (ids == null || ids.isEmpty()) {
            return budget;
        }
        for (String id : ids) {
            context.cancellation().throwIfCancellationRequested();
            ScheduledWork work = createWork(workType, id);
            if (work == null) {
                continue;
            }
            if (alreadyCompleted(definition, work)) {
                context.workSink().completeLocally(work, ScheduledWorkResult.alreadyCompleted());
                continue;
            }
            boolean pending = context.isPending(work.key());
            if (!pending && budget == 0) {
                continue;
            }
            context.workSink().submit(work);
            if (!pending && budget > 0) {
                budget--;
            }
        }
        return budget;
    }

    private boolean alreadyCompleted(ParsedDefinition definition, ScheduledWork work) {
        return localWorkLookup.isAlreadyCompleted(work.key(), definition.snapshot().download());
    }

    private ScheduledWork createWork(String workType, String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        try {
            long numericId = Long.parseLong(id);
            if (numericId <= 0L || !Long.toString(numericId).equals(id)) {
                return null;
            }
            return persistenceCodec.createWorkEnvelope(workType, id);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private long decodeWatermark(ScheduledCheckpoint checkpoint) throws ScheduledExecutionException {
        if (checkpoint == null) {
            return 0L;
        }
        try {
            return persistenceCodec.decodeCheckpoint(checkpoint);
        } catch (IllegalArgumentException ignored) {
            throw failure(ScheduledFailure.Category.INVALID_DEFINITION,
                    "schedule.pixiv.checkpoint-invalid");
        }
    }

    private ScheduledDiscoveryResult checkpoint(long newestSeen) {
        return newestSeen > 0L
                ? ScheduledDiscoveryResult.withCheckpoint(persistenceCodec.encodeCheckpoint(newestSeen))
                : ScheduledDiscoveryResult.withoutCheckpoint();
    }

    private void delaySearchPage(ScheduledSourceContext context) throws ScheduledExecutionException {
        long remaining = searchPageDelayMillis;
        while (remaining > 0L) {
            context.cancellation().throwIfCancellationRequested();
            long slice = Math.min(remaining, 250L);
            try {
                Thread.sleep(slice);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                throw ScheduledExecutionException.cancelled();
            }
            remaining -= slice;
        }
    }

    private ScheduledDiscoveryResult discoverScoped(
            ScheduledSourceContext context,
            DiscoveryOperation operation) throws ScheduledExecutionException {
        Objects.requireNonNull(context, "context");
        context.cancellation().throwIfCancellationRequested();
        if (context.route() == null || !context.route().isResolved()) {
            throw failure(ScheduledFailure.Category.INVALID_DEFINITION,
                    "schedule.pixiv.route-unresolved");
        }
        char[] secret = null;
        try {
            secret = context.credential().copySecret();
            String cookie = secret.length == 0 ? null : new String(secret);
            return PixivScheduledRouteScope.call(context.route(), () -> {
                ScheduledDiscoveryResult result = operation.discover(cookie);
                context.cancellation().throwIfCancellationRequested();
                return result;
            });
        } catch (ScheduledExecutionException failure) {
            throw failure;
        } catch (PixivFetchService.PixivFetchException ignored) {
            throw failure(ScheduledFailure.Category.CREDENTIAL_INVALID,
                    "schedule.pixiv.discovery-credential-invalid");
        } catch (IOException | RestClientException ignored) {
            throw failure(ScheduledFailure.Category.RETRYABLE_NETWORK,
                    "schedule.pixiv.discovery-network-failed");
        } catch (IllegalArgumentException ignored) {
            throw failure(ScheduledFailure.Category.INVALID_DEFINITION,
                    "schedule.pixiv.discovery-definition-invalid");
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception ignored) {
            throw failure(ScheduledFailure.Category.INTERNAL,
                    "schedule.pixiv.discovery-failed");
        } finally {
            if (secret != null) {
                Arrays.fill(secret, '\0');
            }
        }
    }

    private ParsedDefinition parseSingleKind(
            ScheduledTaskDefinition task,
            String expectedSourceType,
            boolean illustOnly) throws ScheduledExecutionException {
        ParsedDefinition definition = parseDefinition(task, expectedSourceType);
        String kind = definition.kind();
        if (!"illust".equals(kind) && !"novel".equals(kind)) {
            throw failure(ScheduledFailure.Category.INVALID_DEFINITION,
                    "schedule.pixiv.definition-kind-invalid");
        }
        if (definition.snapshot().novel() != "novel".equals(kind)) {
            throw failure(ScheduledFailure.Category.INVALID_DEFINITION,
                    "schedule.pixiv.definition-kind-invalid");
        }
        if (illustOnly && "novel".equals(kind)) {
            throw failure(ScheduledFailure.Category.INVALID_DEFINITION,
                    "schedule.pixiv.definition-kind-unsupported");
        }
        return definition;
    }

    private ParsedDefinition parseDefinition(
            ScheduledTaskDefinition task,
            String expectedSourceType) throws ScheduledExecutionException {
        if (task == null
                || !expectedSourceType.equals(task.sourceType())
                || !PixivSchedulePersistenceCodec.DEFINITION_SCHEMA.equals(task.definitionSchema())
                || PixivSchedulePersistenceCodec.DEFINITION_VERSION != task.definitionVersion()) {
            throw failure(ScheduledFailure.Category.INVALID_DEFINITION,
                    "schedule.pixiv.definition-envelope-invalid");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(task.definitionJson());
        } catch (JsonProcessingException ignored) {
            throw failure(ScheduledFailure.Category.INVALID_DEFINITION,
                    "schedule.pixiv.definition-json-invalid");
        }
        if (root == null || !root.isObject()) {
            throw failure(ScheduledFailure.Category.INVALID_DEFINITION,
                    "schedule.pixiv.definition-json-invalid");
        }
        PixivScheduledDefinitionValidator.validate(root, expectedSourceType);
        ScheduleTaskSnapshot snapshot = ScheduleTaskSnapshot.from(root);
        String kind = root.path("kind").asText("illust").toLowerCase(Locale.ROOT);
        if (kind.isEmpty()) {
            kind = "illust";
        }
        return new ParsedDefinition(snapshot, snapshot.source(), kind);
    }

    private SearchMode searchMode(ParsedDefinition definition) {
        int maxPages = definition.source().path("maxPages").asInt(3);
        boolean dateDescending = "date_d".equals(
                definition.source().path("order").asText("date_d"));
        if (maxPages == -1 && dateDescending) {
            return SearchMode.WATERMARK;
        }
        if (maxPages == -1) {
            return SearchMode.DOWNLOADED_BOUNDARY;
        }
        return SearchMode.FULL;
    }

    private static String workType(ParsedDefinition definition) {
        return definition.snapshot().novel()
                ? PixivSchedulePersistenceCodec.WORK_TYPE_NOVEL
                : PixivSchedulePersistenceCodec.WORK_TYPE_ILLUST;
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).asText("");
    }

    private static ScheduledTaskDefinition task(ScheduledSourceContext context)
            throws ScheduledExecutionException {
        if (context == null || context.task() == null) {
            throw failure(ScheduledFailure.Category.INVALID_DEFINITION,
                    "schedule.pixiv.definition-missing");
        }
        return context.task();
    }

    private static ScheduledExecutionException failure(
            ScheduledFailure.Category category,
            String code) {
        return new ScheduledExecutionException(category, code);
    }

    private static int requirePositiveBatchSize(int value) {
        if (value <= 0) {
            throw new IllegalArgumentException("overuse work batch size must be positive");
        }
        return value;
    }

    private enum SearchMode {
        WATERMARK,
        DOWNLOADED_BOUNDARY,
        FULL
    }

    private record ParsedDefinition(
            ScheduleTaskSnapshot snapshot,
            JsonNode source,
            String kind) {
    }

    @FunctionalInterface
    private interface PageLoader {
        List<String> load(int page) throws Exception;
    }

    @FunctionalInterface
    private interface DiscoveryOperation {
        ScheduledDiscoveryResult discover(String cookie) throws Exception;
    }
}
