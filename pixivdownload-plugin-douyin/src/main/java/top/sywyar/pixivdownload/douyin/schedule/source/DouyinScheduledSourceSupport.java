package top.sywyar.pixivdownload.douyin.schedule.source;

import top.sywyar.pixivdownload.douyin.client.DouyinClient;
import top.sywyar.pixivdownload.douyin.client.DouyinClientException;
import top.sywyar.pixivdownload.douyin.client.DouyinCookieValidator;
import top.sywyar.pixivdownload.douyin.model.DouyinAccount;
import top.sywyar.pixivdownload.douyin.model.DouyinAccountSource;
import top.sywyar.pixivdownload.douyin.model.DouyinListing;
import top.sywyar.pixivdownload.douyin.model.DouyinWork;
import top.sywyar.pixivdownload.douyin.schedule.codec.DouyinScheduleCodec;
import top.sywyar.pixivdownload.douyin.schedule.codec.DouyinScheduleCodec.CheckpointState;
import top.sywyar.pixivdownload.douyin.schedule.codec.DouyinScheduleCodec.Definition;
import top.sywyar.pixivdownload.douyin.schedule.failure.DouyinScheduledFailureMapper;
import top.sywyar.pixivdownload.douyin.schedule.network.DouyinScheduledRouteScope;
import top.sywyar.pixivdownload.douyin.schedule.network.DouyinScheduledSourceRouteResolver;
import top.sywyar.pixivdownload.douyin.source.DouyinSourceTypes;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialRequirement;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionPlan;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledFailure;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardBinding;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardPoint;
import top.sywyar.pixivdownload.plugin.api.schedule.network.ScheduledNetworkRoute;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledDiscoveryResult;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceContext;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskDefinition;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskDraft;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWork;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkRelation;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 九类抖音来源共享的纯定义规划与有背压发现驱动。 */
@PluginManagedBean
public final class DouyinScheduledSourceSupport {

    static final int PAGE_SIZE = 20;
    static final int MAX_PAGES = 1_000;
    static final int DEFAULT_KNOWN_STREAK = 20;
    static final int SEARCH_KNOWN_STREAK = 100;

    private final DouyinClient client;
    private final DouyinScheduleCodec codec;
    private final SourceRouteProvider sourceRouteProvider;

    public DouyinScheduledSourceSupport(DouyinClient client, DouyinScheduleCodec codec) {
        this(client, codec, ScheduledNetworkRoute::inherit);
    }

    public DouyinScheduledSourceSupport(
            DouyinClient client,
            DouyinScheduleCodec codec,
            DouyinScheduledSourceRouteResolver routeResolver) {
        this(client, codec,
                Objects.requireNonNull(routeResolver, "routeResolver")::resolve);
    }

    private DouyinScheduledSourceSupport(
            DouyinClient client,
            DouyinScheduleCodec codec,
            SourceRouteProvider sourceRouteProvider) {
        this.client = Objects.requireNonNull(client, "client");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.sourceRouteProvider = Objects.requireNonNull(
                sourceRouteProvider, "sourceRouteProvider");
    }

    public ScheduledTaskDefinition prepare(ScheduledTaskDraft draft)
            throws ScheduledExecutionException {
        return codec.prepare(draft);
    }

    public ScheduledExecutionPlan plan(ScheduledTaskDefinition task, String sourceType)
            throws ScheduledExecutionException {
        codec.decodeDefinition(task, sourceType);
        return new ScheduledExecutionPlan(
                Set.of(DouyinScheduleCodec.WORK_TYPE),
                DouyinScheduledSourceDescriptors.CREDENTIAL_POLICY_ID,
                ScheduledCredentialRequirement.REQUIRED,
                false,
                List.of(new ScheduledGuardBinding(
                        DouyinScheduledSourceDescriptors.GUARD_ID,
                        EnumSet.allOf(ScheduledGuardPoint.class),
                        PAGE_SIZE)),
                DouyinScheduleCodec.CHECKPOINT_SCHEMA,
                DouyinScheduleCodec.CHECKPOINT_VERSION,
                2,
                300L,
                sourceRouteProvider.resolve());
    }

