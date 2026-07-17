package top.sywyar.pixivdownload.douyin.schedule.work;

import top.sywyar.pixivdownload.config.DownloadSettings;
import top.sywyar.pixivdownload.douyin.client.DouyinClient;
import top.sywyar.pixivdownload.douyin.client.DouyinClientException;
import top.sywyar.pixivdownload.douyin.client.DouyinCookieValidator;
import top.sywyar.pixivdownload.douyin.db.history.DouyinSourceRelation;
import top.sywyar.pixivdownload.douyin.download.DouyinMediaDownloader;
import top.sywyar.pixivdownload.douyin.download.work.DouyinWorkDownloadExecutor;
import top.sywyar.pixivdownload.douyin.model.DouyinWork;
import top.sywyar.pixivdownload.douyin.schedule.codec.DouyinScheduleCodec;
import top.sywyar.pixivdownload.douyin.schedule.codec.DouyinScheduleCodec.RelationData;
import top.sywyar.pixivdownload.douyin.schedule.failure.DouyinScheduledFailureMapper;
import top.sywyar.pixivdownload.douyin.schedule.network.DouyinScheduledRouteScope;
import top.sywyar.pixivdownload.douyin.settings.DouyinPluginSettingsService;
import top.sywyar.pixivdownload.douyin.settings.DouyinRuntimeSettings;
import top.sywyar.pixivdownload.douyin.source.DouyinSourceTypes;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledFailure;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWork;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkContext;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkExecutor;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkRelation;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkResult;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 字符串作品引用到共享同步抖音下载接缝的计划任务适配器。 */
@PluginManagedBean
public final class DouyinScheduledWorkExecutor implements ScheduledWorkExecutor {

    private static final String OWNER_SCOPE = "admin";

    private final DouyinClient client;
    private final DouyinMediaDownloader mediaDownloader;
    private final DouyinWorkDownloadExecutor workDownloadExecutor;
    private final DouyinPluginSettingsService settingsService;
    private final DouyinScheduleCodec codec;
    private final DownloadSettings downloadSettings;

    public DouyinScheduledWorkExecutor(
            DouyinClient client,
            DouyinMediaDownloader mediaDownloader,
            DouyinWorkDownloadExecutor workDownloadExecutor,
            DouyinPluginSettingsService settingsService,
            DouyinScheduleCodec codec,
            DownloadSettings downloadSettings) {
        this.client = Objects.requireNonNull(client, "client");
        this.mediaDownloader = Objects.requireNonNull(mediaDownloader, "mediaDownloader");
        this.workDownloadExecutor = Objects.requireNonNull(
                workDownloadExecutor, "workDownloadExecutor");
        this.settingsService = Objects.requireNonNull(settingsService, "settingsService");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.downloadSettings = Objects.requireNonNull(downloadSettings, "downloadSettings");
    }

    @Override
    public String workType() {
        return DouyinScheduleCodec.WORK_TYPE;
    }

    @Override
    public int maxConcurrency() {
        return Math.max(1, downloadSettings.getMaxConcurrent());
    }

    @Override
    public ScheduledWorkResult execute(ScheduledWork work, ScheduledWorkContext context)
            throws ScheduledExecutionException {
        if (context == null || context.route() == null || !context.route().isResolved()) {
            throw new ScheduledExecutionException(
                    ScheduledFailure.Category.INVALID_DEFINITION,
                    "douyin.schedule.route-unresolved");
        }
        if (context.credential() == null || !context.credential().isPresent()) {
            throw new ScheduledExecutionException(
                    ScheduledFailure.Category.CREDENTIAL_INVALID,
                    "douyin.schedule.credential-missing");
        }
        String workId = codec.decodeWorkId(work);
        List<RelationData> relationData = decodeRelations(work.relations());
        char[] secret = context.credential().copySecret();
        try {
            String cookie = new String(secret);
            DouyinCookieValidator.ensureUsable(cookie);
            context.cancellation().throwIfCancellationRequested();
            return DouyinScheduledRouteScope.call(context.route(), () ->
                    executeScoped(workId, relationData, cookie, context));
        } catch (ScheduledExecutionException failure) {
            throw failure;
        } catch (DouyinClientException failure) {
            throw DouyinScheduledFailureMapper.fromClient(failure);
        } catch (IOException failure) {
            throw DouyinScheduledFailureMapper.networkFailure();
        } catch (RuntimeException failure) {
            throw DouyinScheduledFailureMapper.internal(
                    "douyin.schedule.work-execution-failed");
        } catch (Exception failure) {
            throw DouyinScheduledFailureMapper.networkFailure();
        } finally {
            Arrays.fill(secret, '\0');
        }
    }

    private ScheduledWorkResult executeScoped(
            String workId,
            List<RelationData> relations,
            String cookie,
            ScheduledWorkContext context) throws DouyinClientException, IOException,
            ScheduledExecutionException {
        DouyinWork resolved = client.resolvePublicWork(workId, cookie);
        if (resolved == null || resolved.id() == null || !workId.equals(resolved.id().trim())) {
            throw new ScheduledExecutionException(
                    ScheduledFailure.Category.PAYLOAD_UNSUPPORTED,
                    "douyin.schedule.work-identity-mismatch");
        }
        context.cancellation().throwIfCancellationRequested();
        DouyinRuntimeSettings settings = settingsService.runtimeSettings();
        long discoveredAt = System.currentTimeMillis();
        List<DouyinSourceRelation> sourceRelations = relations.stream()
                .map(relation -> new DouyinSourceRelation(
                        workId,
                        relation.sourceType(),
                        relation.sourceId(),
                        relation.sourceTitle(),
                        null,
                        relation.sourceOrder(),
                        discoveredAt))
                .toList();
        RelationData collection = relations.stream()
                .filter(DouyinScheduledWorkExecutor::isCollectionRelation)
                .findFirst()
                .orElse(null);
        DouyinWorkDownloadExecutor.Result result = workDownloadExecutor.execute(
                new DouyinWorkDownloadExecutor.Request(
                        resolved,
                        mediaDownloader,
                        settings.downloadDirectory(),
                        OWNER_SCOPE,
                        workId,
                        cookie,
                        settings.includeCover(),
                        workId,
                        collection == null ? null : collection.sourceId(),
                        collection == null ? null : collection.sourceTitle(),
                        collection == null ? null : collection.sourceOrder(),
                        sourceRelations,
                        context.cancellation()::isCancellationRequested));
        context.cancellation().throwIfCancellationRequested();
        Map<String, String> attributes = Map.of(
                "fileCount", Integer.toString(result.files().size()));
        return new ScheduledWorkResult(
                result.alreadyDownloaded()
                        ? ScheduledWorkResult.Outcome.ALREADY_COMPLETED
                        : ScheduledWorkResult.Outcome.COMPLETED,
                result.alreadyDownloaded()
                        ? "douyin.schedule.work.already-completed"
                        : "douyin.schedule.work.completed",
                attributes);
    }

    private List<RelationData> decodeRelations(List<ScheduledWorkRelation> relations)
            throws ScheduledExecutionException {
        if (relations == null || relations.isEmpty()) {
            return List.of();
        }
        List<RelationData> decoded = new ArrayList<>(relations.size());
        for (ScheduledWorkRelation relation : relations) {
            decoded.add(codec.decodeRelation(relation));
        }
        return List.copyOf(decoded);
    }

    private static boolean isCollectionRelation(RelationData relation) {
        return DouyinSourceTypes.COLLECTION.equals(relation.sourceType())
                || DouyinSourceTypes.ACCOUNT_FAVORITE_COLLECTION.equals(
                relation.sourceType());
    }
}