    public ScheduledDiscoveryResult discover(ScheduledSourceContext context, String sourceType)
            throws ScheduledExecutionException {
        if (context == null || context.task() == null) {
            throw new ScheduledExecutionException(
                    ScheduledFailure.Category.INVALID_DEFINITION,
                    "douyin.schedule.definition-missing");
        }
        if (context.route() == null || !context.route().isResolved()) {
            throw new ScheduledExecutionException(
                    ScheduledFailure.Category.INVALID_DEFINITION,
                    "douyin.schedule.route-unresolved");
        }
        Definition definition = codec.decodeDefinition(context.task(), sourceType);
        CheckpointState checkpoint = codec.decodeCheckpoint(context.checkpoint());
        context.cancellation().throwIfCancellationRequested();
        if (context.credential() == null || !context.credential().isPresent()) {
            throw new ScheduledExecutionException(
                    ScheduledFailure.Category.CREDENTIAL_INVALID,
                    "douyin.schedule.credential-missing");
        }

        char[] secret = context.credential().copySecret();
        try {
            String cookie = new String(secret);
            DouyinCookieValidator.ensureUsable(cookie);
            return DouyinScheduledRouteScope.call(
                    context.route(),
                    () -> discoverScoped(context, definition, checkpoint, cookie));
        } catch (ScheduledExecutionException failure) {
            throw failure;
        } catch (DouyinClientException failure) {
            throw DouyinScheduledFailureMapper.fromClient(failure);
        } catch (IllegalArgumentException failure) {
            throw new ScheduledExecutionException(
                    ScheduledFailure.Category.INVALID_DEFINITION,
                    "douyin.schedule.discovery-input-invalid");
        } catch (RuntimeException failure) {
            throw DouyinScheduledFailureMapper.internal(
                    "douyin.schedule.discovery-failed");
        } catch (Exception failure) {
            throw DouyinScheduledFailureMapper.networkFailure();
        } finally {
            Arrays.fill(secret, '\0');
        }
    }

    private ScheduledDiscoveryResult discoverScoped(
            ScheduledSourceContext context,
            Definition definition,
            CheckpointState checkpoint,
            String cookie) throws ScheduledExecutionException, DouyinClientException {
        DouyinAccount account = requiresAccount(definition.sourceType())
                ? client.resolveAccount(cookie) : null;
        PageLoader loader = pageLoader(definition, account, cookie);
        Set<String> priorFrontier = Set.copyOf(checkpoint.frontier());
        LinkedHashSet<String> candidateFrontier = new LinkedHashSet<>();
        Set<String> seenWorkIds = new HashSet<>();
        Set<String> seenCursors = new HashSet<>();
        boolean favoriteWorksSource = DouyinSourceTypes.ACCOUNT_FAVORITE_WORKS
                .equals(definition.sourceType());

        String cursor = "0";
        String resumeAfter = checkpoint.resumeAfter();
        boolean resumeReached = resumeAfter == null;
        String lastAcceptedHash = null;
        int accepted = 0;
        int sourceOrder = 0;
        int knownStreak = 0;
        int knownStreakLimit = DouyinSourceTypes.SEARCH.equals(definition.sourceType())
                ? SEARCH_KNOWN_STREAK : DEFAULT_KNOWN_STREAK;
        boolean recoveryScan = checkpoint.recovery();
        boolean stopAtKnownStreak = !favoriteWorksSource && !recoveryScan;

        for (int page = 0; page < MAX_PAGES; page++) {
            context.cancellation().throwIfCancellationRequested();
            String cursorKey = normalizeCursor(cursor);
            if (!seenCursors.add(cursorKey)) {
                throw paginationFailure();
            }
            DouyinListing listing = loader.load(cursorKey);
            if (listing == null) {
                throw DouyinScheduledFailureMapper.internal(
                        "douyin.schedule.discovery-empty-response");
            }
            String relationId = relationId(definition, account);
            String relationTitle = relationTitle(definition, account, listing);
            for (DouyinWork work : listing.items()) {
                context.cancellation().throwIfCancellationRequested();
                if (work == null || work.id() == null || work.id().isBlank()
                        || !seenWorkIds.add(work.id().trim())) {
                    continue;
                }
                String workId = work.id().trim();
                String identityHash;
                try {
                    identityHash = codec.identityHash(workId);
                } catch (ScheduledExecutionException malformedWork) {
                    continue;
                }
                if (!resumeReached) {
                    boolean checkpointIdentity = priorFrontier.contains(identityHash)
                            || identityHash.equals(resumeAfter);
                    if (checkpointIdentity) {
                        retainIdentity(candidateFrontier, identityHash, favoriteWorksSource);
                    }
                    if (identityHash.equals(resumeAfter)) {
                        resumeReached = true;
                    }
                    sourceOrder++;
                    continue;
                }

                retainIdentity(candidateFrontier, identityHash, favoriteWorksSource);
                if (priorFrontier.contains(identityHash)) {
                    knownStreak++;
                    sourceOrder++;
                    if (stopAtKnownStreak && knownStreak >= knownStreakLimit) {
                        return completedCheckpoint(checkpoint, candidateFrontier);
                    }
                    continue;
                }
                knownStreak = 0;
                ScheduledWorkRelation relation = codec.createRelation(
                        definition.sourceType(), relationId, relationTitle, sourceOrder);
                ScheduledWork scheduledWork = codec.createWork(
                        workId, work.title(), work.authorName(), relation);
                if (!context.isPending(scheduledWork.key())) {
                    context.workSink().submit(scheduledWork);
                    accepted++;
                }
                lastAcceptedHash = identityHash;
                sourceOrder++;
                if (definition.fetchLimit() > 0 && accepted >= definition.fetchLimit()) {
                    return partialCheckpoint(checkpoint, candidateFrontier, lastAcceptedHash);
                }
            }

            if (!listing.hasMore()) {
                if (!resumeReached) {
                    return recoveryCheckpoint(checkpoint, candidateFrontier);
                }
                return completedCheckpoint(checkpoint, candidateFrontier);
            }
            String nextCursor = normalizeCursor(listing.nextCursor());
            if (nextCursor.equals(cursorKey)) {
                throw paginationFailure();
            }
            cursor = nextCursor;
        }
        throw paginationFailure();
    }

    private ScheduledDiscoveryResult partialCheckpoint(
            CheckpointState previous,
            LinkedHashSet<String> observed,
            String resumeAfter) {
        return ScheduledDiscoveryResult.withCheckpoint(codec.encodeCheckpoint(
                new CheckpointState(
                        mergeFrontier(observed, previous.frontier()),
                        resumeAfter,
                        previous.recovery())));
    }

    private ScheduledDiscoveryResult completedCheckpoint(
            CheckpointState previous,
            LinkedHashSet<String> observed) {
        return ScheduledDiscoveryResult.withCheckpoint(codec.encodeCheckpoint(
                new CheckpointState(
                        mergeFrontier(observed, previous.frontier()), null, false)));
    }

    private ScheduledDiscoveryResult recoveryCheckpoint(
            CheckpointState previous,
            LinkedHashSet<String> observed) {
        return ScheduledDiscoveryResult.withCheckpoint(codec.encodeCheckpoint(
                new CheckpointState(
                        mergeFrontier(observed, previous.frontier()), null, true)));
    }

    private static List<String> mergeFrontier(
            LinkedHashSet<String> observed,
            List<String> previous) {
        LinkedHashSet<String> merged = new LinkedHashSet<>(observed);
        merged.addAll(previous);
        return merged.stream()
                .limit(DouyinScheduleCodec.MAX_FRONTIER_IDENTITIES)
                .toList();
    }

    private static void retainIdentity(
            LinkedHashSet<String> frontier,
            String identityHash,
            boolean requireExactRetention) throws ScheduledExecutionException {
        if (frontier.contains(identityHash)) {
            return;
        }
        if (frontier.size() >= DouyinScheduleCodec.MAX_FRONTIER_IDENTITIES) {
            if (requireExactRetention) {
                throw new ScheduledExecutionException(
                        ScheduledFailure.Category.PAYLOAD_UNSUPPORTED,
                        "douyin.schedule.checkpoint-capacity-exceeded");
            }
            return;
        }
        frontier.add(identityHash);
    }

    private PageLoader pageLoader(Definition definition, DouyinAccount account, String cookie) {
        return switch (definition.sourceType()) {
            case DouyinSourceTypes.USER -> cursor ->
                    client.listUserWorksPage(definition.sourceId(), cursor, PAGE_SIZE, cookie);
            case DouyinSourceTypes.SEARCH -> cursor ->
                    client.searchWorksPage(definition.sourceId(), cursor, PAGE_SIZE, cookie);
            case DouyinSourceTypes.COLLECTION,
                    DouyinSourceTypes.ACCOUNT_FAVORITE_COLLECTION -> cursor ->
                    client.listSeriesWorksPage(definition.sourceId(), cursor, PAGE_SIZE, cookie);
            case DouyinSourceTypes.MUSIC -> cursor ->
                    client.listMusicWorksPage(definition.sourceId(), cursor, PAGE_SIZE, cookie);
            case DouyinSourceTypes.ACCOUNT_FAVORITE_FOLDER -> cursor ->
                    client.listFavoriteFolderWorksPage(
                            definition.sourceId(), cursor, PAGE_SIZE, cookie);
            case DouyinSourceTypes.ACCOUNT_OWN_WORKS -> cursor ->
                    client.listAccountWorksPage(
                            account, DouyinAccountSource.OWN_WORKS, cursor, PAGE_SIZE, cookie);
            case DouyinSourceTypes.ACCOUNT_LIKED_WORKS -> cursor ->
                    client.listAccountWorksPage(
                            account, DouyinAccountSource.LIKED_WORKS, cursor, PAGE_SIZE, cookie);
            case DouyinSourceTypes.ACCOUNT_FAVORITE_WORKS -> cursor ->
                    client.listAccountWorksPage(
                            account, DouyinAccountSource.FAVORITE_WORKS, cursor, PAGE_SIZE, cookie);
            default -> throw new IllegalArgumentException("unsupported Douyin source type");
        };
    }

    private static boolean requiresAccount(String sourceType) {
        return sourceType != null && sourceType.startsWith("douyin.account.");
    }

    private static String relationId(Definition definition, DouyinAccount account) {
        if (DouyinSourceTypes.ACCOUNT_OWN_WORKS.equals(definition.sourceType())) {
            return account.accountKey();
        }
        if (DouyinSourceTypes.ACCOUNT_LIKED_WORKS.equals(definition.sourceType())) {
            return "liked";
        }
        if (DouyinSourceTypes.ACCOUNT_FAVORITE_WORKS.equals(definition.sourceType())) {
            return "favorites";
        }
        return definition.sourceId();
    }

    private static String relationTitle(
            Definition definition,
            DouyinAccount account,
            DouyinListing listing) {
        if (account != null
                && !DouyinSourceTypes.ACCOUNT_FAVORITE_FOLDER.equals(definition.sourceType())
                && !DouyinSourceTypes.ACCOUNT_FAVORITE_COLLECTION.equals(
                definition.sourceType())) {
            return firstNonBlank(account.displayName(), listing.ownerName(), listing.title());
        }
        if (DouyinSourceTypes.SEARCH.equals(definition.sourceType())) {
            return definition.sourceId();
        }
        return firstNonBlank(listing.title(), listing.ownerName(), definition.sourceId());
    }

    private static String normalizeCursor(String cursor) {
        return cursor == null || cursor.isBlank() ? "0" : cursor.trim();
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

    private static ScheduledExecutionException paginationFailure() {
        return new ScheduledExecutionException(
                ScheduledFailure.Category.RETRYABLE_NETWORK,
                "douyin.schedule.pagination-stalled");
    }

    @FunctionalInterface
    private interface PageLoader {
        DouyinListing load(String cursor) throws DouyinClientException;
    }

    @FunctionalInterface
    private interface SourceRouteProvider {
        ScheduledNetworkRoute resolve() throws ScheduledExecutionException;
    }
}
