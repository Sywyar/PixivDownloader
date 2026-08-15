package top.sywyar.pixivdownload.core.ownership;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.ai.AiClientSettings;
import top.sywyar.pixivdownload.config.RuntimePathProvider;
import top.sywyar.pixivdownload.core.artwork.download.ArtworkAuthorLookup;
import top.sywyar.pixivdownload.core.artwork.download.ArtworkDownloadCompletion;
import top.sywyar.pixivdownload.core.artwork.download.ArtworkDownloadHistory;
import top.sywyar.pixivdownload.core.artwork.download.ArtworkDownloadLookup;
import top.sywyar.pixivdownload.core.artwork.download.ArtworkDownloadStatistics;
import top.sywyar.pixivdownload.core.artwork.download.ArtworkSeriesObservation;
import top.sywyar.pixivdownload.core.artwork.download.ArtworkSeriesObserver;
import top.sywyar.pixivdownload.core.collection.CollectionDownloadRootResolver;
import top.sywyar.pixivdownload.core.collection.WorkCollectionMembership;
import top.sywyar.pixivdownload.core.download.InteractiveDownloadExecutionLane;
import top.sywyar.pixivdownload.core.ffmpeg.FfmpegCommandResolver;
import top.sywyar.pixivdownload.core.ffmpeg.ResolvedFfmpegCommand;
import top.sywyar.pixivdownload.core.gallery.model.identity.GalleryMediaKey;
import top.sywyar.pixivdownload.core.gallery.model.media.GalleryMediaAsset;
import top.sywyar.pixivdownload.core.gallery.model.media.GalleryMediaKind;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxClient;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxException;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxFailure;
import top.sywyar.pixivdownload.core.pixiv.PixivBookmarkActions;
import top.sywyar.pixivdownload.core.pixiv.PixivImageDownloader;
import top.sywyar.pixivdownload.core.pixiv.PixivImageTransferObserver;
import top.sywyar.pixivdownload.core.pixiv.PixivProxyAccessDecision;
import top.sywyar.pixivdownload.core.pixiv.PixivProxyAccessOutcome;
import top.sywyar.pixivdownload.core.pixiv.PixivProxyAccessPolicy;
import top.sywyar.pixivdownload.core.pixiv.thumbnail.PixivThumbnailFetchException;
import top.sywyar.pixivdownload.core.pixiv.thumbnail.PixivThumbnailFailure;
import top.sywyar.pixivdownload.core.pixiv.thumbnail.PixivThumbnailFetcher;
import top.sywyar.pixivdownload.core.quota.VisitorDownloadQuotaReservation;
import top.sywyar.pixivdownload.core.quota.VisitorDownloadQuotaService;
import top.sywyar.pixivdownload.core.schedule.ScheduleTaskDefinitionUpdate;
import top.sywyar.pixivdownload.core.schedule.ScheduledPendingWork;
import top.sywyar.pixivdownload.core.schedule.ScheduledTask;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskCreate;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskStore;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleLastOutcome;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleRunCompletion;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleRunState;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleRunToken;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleSuspendReason;
import top.sywyar.pixivdownload.core.work.service.AuthorObservationService;
import top.sywyar.pixivdownload.core.work.service.DownloadPathGuard;
import top.sywyar.pixivdownload.core.work.service.DownloadPathRejectedException;
import top.sywyar.pixivdownload.core.work.service.WorkFileNameCatalog;
import top.sywyar.pixivdownload.core.work.service.WorkMetadataCapture;
import top.sywyar.pixivdownload.core.work.service.WorkTagCatalog;
import top.sywyar.pixivdownload.i18n.LegacyLocaleBundlePolicy;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.i18n.NamespaceMessageResolver;
import top.sywyar.pixivdownload.notification.NotificationDispatcher;
import top.sywyar.pixivdownload.push.PushChannelId;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationAudio;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationReferenceVoice;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationVoiceEngine;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationVoiceRequest;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationVoiceSelection;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationVoiceSelector;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * core-api 所有权白名单。新增公开类型必须先说明为何由核心长期拥有，再归入明确类别；
 * 仅满足「纯 JDK」不足以进入本模块。
 */
@DisplayName("core-api 所有权边界")
class CoreApiOwnershipGuardTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("top.sywyar.pixivdownload");

    private static final Map<String, Set<String>> APPROVED_TYPES_BY_OWNER = Map.ofEntries(
            Map.entry("宿主运行时窄端口", union(
                    types("top.sywyar.pixivdownload.config",
                            "DebugSettings", "DownloadSettings", "MultiModeSettings",
                            "OutboundProxyEndpoint", "OutboundProxyOverride", "OutboundProxySettings",
                            "RuntimePathProvider"),
                    types("top.sywyar.pixivdownload.core.db.pathprefix", "StoredPathCodec"),
                    types("top.sywyar.pixivdownload.core.download", "InteractiveDownloadExecutionLane"),
                    types("top.sywyar.pixivdownload.core.ffmpeg",
                            "FfmpegCommandResolver", "ResolvedFfmpegCommand"),
                    types("top.sywyar.pixivdownload.core.web", "AcquisitionCredentialResolver"),
                    types("top.sywyar.pixivdownload.i18n",
                            "MessageResolver", "NamespaceMessageResolver", "ResourceBundleMessageResolver", "LocaleBundlePolicy", "LegacyLocaleBundlePolicy"),
                    types("top.sywyar.pixivdownload.setup",
                            "ApplicationModeProvider", "InstallIdentityProvider", "UserDisplayNameProvider"),
                    types("top.sywyar.pixivdownload.web", "LocalRequestTrust"))),
            Map.entry("AI 调用稳定契约", union(
                    types("top.sywyar.pixivdownload.ai",
                            "AiChatClient", "AiClientException", "AiClientSettings"),
                    types("top.sywyar.pixivdownload.ai.model",
                            "AiChatMessage", "AiChatOptions", "AiChatResult"))),
            Map.entry("核心归档与收藏能力", union(
                    types("top.sywyar.pixivdownload.core.archive",
                            "ArchiveExportEntry", "ArchiveExportRequest", "ArchiveExportResult",
                            "ArchiveExportRules", "ArchiveExportService", "ArchiveWorkDeletion"),
                    types("top.sywyar.pixivdownload.core.collection",
                            "CollectionDownloadRootResolver", "WorkCollectionMembership"))),
            Map.entry("游客下载配额", types("top.sywyar.pixivdownload.core.quota",
                    "VisitorDownloadQuotaReservation", "VisitorDownloadQuotaService")),
            Map.entry("核心计划任务持久化语义", union(
                    types("top.sywyar.pixivdownload.core.schedule",
                            "ScheduleTaskDefinitionUpdate", "ScheduledPendingWork", "ScheduledTask",
                            "ScheduledTaskCreate", "ScheduledTaskStore"),
                    types("top.sywyar.pixivdownload.core.schedule.state",
                            "ScheduleLastOutcome", "ScheduleRunCompletion", "ScheduleRunState",
                            "ScheduleRunToken", "ScheduleSuspendReason"))),
            Map.entry("主画廊长期中性协议", union(
                    types("top.sywyar.pixivdownload.core.gallery",
                            "GalleryProjectionProvider", "GalleryWorkProvider"),
                    types("top.sywyar.pixivdownload.core.gallery.facet",
                            "GalleryAuthorFacet", "GalleryFacet", "GalleryFacetPage",
                            "GalleryFacetType", "GalleryTagFacet"),
                    types("top.sywyar.pixivdownload.core.gallery.frontend",
                            "GalleryFrontendContribution", "GalleryFrontendHook", "GalleryFrontendProvider",
                            "GalleryFrontendScope"),
                    types("top.sywyar.pixivdownload.core.gallery.model",
                            "GalleryDiagnostic", "GalleryFieldCapability", "GalleryKind"),
                    types("top.sywyar.pixivdownload.core.gallery.model.identity",
                            "GalleryMediaKey", "GalleryProjectionKey", "GalleryWorkKey"),
                    types("top.sywyar.pixivdownload.core.gallery.model.media",
                            "GalleryMediaAsset", "GalleryMediaKind"),
                    types("top.sywyar.pixivdownload.core.gallery.model.projection",
                            "GalleryDataAccess", "GalleryProjection", "GalleryProjectionDescriptor",
                            "GalleryProjectionPage"),
                    types("top.sywyar.pixivdownload.core.gallery.model.work",
                            "GalleryActor", "GalleryAiStatus", "GalleryContentRating", "GalleryTag",
                            "GalleryWork", "GalleryWorkDescriptor"),
                    types("top.sywyar.pixivdownload.core.gallery.query",
                            "GalleryFilter", "GalleryFilterCapability", "GalleryFilterField", "GalleryFilterMode",
                            "GalleryProjectionQuery", "GallerySortDirection", "GallerySortField"),
                    types("top.sywyar.pixivdownload.core.gallery.runtime",
                            "GalleryCountResult", "GalleryRuntimeQuery", "GalleryRuntimeSnapshot",
                            "GalleryWorkResult"))),
            Map.entry("核心作品事实与共享纯语义", union(
                    types("top.sywyar.pixivdownload.core.artwork.download",
                            "ArtworkAuthorLookup", "ArtworkDownloadCompletion", "ArtworkDownloadHistory",
                            "ArtworkDownloadLookup", "ArtworkDownloadStatistics",
                            "ArtworkSeriesObservation", "ArtworkSeriesObserver"),
                    types("top.sywyar.pixivdownload.core.hash",
                            "ArtworkHashEntry", "ArtworkHashFingerprint", "ArtworkHashIndexMaintenance",
                            "ArtworkHashIndexQuery"),
                    types("top.sywyar.pixivdownload.core.pixiv",
                            "PixivAjaxClient", "PixivAjaxException", "PixivAjaxFailure",
                            "PixivBookmarkActions", "PixivCookieUserResolver",
                            "PixivCoverUrlResolver", "PixivDescriptionHtml", "PixivImageDownloader",
                            "PixivImageTransferObserver", "PixivProxyAccessDecision",
                            "PixivProxyAccessOutcome", "PixivProxyAccessPolicy"),
                    types("top.sywyar.pixivdownload.core.pixiv.filename",
                            "PixivWorkFileNameFormatter"),
                    types("top.sywyar.pixivdownload.core.pixiv.thumbnail",
                            "PixivThumbnailFetcher", "PixivThumbnailFetchException",
                            "PixivThumbnailFailure"),
                    types("top.sywyar.pixivdownload.core.time", "EpochMillisNormalizer"),
                    types("top.sywyar.pixivdownload.core.work",
                            "WorkActionResult"),
                    types("top.sywyar.pixivdownload.core.work.model",
                            "LocalWorkAsset", "PagedResult", "WorkAssetFile", "WorkFileNameTemplateRef",
                            "WorkMetadata", "WorkRestriction", "WorkSummary", "WorkTag", "WorkType",
                            "WorkVisibilityScope"),
                    types("top.sywyar.pixivdownload.core.work.query",
                            "AuthorQuery", "AuthorSummary", "SeriesNeighbors", "TagOption", "TagQuery", "WorkQuery"),
                    types("top.sywyar.pixivdownload.core.work.service",
                            "AuthorObservationService", "DownloadPathGuard", "DownloadPathRejectedException",
                            "WorkAssetService",
                            "WorkDeletionException", "WorkDeletionService", "WorkFileNameCatalog",
                            "WorkMetadataCapture", "WorkMetadataRepository", "WorkQueryService", "WorkTagCatalog",
                            "WorkVisibilityDeniedException", "WorkVisibilityService"))),
            Map.entry("核心统计只读语义", types("top.sywyar.pixivdownload.core.stats",
                    "StatsAggregates", "StatsQueryStore")),
            Map.entry("中性通知场景", types("top.sywyar.pixivdownload.notification",
                    "NotificationConfigKeys", "NotificationDispatcher", "NotificationScenario",
                    "NotificationSeverity", "NotificationSink")),
            Map.entry("推送共享协议与纯转换", types("top.sywyar.pixivdownload.push",
                    "PushChannel", "PushChannelId", "PushChannelSettings", "PushDispatcher", "PushFormat",
                    "PushFormatConverter", "PushMessage", "PushResult", "RenderedMessage")),
            Map.entry("朗读引擎稳定契约", types("top.sywyar.pixivdownload.tts.narration.engine",
                    "NarrationAudio", "NarrationReferenceVoice", "NarrationSpeechText", "NarrationVoiceEngine",
                    "NarrationVoiceException", "NarrationVoiceMode", "NarrationVoiceRequest",
                    "NarrationVoiceSelection", "NarrationVoiceSelector"))
    );

    private static final Set<String> APPROVED_PUBLIC_NESTED_TYPES = Set.of(
            "top.sywyar.pixivdownload.core.stats.StatsAggregates$Overview",
            "top.sywyar.pixivdownload.core.stats.StatsAggregates$AuthorStat",
            "top.sywyar.pixivdownload.core.stats.StatsAggregates$TagStat",
            "top.sywyar.pixivdownload.core.stats.StatsAggregates$MonthlyStat",
            "top.sywyar.pixivdownload.core.work.query.SeriesNeighbors$Neighbor",
            "top.sywyar.pixivdownload.core.work.query.WorkQuery$Builder",
            "top.sywyar.pixivdownload.core.work.service.WorkDeletionException$Reason",
            "top.sywyar.pixivdownload.core.ffmpeg.ResolvedFfmpegCommand$Source",
            "top.sywyar.pixivdownload.push.PushResult$Status"
    );

    private static final Map<String, Object> APPROVED_PUBLIC_CONSTANTS = Map.ofEntries(
            Map.entry("top.sywyar.pixivdownload.ai.model.AiChatMessage#ROLE_SYSTEM:java.lang.String", "system"),
            Map.entry("top.sywyar.pixivdownload.ai.model.AiChatMessage#ROLE_USER:java.lang.String", "user"),
            Map.entry("top.sywyar.pixivdownload.ai.model.AiChatMessage#ROLE_ASSISTANT:java.lang.String", "assistant"),
            Map.entry("top.sywyar.pixivdownload.core.archive.ArchiveExportRules#FORMAT_ZIP:java.lang.String", "zip"),
            Map.entry("top.sywyar.pixivdownload.core.archive.ArchiveExportRules#GROUP_BY_ID:java.lang.String", "id"),
            Map.entry("top.sywyar.pixivdownload.core.archive.ArchiveExportRules#GROUP_BY_AUTHOR:java.lang.String", "author"),
            Map.entry("top.sywyar.pixivdownload.core.schedule.ScheduledTask#LEGACY_STORAGE_VERSION:int", 0),
            Map.entry("top.sywyar.pixivdownload.core.schedule.ScheduledTask#CURRENT_STORAGE_VERSION:int", 1),
            Map.entry("top.sywyar.pixivdownload.core.schedule.ScheduledTask#TRIGGER_INTERVAL:java.lang.String", "interval"),
            Map.entry("top.sywyar.pixivdownload.core.schedule.ScheduledTask#TRIGGER_CRON:java.lang.String", "cron"),
            Map.entry("top.sywyar.pixivdownload.core.web.AcquisitionCredentialResolver#HEADER_NAME:java.lang.String",
                    "X-Acquisition-Credential"),
            Map.entry("top.sywyar.pixivdownload.core.web.AcquisitionCredentialResolver#MAX_LENGTH:int", 16_384),
            Map.entry("top.sywyar.pixivdownload.core.pixiv.PixivImageTransferObserver#MAX_IMAGE_BYTES:long",
                    100L * 1024L * 1024L),
            Map.entry("top.sywyar.pixivdownload.core.pixiv.PixivImageTransferObserver#MAX_TASK_BYTES:long",
                    1024L * 1024L * 1024L),
            Map.entry("top.sywyar.pixivdownload.core.pixiv.filename.PixivWorkFileNameFormatter#DEFAULT_TEMPLATE:java.lang.String",
                    "{artwork_id}_p{page}"),
            Map.entry("top.sywyar.pixivdownload.core.work.WorkActionResult#SUCCESS:java.lang.String", "success"),
            Map.entry("top.sywyar.pixivdownload.core.work.WorkActionResult#FAILED:java.lang.String", "failed"),
            Map.entry("top.sywyar.pixivdownload.core.work.WorkActionResult#SKIPPED:java.lang.String", "skipped"),
            Map.entry("top.sywyar.pixivdownload.core.work.WorkActionResult#EXISTS:java.lang.String", "exists"),
            Map.entry("top.sywyar.pixivdownload.notification.NotificationConfigKeys#SCENARIO_PREFIX:java.lang.String",
                    "notification.scenario."),
            Map.entry("top.sywyar.pixivdownload.notification.NotificationConfigKeys#SCENARIO_ENABLED_SUFFIX:java.lang.String",
                    ".enabled"),
            Map.entry("top.sywyar.pixivdownload.push.PushResult#DETAIL_MESSAGE_PREFIX:java.lang.String", "push.result.detail."),
            Map.entry("top.sywyar.pixivdownload.push.PushResult#DETAIL_CHANNEL_UNAVAILABLE:java.lang.String",
                    "push.result.detail.channel-unavailable"),
            Map.entry("top.sywyar.pixivdownload.push.PushResult#DETAIL_CHANNEL_NOT_CONFIGURED:java.lang.String",
                    "push.result.detail.channel-not-configured"),
            Map.entry("top.sywyar.pixivdownload.push.PushResult#DETAIL_SETTINGS_INCOMPLETE:java.lang.String",
                    "push.result.detail.settings-incomplete"),
            Map.entry("top.sywyar.pixivdownload.push.PushResult#DETAIL_SETTINGS_TYPE_MISMATCH:java.lang.String",
                    "push.result.detail.settings-type-mismatch"),
            Map.entry("top.sywyar.pixivdownload.push.PushResult#DETAIL_UNEXPECTED_ERROR:java.lang.String",
                    "push.result.detail.unexpected-error"),
            Map.entry("top.sywyar.pixivdownload.push.PushResult#DETAIL_SERIALIZATION_FAILED:java.lang.String",
                    "push.result.detail.serialization-failed"),
            Map.entry("top.sywyar.pixivdownload.push.PushResult#DETAIL_SIGNING_FAILED:java.lang.String",
                    "push.result.detail.signing-failed"),
            Map.entry("top.sywyar.pixivdownload.push.PushResult#DETAIL_INVALID_CONTENT_TYPE:java.lang.String",
                    "push.result.detail.invalid-content-type"),
            Map.entry("top.sywyar.pixivdownload.push.PushResult#DETAIL_INVALID_URL:java.lang.String",
                    "push.result.detail.invalid-url"),
            Map.entry("top.sywyar.pixivdownload.i18n.LegacyLocaleBundlePolicy#INSTANCE:top.sywyar.pixivdownload.i18n.LocaleBundlePolicy",
                    LegacyLocaleBundlePolicy.INSTANCE)
    );

    private static final Map<String, List<String>> APPROVED_ENUM_CONSTANTS_BY_TYPE = Map.ofEntries(
            Map.entry("top.sywyar.pixivdownload.core.ffmpeg.ResolvedFfmpegCommand$Source",
                    List.of("MANAGED", "BUNDLED", "SYSTEM", "FALLBACK")),
            Map.entry("top.sywyar.pixivdownload.core.pixiv.thumbnail.PixivThumbnailFailure",
                    List.of("INVALID_TARGET", "HTTP_STATUS", "TRANSPORT")),
            Map.entry("top.sywyar.pixivdownload.core.gallery.facet.GalleryFacetType",
                    List.of("AUTHOR", "TAG")),
            Map.entry("top.sywyar.pixivdownload.core.gallery.frontend.GalleryFrontendHook",
                    List.of("VIEW_ENTRY", "FILTER_EXTENSION", "CARD_EXTENSION", "MEDIA_RENDERER", "DETAIL_ACTION")),
            Map.entry("top.sywyar.pixivdownload.core.gallery.model.GalleryFieldCapability",
                    List.of("SUPPORTED", "CONSTANT", "UNKNOWN", "UNSUPPORTED")),
            Map.entry("top.sywyar.pixivdownload.core.gallery.model.GalleryKind",
                    List.of("IMAGE", "NOVEL", "VIDEO")),
            Map.entry("top.sywyar.pixivdownload.core.gallery.model.media.GalleryMediaKind",
                    List.of("IMAGE", "VIDEO", "LIVE_PHOTO_VIDEO", "UGOIRA", "COVER", "TEXT", "UNKNOWN")),
            Map.entry("top.sywyar.pixivdownload.core.gallery.model.projection.GalleryDataAccess",
                    List.of("SHARED", "ADMIN_ONLY")),
            Map.entry("top.sywyar.pixivdownload.core.gallery.model.work.GalleryAiStatus",
                    List.of("AI", "NON_AI", "UNKNOWN")),
            Map.entry("top.sywyar.pixivdownload.core.gallery.model.work.GalleryContentRating",
                    List.of("SFW", "R18", "R18G", "UNKNOWN")),
            Map.entry("top.sywyar.pixivdownload.core.gallery.query.GalleryFilterField",
                    List.of("AUTHOR", "TAG", "AI_STATUS", "CONTENT_RATING", "SOURCE", "CONTAINED_MEDIA_KIND")),
            Map.entry("top.sywyar.pixivdownload.core.gallery.query.GalleryFilterMode",
                    List.of("ANY_OF", "ALL_OF", "NONE_OF")),
            Map.entry("top.sywyar.pixivdownload.core.gallery.query.GallerySortDirection",
                    List.of("ASC", "DESC")),
            Map.entry("top.sywyar.pixivdownload.core.gallery.query.GallerySortField",
                    List.of("CREATED_AT", "DOWNLOADED_AT", "UPDATED_AT", "TITLE")),
            Map.entry("top.sywyar.pixivdownload.core.pixiv.PixivProxyAccessOutcome",
                    List.of("ALLOWED", "OWNER_REQUIRED", "RATE_LIMITED")),
            Map.entry("top.sywyar.pixivdownload.core.pixiv.PixivAjaxFailure",
                    List.of("INVALID_TARGET", "HTTP_STATUS", "RESPONSE_TOO_LARGE", "TRANSPORT")),
            Map.entry("top.sywyar.pixivdownload.core.schedule.state.ScheduleLastOutcome",
                    List.of("NEVER", "OK", "ERROR", "CANCELLED", "INTERRUPTED")),
            Map.entry("top.sywyar.pixivdownload.core.schedule.state.ScheduleRunState",
                    List.of("QUEUED", "RUNNING", "CANCEL_REQUESTED")),
            Map.entry("top.sywyar.pixivdownload.core.schedule.state.ScheduleSuspendReason",
                    List.of("MANUAL", "CREDENTIAL", "POLICY", "SOURCE_UNAVAILABLE", "EXECUTOR_UNAVAILABLE",
                            "QUIESCED", "MIGRATION_ERROR")),
            Map.entry("top.sywyar.pixivdownload.core.work.model.WorkType",
                    List.of("ARTWORK", "NOVEL")),
            Map.entry("top.sywyar.pixivdownload.core.work.service.WorkDeletionException$Reason",
                    List.of("LOCAL_FILE_DELETE_FAILED")),
            Map.entry("top.sywyar.pixivdownload.notification.NotificationScenario",
                    List.of("POLICY_ACCOUNT_SUSPENDED", "CREDENTIAL_SUSPENDED",
                            "CREDENTIAL_FAILURE_CIRCUIT_OPEN", "PENDING_EXHAUSTED",
                            "CREDENTIAL_REVOKED_CONTINUING", "RUN_FAILED", "RUN_SUMMARY",
                            "MAINTENANCE_TASK_FAILED")),
            Map.entry("top.sywyar.pixivdownload.notification.NotificationSeverity",
                    List.of("INFO", "WARNING", "ERROR")),
            Map.entry("top.sywyar.pixivdownload.push.PushFormat",
                    List.of("PLAIN_TEXT", "MARKDOWN", "HTML", "CARD")),
            Map.entry("top.sywyar.pixivdownload.push.PushResult$Status",
                    List.of("OK", "FAILED", "SKIPPED")),
            Map.entry("top.sywyar.pixivdownload.tts.narration.engine.NarrationVoiceMode",
                    List.of("VOICE_DESIGN", "CLONE", "HIFI_CLONE"))
    );

    private static final Pattern JAVADOC_BLOCK = Pattern.compile("/\\*\\*(.*?)\\*/", Pattern.DOTALL);
    private static final Pattern PACKAGE_DECLARATION = Pattern.compile("(?m)^package\\s+([\\w.]+);");
    private static final Pattern IMPORT_DECLARATION = Pattern.compile("(?m)^import\\s+([\\w.$]+);");
    private static final Pattern JAVADOC_TYPE_REFERENCE = Pattern.compile(
            "\\{@(?:link|linkplain|value)\\s+([^\\s}]+)");
    private static final Pattern JAVADOC_SEE = Pattern.compile("@see\\s+([^\\s*]+)");
    private static final Pattern JAVADOC_CODE_TYPE_REFERENCE = Pattern.compile(
            "\\{@code\\s+((?:[a-z_$][\\w$]*\\.)*[A-Z][\\w$]*(?:\\.[A-Z][\\w$]*)*)\\b");
    private static final Pattern PROJECT_REFERENCE = Pattern.compile(
            "\\btop\\.sywyar\\.pixivdownload(?:\\.[A-Za-z_$][\\w$]*)+\\b");
    private static final Pattern RELATIVE_IMPLEMENTATION_PACKAGE_REFERENCE = Pattern.compile(
            "\\{@code\\s+((?:novel|plugin|core\\.narration|ai\\.narration|tts\\.narration)"
                    + "(?:\\.[A-Za-z0-9_$]+)*)");
    private static final Pattern FORBIDDEN_IMPLEMENTATION_TERM = Pattern.compile(
            "\\b(?:VoxCPM|MiMo|CosyVoice|Fish(?: Audio| TTS)|MiniMax|ElevenLabs|Qwen|Doubao|"
                    + "NarrationSentenceSplitter|pushbot|diy_send)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CONCRETE_PLUGIN_PROSE = Pattern.compile(
            "\\b(?:optional\\s+)?(?:AI|TTS|novel|stats|gallery|duplicate|push|mail|notification|douyin|"
                    + "download[- ]workbench|plugin[- ]market|recovery[- ]sentinel|gui[- ]theme)\\s+plugin\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CONCRETE_PUSH_CHANNEL_PROSE = Pattern.compile(
            "\\b(?:Bark|DingTalk|Telegram|Feishu|WeCom|PushPlus|ServerChan)\\b"
                    + "|钉钉|飞书|企业微信|Server\\s*酱",
            Pattern.CASE_INSENSITIVE);
    private static final List<Pattern> PLUGIN_PRIVATE_LAYOUT_PATTERNS = List.of(
            Pattern.compile(
                    "novel-(?:\\{(?:id|workId|novelId)\\}|<(?:id|workId|novelId)>|\\d+)",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("_thumb\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bdownload\\.root-folder\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("小说独占目录(?:守卫)?|独占目录守卫"));
    private static final Set<String> CONCRETE_PLUGIN_IDS = Set.of(
            "download-workbench", "plugin-market", "recovery-sentinel", "novel", "notification", "tts", "ai",
            "push", "mail", "gallery", "duplicate", "stats", "douyin", "gui-theme");
    private static final Set<String> CONCRETE_ENGINE_IDS = Set.of(
            "voxcpm", "mimo", "cosyvoice", "fish", "minimax", "elevenlabs", "qwen", "doubao");
    private static final Map<String, Set<String>> APPROVED_OWNER_LITERALS_BY_TYPE = Map.ofEntries(
            Map.entry("top.sywyar.pixivdownload.core.gallery.model.work.GalleryWork",
                    Set.of("media work key must match gallery work key")),
            Map.entry("top.sywyar.pixivdownload.core.pixiv.PixivCoverUrlResolver",
                    Set.of("^/c/[^/]+/(novel-cover-(?:master|original)/.+)$")),
            Map.entry("top.sywyar.pixivdownload.core.pixiv.filename.PixivWorkFileNameFormatter",
                    Set.of(
                            "\\{(artwork_id|artwork_title|author_id|author_name|timestamp|page|count|ai\\+?|R18\\+?)}",
                            "ai",
                            "ai+"))
    );

    @Test
    @DisplayName("每个生产类型都必须出现在显式 owner 白名单中")
    void everyProductionTypeHasAnExplicitCoreOwner() {
        Set<String> approved = approvedTypes();
        Set<String> actualTopLevelTypes = new LinkedHashSet<>();
        CLASSES.stream()
                .map(javaClass -> javaClass.getName())
                .filter(name -> !name.contains("$"))
                .sorted()
                .forEach(actualTopLevelTypes::add);

        assertThat(actualTopLevelTypes)
                .as("新增 core-api 类型必须先确认长期核心 owner；插件私有行模型、配置和工具不得仅因纯 JDK 而进入")
                .containsExactlyInAnyOrderElementsOf(approved);
        assertThat(actualTopLevelTypes)
                .noneMatch(name -> name.startsWith("top.sywyar.pixivdownload.core.metadata.novel.")
                        || name.startsWith("top.sywyar.pixivdownload.util."));

        Set<String> actualPublicNestedTypes = new LinkedHashSet<>();
        CLASSES.stream()
                .filter(javaClass -> javaClass.getName().contains("$"))
                .filter(javaClass -> javaClass.getModifiers().contains(JavaModifier.PUBLIC))
                .map(javaClass -> javaClass.getName())
                .sorted()
                .forEach(actualPublicNestedTypes::add);

        assertThat(actualPublicNestedTypes)
                .as("公开嵌套类型同样属于 core-api 契约面，必须逐个确认长期核心 owner")
                .containsExactlyInAnyOrderElementsOf(APPROVED_PUBLIC_NESTED_TYPES);
    }

    @Test
    @DisplayName("owner 白名单中的顶层契约必须可加载且保持 public")
    void approvedTopLevelContractsAreLoadableAndPublic() {
        assertThat(topLevelContractVisibilityViolations(approvedTypes()))
                .as("白名单不能掩盖缺失或非 public 契约；反射加载失败必须 fail-closed")
                .isEmpty();
    }

    @Test
    @DisplayName("顶层契约反射守卫对缺类与非 public 类型 fail-closed")
    void topLevelContractReflectionGuardHasFailClosedFixtures() {
        String missingType = "top.sywyar.pixivdownload.missing.MissingCoreContract";

        assertThat(topLevelContractVisibilityViolations(Set.of(
                missingType,
                CoreApiOwnershipGuardTest.class.getName())))
                .anyMatch(violation -> violation.contains(missingType)
                        && violation.contains("cannot be loaded"))
                .anyMatch(violation -> violation.contains(CoreApiOwnershipGuardTest.class.getName())
                        && violation.contains("is not public"));
    }

    @Test
    @DisplayName("core.work 不得承载来源专属插画下载事实")
    void coreWorkDoesNotOwnArtworkSpecificDownloadFacts() {
        assertThat(CLASSES.stream()
                .filter(javaClass -> javaClass.getPackageName()
                        .startsWith("top.sywyar.pixivdownload.core.work"))
                .map(javaClass -> javaClass.getSimpleName())
                .filter(simpleName -> simpleName.startsWith("Artwork"))
                .toList())
                .isEmpty();
    }

    @Test
    @DisplayName("公开常量的名称、类型和值必须保持精确契约")
    void publicConstantsHaveExactNamesTypesAndValues() throws ReflectiveOperationException {
        Map<String, Object> actual = new LinkedHashMap<>();
        Set<String> publicTypes = union(approvedTypes(), APPROVED_PUBLIC_NESTED_TYPES);
        for (String typeName : publicTypes.stream().sorted().toList()) {
            Class<?> type = Class.forName(typeName);
            Arrays.stream(type.getDeclaredFields())
                    .filter(field -> Modifier.isPublic(field.getModifiers()))
                    .filter(field -> Modifier.isStatic(field.getModifiers()))
                    .filter(field -> Modifier.isFinal(field.getModifiers()))
                    .filter(field -> !field.isSynthetic() && !field.isEnumConstant())
                    .forEach(field -> {
                        try {
                            actual.put(typeName + "#" + field.getName() + ":" + field.getType().getName(),
                                    field.get(null));
                        } catch (IllegalAccessException exception) {
                            throw new IllegalStateException("cannot read public core-api constant " + field, exception);
                        }
                    });
        }

        assertThat(actual)
                .as("公开 static final 字段属于稳定契约；新增、删除、改名或改值必须显式更新 owner 白名单")
                .containsExactlyInAnyOrderEntriesOf(APPROVED_PUBLIC_CONSTANTS);
    }

    @Test
    @DisplayName("公开枚举常量必须保持精确契约")
    void publicEnumConstantsHaveExactNamesAndOrder() throws ReflectiveOperationException {
        Map<String, List<String>> actual = new LinkedHashMap<>();
        Set<String> publicTypes = union(approvedTypes(), APPROVED_PUBLIC_NESTED_TYPES);
        for (String typeName : publicTypes.stream().sorted().toList()) {
            Class<?> type = Class.forName(typeName);
            if (type.isEnum()) {
                actual.put(typeName, Arrays.stream(type.getEnumConstants())
                        .map(constant -> ((Enum<?>) constant).name())
                        .toList());
            }
        }

        assertThat(actual)
                .as("公开 enum 值及顺序属于稳定契约，必须逐项确认 owner 后才能改动")
                .containsExactlyInAnyOrderEntriesOf(APPROVED_ENUM_CONSTANTS_BY_TYPE);
    }

    @Test
    @DisplayName("敏感 record 与工厂方法必须保持精确契约面")
    void sensitiveContractsHaveExactShapes() {
        assertRecordShape(GalleryMediaAsset.class,
                List.of("key", "kind", "url", "thumbnailUrl", "mimeType", "attributes"),
                List.of(GalleryMediaKey.class, GalleryMediaKind.class, String.class,
                        String.class, String.class, Map.class));
        assertRecordShape(PushChannelId.class,
                List.of("id"),
                List.of(String.class));
        assertRecordShape(AiClientSettings.class,
                List.of("baseUrl", "apiKey", "model", "useProxy"),
                List.of(String.class, String.class, String.class, boolean.class));
        assertRecordShape(NarrationAudio.class,
                List.of("data", "contentType"),
                List.of(byte[].class, String.class));
        assertRecordShape(NarrationReferenceVoice.class,
                List.of("audio", "mime", "text"),
                List.of(byte[].class, String.class, String.class));
        assertRecordShape(NarrationVoiceSelection.class,
                List.of("id", "engine"),
                List.of(String.class, NarrationVoiceEngine.class));
        assertRecordShape(NarrationVoiceRequest.class,
                List.of("text", "controlInstruction", "delivery", "referenceVoice"),
                List.of(String.class, String.class, String.class, NarrationReferenceVoice.class));
        assertRecordShape(ResolvedFfmpegCommand.class,
                List.of("command", "source"),
                List.of(String.class, ResolvedFfmpegCommand.Source.class));
        assertRecordShape(ScheduledTask.class,
                List.of("id", "name", "enabled", "sourceType", "sourceOwnerPluginId", "definitionSchema",
                        "definitionVersion", "definitionJson", "presentationJson", "triggerKind", "intervalMinutes",
                        "cronExpr", "proxySnapshot", "nextRunTime", "lastRunTime", "checkpointSchema",
                        "checkpointVersion", "checkpointJson", "storageVersion", "runState", "runClaimToken",
                        "lastOutcome", "outcomeCode", "outcomeMessage", "suspendReason", "suspendCode",
                        "suspendDetailJson", "stateVersion", "credentialPolicyOwnerPluginId", "credentialPolicyId",
                        "credentialAccountKey", "credentialPolicyStateJson", "credentialSecretReference",
                        "createdTime"),
                List.of(Long.class, String.class, boolean.class, String.class, String.class, String.class,
                        Integer.class, String.class, String.class, String.class, Integer.class, String.class,
                        String.class, Long.class, Long.class, String.class, Integer.class, String.class, int.class,
                        ScheduleRunState.class, String.class, ScheduleLastOutcome.class, String.class, String.class,
                        ScheduleSuspendReason.class, String.class, String.class, long.class, String.class,
                        String.class, String.class, String.class, String.class, long.class));
        assertRecordShape(ScheduledTaskCreate.class,
                List.of("name", "sourceType", "sourceOwnerPluginId", "definitionSchema", "definitionVersion",
                        "definitionJson", "presentationJson", "triggerKind", "intervalMinutes", "cronExpr",
                        "nextRunTime", "createdTime"),
                List.of(String.class, String.class, String.class, String.class, int.class, String.class,
                        String.class, String.class, Integer.class, String.class, Long.class, long.class));
        assertRecordShape(ScheduledPendingWork.class,
                List.of("taskId", "workType", "workId", "payloadSchema", "payloadVersion", "payloadJson",
                        "relationsJson", "presentationJson", "reasonCode", "reasonDetailJson", "attempts",
                        "firstSeenTime", "lastAttemptTime"),
                List.of(long.class, String.class, String.class, String.class, int.class, String.class, String.class,
                        String.class, String.class, String.class, int.class, Long.class, Long.class));
        assertRecordShape(ScheduleTaskDefinitionUpdate.class,
                List.of("name", "sourceType", "sourceOwnerPluginId", "definitionSchema", "definitionVersion",
                        "definitionJson", "presentationJson", "triggerKind", "intervalMinutes", "cronExpr",
                        "nextRunTime"),
                List.of(String.class, String.class, String.class, String.class, int.class, String.class,
                        String.class, String.class, Integer.class, String.class, Long.class));
        assertRecordShape(ScheduleRunCompletion.class,
                List.of("finishedTime", "outcome", "outcomeCode", "outcomeMessage", "nextRunTime",
                        "checkpointSchema", "checkpointVersion", "checkpointJson"),
                List.of(long.class, ScheduleLastOutcome.class, String.class, String.class, Long.class,
                        String.class, Integer.class, String.class));
        assertRecordShape(ScheduleRunToken.class,
                List.of("claimToken", "stateVersion", "runState"),
                List.of(String.class, long.class, ScheduleRunState.class));
        assertRecordShape(VisitorDownloadQuotaReservation.class,
                List.of("allowed", "quotaUnitsUsed", "maxQuotaUnits", "resetSeconds"),
                List.of(boolean.class, int.class, int.class, long.class));
        assertRecordShape(PixivProxyAccessDecision.class,
                List.of("outcome", "errorMessage", "maxRequests", "windowHours"),
                List.of(PixivProxyAccessOutcome.class, String.class, int.class, int.class));
        assertRecordShape(ArtworkDownloadCompletion.class,
                List.of("artworkId", "title", "folder", "imageCount", "extensions", "recordTime",
                        "restriction", "aiGenerated", "authorId", "description", "fileNameTemplate",
                        "normalizedAuthorName", "seriesId", "seriesOrder", "tags"),
                List.of(long.class, String.class, Path.class, int.class, Set.class, long.class,
                        int.class, boolean.class, Long.class, String.class, String.class,
                        String.class, Long.class, Long.class, List.class));
        assertRecordShape(ArtworkSeriesObservation.class,
                List.of("artworkId", "lookupWhenMissing", "seriesId", "title", "authorId",
                        "description", "coverUrl"),
                List.of(long.class, boolean.class, Long.class, String.class, Long.class,
                        String.class, String.class));

        assertThat(publicDeclaredMethodSignatures(AiClientSettings.class))
                .containsExactlyInAnyOrder(
                        "public apiKey():java.lang.String",
                        "public baseUrl():java.lang.String",
                        "public final equals(java.lang.Object):boolean",
                        "public final hashCode():int",
                        "public model():java.lang.String",
                        "public toString():java.lang.String",
                        "public useProxy():boolean");
        assertThat(publicDeclaredMethodSignatures(PushChannelId.class))
                .containsExactlyInAnyOrder(
                        "public final equals(java.lang.Object):boolean",
                        "public final hashCode():int",
                        "public id():java.lang.String",
                        "public final toString():java.lang.String");
        assertThat(publicDeclaredMethodSignatures(NarrationAudio.class))
                .containsExactlyInAnyOrder(
                        "public contentType():java.lang.String",
                        "public data():[B",
                        "public equals(java.lang.Object):boolean",
                        "public hashCode():int",
                        "public final toString():java.lang.String");
        assertThat(publicDeclaredMethodSignatures(NarrationReferenceVoice.class))
                .containsExactlyInAnyOrder(
                        "public audio():[B",
                        "public equals(java.lang.Object):boolean",
                        "public hasAudio():boolean",
                        "public hashCode():int",
                        "public mime():java.lang.String",
                        "public text():java.lang.String",
                        "public final toString():java.lang.String");
        assertThat(publicDeclaredMethodSignatures(NarrationVoiceSelection.class))
                .containsExactlyInAnyOrder(
                        "public engine():top.sywyar.pixivdownload.tts.narration.engine.NarrationVoiceEngine",
                        "public final equals(java.lang.Object):boolean",
                        "public final hashCode():int",
                        "public id():java.lang.String",
                        "public final toString():java.lang.String");
        assertThat(publicDeclaredMethodSignatures(NarrationVoiceSelector.class))
                .containsExactlyInAnyOrder(
                        "public abstract availableEngineCount():int",
                        "public abstract configuredEngineId():java.lang.String",
                        "public abstract selected():java.util.Optional");

        assertThat(publicDeclaredMethodSignatures(NarrationVoiceRequest.class))
                .containsExactlyInAnyOrder(
                        "public controlInstruction():java.lang.String",
                        "public delivery():java.lang.String",
                        "public final equals(java.lang.Object):boolean",
                        "public hasReferenceVoice():boolean",
                        "public final hashCode():int",
                        "public static of(java.lang.String,java.lang.String):top.sywyar.pixivdownload.tts.narration.engine.NarrationVoiceRequest",
                        "public referenceVoice():top.sywyar.pixivdownload.tts.narration.engine.NarrationReferenceVoice",
                        "public text():java.lang.String",
                        "public final toString():java.lang.String",
                        "public withText(java.lang.String):top.sywyar.pixivdownload.tts.narration.engine.NarrationVoiceRequest");

        assertThat(publicDeclaredMethodSignatures(AuthorObservationService.class))
                .containsExactly("public abstract observe(long,java.lang.String):void");
        assertThat(publicDeclaredMethodSignatures(InteractiveDownloadExecutionLane.class))
                .containsExactly("public abstract execute(java.lang.Runnable):void");
        assertThat(publicDeclaredMethodSignatures(ArtworkAuthorLookup.class))
                .containsExactly("public abstract resolveMissing(long,java.lang.String):void");
        assertThat(publicDeclaredMethodSignatures(ArtworkDownloadHistory.class))
                .containsExactlyInAnyOrder(
                        "public abstract allocateRecordTime(long):long",
                        "public abstract record(top.sywyar.pixivdownload.core.artwork.download.ArtworkDownloadCompletion):void");
        assertThat(publicDeclaredMethodSignatures(ArtworkDownloadLookup.class))
                .containsExactly("public abstract isDownloaded(long,boolean):boolean");
        assertThat(publicDeclaredMethodSignatures(ArtworkDownloadStatistics.class))
                .containsExactly("public abstract recordCompleted(int):void");
        assertThat(publicDeclaredMethodSignatures(ArtworkSeriesObserver.class))
                .containsExactly("public abstract observe(top.sywyar.pixivdownload.core.artwork.download.ArtworkSeriesObservation,java.lang.String):void");
        assertThat(publicDeclaredMethodSignatures(DownloadPathGuard.class))
                .containsExactlyInAnyOrder(
                        "public abstract requireSafeDirectoryName(java.lang.String):java.lang.String",
                        "public abstract requireWithinRoot(java.nio.file.Path,java.nio.file.Path):void");
        assertThat(publicDeclaredMethodSignatures(DownloadPathRejectedException.class)).isEmpty();
        assertThat(publicDeclaredMethodSignatures(WorkFileNameCatalog.class))
                .containsExactlyInAnyOrder(
                        "public abstract getOrCreateAuthorNameId(java.lang.String):long",
                        "public abstract getOrCreateTemplateId(java.lang.String):long");
        assertThat(publicDeclaredMethodSignatures(WorkTagCatalog.class))
                .containsExactly("public abstract getOrCreateTagId(java.lang.String,java.lang.String):java.lang.Long");
        assertThat(publicDeclaredMethodSignatures(WorkCollectionMembership.class))
                .containsExactly("public abstract addWork(top.sywyar.pixivdownload.core.work.model.WorkType,long,long):boolean");
        assertThat(publicDeclaredMethodSignatures(CollectionDownloadRootResolver.class))
                .containsExactly("public abstract resolveDownloadRoot(long,java.nio.file.Path):java.nio.file.Path");
        assertThat(publicDeclaredMethodSignatures(VisitorDownloadQuotaService.class))
                .containsExactlyInAnyOrder(
                        "public abstract checkAndReserve(java.lang.String,int):top.sywyar.pixivdownload.core.quota.VisitorDownloadQuotaReservation",
                        "public abstract createArchive(java.lang.String):java.lang.String",
                        "public abstract recordFolder(java.lang.String,java.nio.file.Path):void");
        assertThat(publicDeclaredMethodSignatures(PixivAjaxClient.class))
                .containsExactly("public abstract get(java.net.URI,java.lang.String):java.lang.String");
        assertThat(publicDeclaredMethodSignatures(PixivAjaxException.class))
                .containsExactlyInAnyOrder(
                        "public failure():top.sywyar.pixivdownload.core.pixiv.PixivAjaxFailure",
                        "public statusCode():int");
        assertThat(publicDeclaredMethodSignatures(PixivBookmarkActions.class))
                .containsExactlyInAnyOrder(
                        "public abstract bookmarkArtwork(java.lang.Long,java.lang.String):top.sywyar.pixivdownload.core.work.WorkActionResult",
                        "public abstract bookmarkNovel(java.lang.Long,java.lang.String):top.sywyar.pixivdownload.core.work.WorkActionResult");
        assertThat(publicDeclaredMethodSignatures(PixivImageDownloader.class))
                .containsExactlyInAnyOrder(
                        "public abstract download(java.net.URI,java.net.URI,java.nio.file.Path,java.lang.String,top.sywyar.pixivdownload.core.pixiv.PixivImageTransferObserver):boolean",
                        "public downloadImage(java.net.URI,java.net.URI,java.nio.file.Path,java.lang.String,top.sywyar.pixivdownload.core.pixiv.PixivImageTransferObserver):java.lang.String");
        assertThat(publicDeclaredMethodSignatures(PixivThumbnailFetcher.class))
                .containsExactly("public abstract fetch(java.net.URI):[B");
        assertThat(publicDeclaredMethodSignatures(PixivThumbnailFetchException.class))
                .containsExactlyInAnyOrder(
                        "public failure():top.sywyar.pixivdownload.core.pixiv.thumbnail.PixivThumbnailFailure",
                        "public statusCode():int");
        assertThat(publicDeclaredMethodSignatures(FfmpegCommandResolver.class))
                .containsExactly("public abstract resolve():top.sywyar.pixivdownload.core.ffmpeg.ResolvedFfmpegCommand");
        assertThat(publicDeclaredMethodSignatures(ResolvedFfmpegCommand.class))
                .containsExactlyInAnyOrder(
                        "public command():java.lang.String",
                        "public final equals(java.lang.Object):boolean",
                        "public final hashCode():int",
                        "public source():top.sywyar.pixivdownload.core.ffmpeg.ResolvedFfmpegCommand$Source",
                        "public final toString():java.lang.String");
        assertThat(publicDeclaredMethodSignatures(PixivImageTransferObserver.class))
                .containsExactlyInAnyOrder(
                        "public checkCancelled():void",
                        "public maximumBytes():long",
                        "public onBytesTransferred(long):void",
                        "public onContentType(java.lang.String):void",
                        "public onContentLength(long):void");
        assertThat(publicDeclaredMethodSignatures(PixivProxyAccessPolicy.class))
                .containsExactlyInAnyOrder(
                        "public abstract evaluate(java.lang.String,boolean):top.sywyar.pixivdownload.core.pixiv.PixivProxyAccessDecision",
                        "public abstract resolveSearchFillLimitPage(boolean):int");
        assertThat(publicDeclaredMethodSignatures(WorkMetadataCapture.class))
                .containsExactlyInAnyOrder(
                        "public abstract capture(top.sywyar.pixivdownload.core.work.model.WorkType,long,java.lang.String,java.lang.String,java.lang.String):void",
                        "public capture(top.sywyar.pixivdownload.core.work.model.WorkType,long,java.lang.String,java.lang.String):void",
                        "public captureForwarded(top.sywyar.pixivdownload.core.work.model.WorkType,long,java.lang.String):void");
        assertThat(publicDeclaredMethodSignatures(MessageResolver.class))
                .containsExactlyInAnyOrder(
                        "public currentLocale():java.util.Locale",
                        "public abstract transient get(java.lang.String,[Ljava.lang.Object;):java.lang.String",
                        "public abstract transient get(java.util.Locale,java.lang.String,[Ljava.lang.Object;):java.lang.String",
                        "public abstract transient getForLog(java.lang.String,[Ljava.lang.Object;):java.lang.String",
                        "public abstract transient getOrDefault(java.lang.String,java.lang.String,[Ljava.lang.Object;):java.lang.String",
                        "public abstract transient getOrDefault(java.util.Locale,java.lang.String,java.lang.String,[Ljava.lang.Object;):java.lang.String",
                        "public normalizeLocale(java.util.Locale):java.util.Locale");
        assertThat(publicDeclaredMethodSignatures(NamespaceMessageResolver.class))
                .containsExactly(
                        "public abstract resolve(java.lang.String,java.util.Locale,java.lang.String):java.util.Optional");
        assertThat(publicDeclaredMethodSignatures(NotificationDispatcher.class))
                .containsExactly(
                        "public abstract notify(top.sywyar.pixivdownload.notification.NotificationScenario,java.util.Locale,java.util.Map):void");
    }

    @Test
    @DisplayName("计划任务 Store 创建契约必须接收不可变命令并返回生成 id")
    void scheduledTaskStoreCreateHasExactCommandAndGeneratedIdContract() throws NoSuchMethodException {
        Method create = ScheduledTaskStore.class.getDeclaredMethod("create", ScheduledTaskCreate.class);

        assertThat(create.getReturnType()).isEqualTo(long.class);
        assertThat(create.getReturnType().isPrimitive()).isTrue();
        assertThat(Modifier.isPublic(create.getModifiers())).isTrue();
        assertThat(Modifier.isAbstract(create.getModifiers())).isTrue();
        assertThat(Arrays.stream(ScheduledTaskStore.class.getDeclaredMethods())
                .map(Method::getName))
                .doesNotContain(
                        "insert",
                        "recoverInterruptedRuns",
                        "findCredentialMetadata",
                        "findPendingWork",
                        "incrementPendingAttempts",
                        "deleteAllPendingWork",
                        "suspendByCredentialAccount",
                        "resumeByCredentialAccount");
    }

    @Test
    @DisplayName("运行期路径端口只暴露 owner-scoped 根路径语义")
    void runtimePathProviderHasExactOwnerScopedSurface() throws NoSuchMethodException {
        assertThat(RuntimePathProvider.class.getDeclaredMethods())
                .hasSize(3)
                .allSatisfy(method -> assertThat(method.getReturnType()).isEqualTo(Path.class))
                .extracting(method -> method.getName())
                .containsExactlyInAnyOrder(
                        "resolvePluginConfigPath",
                        "resolvePluginStateDirectory",
                        "resolvePluginDataDirectory");

        assertThat(RuntimePathProvider.class
                .getDeclaredMethod("resolvePluginConfigPath", String.class, String.class)
                .getParameterTypes()).containsExactly(String.class, String.class);
        assertThat(RuntimePathProvider.class
                .getDeclaredMethod("resolvePluginStateDirectory", String.class)
                .getParameterTypes()).containsExactly(String.class);
        assertThat(RuntimePathProvider.class
                .getDeclaredMethod("resolvePluginDataDirectory", String.class)
                .getParameterTypes()).containsExactly(String.class);

        assertThat(publicDeclaredMethodSignatures(RuntimePathProvider.class))
                .containsExactlyInAnyOrder(
                        "public abstract resolvePluginConfigPath(java.lang.String,java.lang.String):java.nio.file.Path",
                        "public abstract resolvePluginDataDirectory(java.lang.String):java.nio.file.Path",
                        "public abstract resolvePluginStateDirectory(java.lang.String):java.nio.file.Path");
    }

    @Test
    @DisplayName("生产 Javadoc 不得反向链接 app 或插件实现类型")
    void productionJavadocsReferenceOnlyCoreOwnedOrJdkTypes() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path source : productionSources()) {
            String content = Files.readString(source, StandardCharsets.UTF_8);
            violations.addAll(documentationViolations(relativeSource(source), content));
        }

        assertThat(violations)
                .as("core-api 文档也属于契约边界，只能精确引用已批准核心类型、公开嵌套类型与真实 JDK 类型")
                .isEmpty();
    }

    @Test
    @DisplayName("core-api 生产源码不得暴露小说插件私有详情字段")
    void productionSourcesDoNotExposeNovelPrivateDetailFields() throws IOException {
        List<String> violations = new ArrayList<>();
        List<String> privateTerms = List.of(
                "wordCount", "word_count", "textLength", "text_length",
                "readingTimeSeconds", "reading_time_seconds", "xLanguage", "x_language",
                "rawContent", "raw_content", "novels_fts", "uploadTimestamp",
                "NovelRecord", "NovelSeries", "NovelTagRow");
        for (Path source : productionSources()) {
            String content = Files.readString(source, StandardCharsets.UTF_8);
            for (String term : privateTerms) {
                if (content.contains(term)) {
                    violations.add(relativeSource(source) + " contains " + term);
                }
            }
        }

        assertThat(violations)
                .as("小说完整持久化行、正文、字数与 FTS 生命周期必须留在小说插件")
                .isEmpty();
    }

    @Test
    @DisplayName("生产源码的任意字符串字面量不得声明具体插件或引擎 owner")
    void productionStringLiteralsDoNotDeclareConcreteOwners() throws IOException {
        List<JavaSourceInput> sources = new ArrayList<>();
        for (Path source : productionSources()) {
            sources.add(new JavaSourceInput(
                    relativeSource(source),
                    Files.readString(source, StandardCharsets.UTF_8)));
        }

        assertThat(concreteOwnerLiteralViolations(sources))
                .as("具体插件 id 与引擎 id 必须留在 owner 模块；所有源码必须由单次 javac AST 批量解析")
                .isEmpty();
    }

    @Test
    @DisplayName("文档解析器精确放行核心契约并拒绝实现泄漏")
    void documentationMatcherHasPositiveAndNegativeFixtures() {
        String allowed = """
                package top.sywyar.pixivdownload.core.archive;
                import top.sywyar.pixivdownload.core.stats.StatsAggregates;
                import top.sywyar.pixivdownload.tts.narration.engine.NarrationVoiceEngine;
                /**
                 * {@code ArchiveExportService} / {@code NarrationVoiceEngine}
                 * {@code top.sywyar.pixivdownload.tts.narration.engine}
                 * {@link StatsAggregates.Overview} / {@link java.lang.Long}
                 * 中性介质示例 {@code "mail"} / {@code "push"}。
                 */
                final class AllowedFixture {}
                """;
        assertThat(documentationViolations("AllowedFixture.java", allowed)).isEmpty();

        String rejected = """
                package top.sywyar.pixivdownload.core.stats;
                import top.sywyar.pixivdownload.core.stats.StatsAggregates;
                /**
                 * {@code MiMo} / {@code CosyVoice} / {@code Qwen} / {@code Doubao}
                 * {@code MiMoNarrationEngine}
                 * {@link top.sywyar.pixivdownload.tts.narration.engine.MiMoNarrationEngine}
                 * {@link StatsAggregates.InternalRow}
                 * Capability contributed by the optional AI plugin.
                 * Capability contributed by the optional Douyin plugin.
                 * Capability contributed by the GUI theme plugin.
                 * Capability contributed by the recovery-sentinel plugin.
                 * Capability contributed by the plugin-market plugin.
                 * Layout examples: novel-{workId} / asset_thumb.jpg below download.root-folder.
                 * 小说独占目录守卫属于具体 owner。
                 */
                interface RejectedFixture {
                    String ID = "plugin-market";
                    final static String FALLBACK_ID = "recovery-sentinel";
                    String NOVEL_ID = "novel";
                    String ENGINE_ID = "mimo";
                    String novelKey = "plugins.novel.enabled";
                    String splitOwner = "dou" + "yin";
                }
                """;
        List<String> documentation = documentationViolations("RejectedFixture.java", rejected);
        assertThat(documentation)
                .anyMatch(violation -> violation.contains("MiMo"))
                .anyMatch(violation -> violation.contains("CosyVoice"))
                .anyMatch(violation -> violation.contains("MiMoNarrationEngine"))
                .anyMatch(violation -> violation.contains("StatsAggregates.InternalRow"))
                .anyMatch(violation -> violation.contains("optional AI plugin"))
                .anyMatch(violation -> violation.contains("optional Douyin plugin"))
                .anyMatch(violation -> violation.contains("GUI theme plugin"))
                .anyMatch(violation -> violation.contains("recovery-sentinel plugin"))
                .anyMatch(violation -> violation.contains("plugin-market plugin"))
                .anyMatch(violation -> violation.contains("novel-{workId}"))
                .anyMatch(violation -> violation.contains("_thumb"))
                .anyMatch(violation -> violation.contains("download.root-folder"))
                .anyMatch(violation -> violation.contains("小说独占目录守卫"));

        List<String> literals = concreteOwnerLiteralViolations(List.of(
                new JavaSourceInput("RejectedFixture.java", rejected)));
        assertThat(literals)
                .anyMatch(violation -> violation.contains("plugin-market"))
                .anyMatch(violation -> violation.contains("recovery-sentinel"))
                .anyMatch(violation -> violation.contains("novel"))
                .anyMatch(violation -> violation.contains("mimo"))
                .anyMatch(violation -> violation.contains("plugins.novel.enabled"))
                .anyMatch(violation -> violation.contains("douyin"));

        String rejectedApprovedConstantMutation = """
                package top.sywyar.pixivdownload.notification;
                final class NotificationConfigKeys {
                    public static final String SCENARIO_PREFIX = "notification.scenario." + "novel";
                }
                """;
        assertThat(concreteOwnerLiteralViolations(List.of(new JavaSourceInput(
                "top/sywyar/pixivdownload/notification/NotificationConfigKeys.java",
                rejectedApprovedConstantMutation))))
                .anyMatch(violation -> violation.contains("novel"));
    }

    @Test
    @DisplayName("AST owner 守卫覆盖所有字符串上下文与纯字面量折叠")
    void ownerLiteralAstGuardCoversAllStringContextsAndLiteralFolding() {
        String rejected = """
                package fixture;
                import java.util.List;
                import java.util.regex.Pattern;

                @interface OwnerMarker {
                    String value();
                }

                @OwnerMarker("recovery-sentinel")
                final class RejectedLiteralContexts {
                    static final String FOLDED = "plugin-" + "market";
                    static final Object OBJECT_VALUE = "push";
                    static final List<String> IDS = List.of("notification");
                    static final Pattern OWNER_PATTERN = Pattern.compile("tts");
                    static final Object ENGINE = "mimo";
                    static final String TEXT_BLOCK = \"\"\"
                            gallery
                            \"\"\";

                    String choose(String id) {
                        if (id.isEmpty()) {
                            return "mail";
                        }
                        return switch (id) {
                            case "stats" -> "douyin";
                            default -> "novel";
                        };
                    }
                }
                """;

        assertThat(concreteOwnerLiteralViolations(List.of(
                new JavaSourceInput("fixture/RejectedLiteralContexts.java", rejected))))
                .anyMatch(violation -> violation.contains("recovery-sentinel"))
                .anyMatch(violation -> violation.contains("plugin-market"))
                .anyMatch(violation -> violation.contains("push"))
                .anyMatch(violation -> violation.contains("notification"))
                .anyMatch(violation -> violation.contains("tts"))
                .anyMatch(violation -> violation.contains("mimo"))
                .anyMatch(violation -> violation.contains("gallery"))
                .anyMatch(violation -> violation.contains("mail"))
                .anyMatch(violation -> violation.contains("stats"))
                .anyMatch(violation -> violation.contains("douyin"))
                .anyMatch(violation -> violation.contains("novel"));
    }

    @Test
    @DisplayName("approved public 常量只能按精确字段身份、修饰符和值豁免")
    void approvedPublicConstantExemptionIsExactAndFailClosed() {
        String exact = """
                package top.sywyar.pixivdownload.notification;
                public final class NotificationConfigKeys {
                    public static final String SCENARIO_PREFIX = "notification.scenario.";
                }
                """;
        assertThat(concreteOwnerLiteralViolations(List.of(new JavaSourceInput(
                "top/sywyar/pixivdownload/notification/NotificationConfigKeys.java", exact))))
                .isEmpty();

        String wrongField = exact.replace("SCENARIO_PREFIX", "OTHER_PREFIX");
        assertThat(concreteOwnerLiteralViolations(List.of(new JavaSourceInput(
                "top/sywyar/pixivdownload/notification/NotificationConfigKeys.java", wrongField))))
                .anyMatch(violation -> violation.contains("notification"));

        String wrongModifiers = exact.replace("public static final String", "private static final String");
        assertThat(concreteOwnerLiteralViolations(List.of(new JavaSourceInput(
                "top/sywyar/pixivdownload/notification/NotificationConfigKeys.java", wrongModifiers))))
                .anyMatch(violation -> violation.contains("notification"));

        String wrongValue = exact.replace("\"notification.scenario.\"",
                "\"notification.scenario.\" + \"novel\"");
        assertThat(concreteOwnerLiteralViolations(List.of(new JavaSourceInput(
                "top/sywyar/pixivdownload/notification/NotificationConfigKeys.java", wrongValue))))
                .anyMatch(violation -> violation.contains("novel"));
    }

    @Test
    @DisplayName("合法 owner 同名业务字面量只能按类型与精确值最窄豁免")
    void legitimateOwnerWordBaselineRequiresExactTypeAndValue() {
        String exactType = """
                package top.sywyar.pixivdownload.core.pixiv.filename;
                final class PixivWorkFileNameFormatter {
                    Object placeholder() {
                        return "ai";
                    }
                }
                """;
        assertThat(concreteOwnerLiteralViolations(List.of(new JavaSourceInput(
                "top/sywyar/pixivdownload/core/pixiv/filename/PixivWorkFileNameFormatter.java",
                exactType))))
                .isEmpty();

        String wrongType = exactType
                .replace("PixivWorkFileNameFormatter", "OtherFormatter");
        assertThat(concreteOwnerLiteralViolations(List.of(new JavaSourceInput(
                "top/sywyar/pixivdownload/core/pixiv/filename/OtherFormatter.java",
                wrongType))))
                .anyMatch(violation -> violation.contains("concrete plugin id ai"));

        String wrongValue = exactType.replace("\"ai\"", "\"ai-owner\"");
        assertThat(concreteOwnerLiteralViolations(List.of(new JavaSourceInput(
                "top/sywyar/pixivdownload/core/pixiv/filename/PixivWorkFileNameFormatter.java",
                wrongValue))))
                .anyMatch(violation -> violation.contains("concrete plugin id ai"));
    }

    @Test
    @DisplayName("javac AST 解析出现 ERROR 时 owner 守卫必须 fail-closed")
    void ownerLiteralAstGuardFailsClosedOnParseErrors() {
        String malformed = """
                package fixture;
                final class MalformedFixture {
                    String owner = "novel"
                """;

        assertThat(concreteOwnerLiteralViolations(List.of(
                new JavaSourceInput("fixture/MalformedFixture.java", malformed))))
                .anyMatch(violation -> violation.contains("parse ERROR"));
    }

    private static Set<String> approvedTypes() {
        Set<String> approved = new LinkedHashSet<>();
        int declaredCount = 0;
        for (Set<String> ownerTypes : APPROVED_TYPES_BY_OWNER.values()) {
            declaredCount += ownerTypes.size();
            approved.addAll(ownerTypes);
        }
        if (approved.size() != declaredCount) {
            throw new IllegalStateException("core-api owner whitelist contains duplicate types");
        }
        return Set.copyOf(approved);
    }

    private static List<String> topLevelContractVisibilityViolations(Set<String> typeNames) {
        Set<String> violations = new LinkedHashSet<>();
        for (String typeName : typeNames.stream().sorted().toList()) {
            Class<?> type;
            try {
                type = Class.forName(typeName, false, CoreApiOwnershipGuardTest.class.getClassLoader());
            } catch (ClassNotFoundException | LinkageError failure) {
                violations.add(typeName + " cannot be loaded: " + failure.getClass().getSimpleName());
                continue;
            }
            if (type.getEnclosingClass() != null) {
                violations.add(typeName + " is not a top-level contract");
            }
            if (!Modifier.isPublic(type.getModifiers())) {
                violations.add(typeName + " is not public");
            }
        }
        return List.copyOf(violations);
    }

    private static void assertRecordShape(Class<?> type, List<String> componentNames, List<Class<?>> componentTypes) {
        assertThat(type.isRecord()).as(type.getName() + " must remain a record").isTrue();
        assertThat(Arrays.stream(type.getRecordComponents()).map(component -> component.getName()).toList())
                .containsExactlyElementsOf(componentNames);
        assertThat(Arrays.stream(type.getRecordComponents())
                .map(component -> component.getType().getName())
                .toList())
                .containsExactlyElementsOf(componentTypes.stream().map(Class::getName).toList());
    }

    private static Set<String> publicDeclaredMethodSignatures(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(CoreApiOwnershipGuardTest::methodSignature)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static String methodSignature(Method method) {
        String parameters = Arrays.stream(method.getParameterTypes())
                .map(Class::getName)
                .collect(Collectors.joining(","));
        return Modifier.toString(method.getModifiers()) + " " + method.getName()
                + "(" + parameters + "):" + method.getReturnType().getName();
    }

    private static Set<String> types(String packageName, String... simpleNames) {
        Set<String> types = new LinkedHashSet<>();
        Arrays.stream(simpleNames).map(name -> packageName + "." + name).forEach(types::add);
        return Set.copyOf(types);
    }

    @SafeVarargs
    private static Set<String> union(Set<String>... groups) {
        Set<String> result = new LinkedHashSet<>();
        Arrays.stream(groups).forEach(result::addAll);
        return Set.copyOf(result);
    }

    private static List<Path> productionSources() throws IOException {
        Path root = productionSourceRoot();
        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> sources = paths.filter(path -> path.toString().endsWith(".java")).sorted().toList();
            if (sources.size() != approvedTypes().size()) {
                throw new IllegalStateException("core-api source scan must cover exactly " + approvedTypes().size()
                        + " production files, but found " + sources.size() + " under " + root);
            }
            return sources;
        }
    }

    private static Path productionSourceRoot() {
        for (Path candidate : List.of(
                Path.of("src", "main", "java"),
                Path.of("pixivdownload-core-api", "src", "main", "java"))) {
            Path marker = candidate.resolve(Path.of(
                    "top", "sywyar", "pixivdownload", "ai", "AiChatClient.java"));
            if (Files.isDirectory(candidate) && Files.isRegularFile(marker)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        throw new IllegalStateException("cannot locate pixivdownload-core-api production source root");
    }

    private static List<String> documentationViolations(String sourceName, String content) {
        String packageName = requiredPackageName(content);
        Map<String, String> imports = importedTypes(content);
        Set<String> violations = new LinkedHashSet<>();
        Matcher blocks = JAVADOC_BLOCK.matcher(content);
        while (blocks.find()) {
            String javadoc = blocks.group(1);
            int offset = blocks.start(1);

            Matcher typeReference = JAVADOC_TYPE_REFERENCE.matcher(javadoc);
            while (typeReference.find()) {
                String token = typeReference.group(1);
                if (!isAllowedTypeReference(token, packageName, imports)) {
                    violations.add(documentationViolation(
                            sourceName, content, offset + typeReference.start(1), "unapproved type", token));
                }
            }

            Matcher seeReference = JAVADOC_SEE.matcher(javadoc);
            while (seeReference.find()) {
                String token = seeReference.group(1);
                if (!isAllowedTypeReference(token, packageName, imports)) {
                    violations.add(documentationViolation(
                            sourceName, content, offset + seeReference.start(1), "unapproved @see type", token));
                }
            }

            Matcher codeType = JAVADOC_CODE_TYPE_REFERENCE.matcher(javadoc);
            while (codeType.find()) {
                String token = codeType.group(1);
                if (looksLikeTypeReference(token) && !isAllowedTypeReference(token, packageName, imports)) {
                    violations.add(documentationViolation(
                            sourceName, content, offset + codeType.start(1), "unapproved code type", token));
                }
            }

            Matcher projectReference = PROJECT_REFERENCE.matcher(javadoc);
            while (projectReference.find()) {
                String token = projectReference.group();
                if (!isApprovedProjectReference(token)) {
                    violations.add(documentationViolation(
                            sourceName, content, offset + projectReference.start(), "unapproved project reference", token));
                }
            }

            Matcher relativePackage = RELATIVE_IMPLEMENTATION_PACKAGE_REFERENCE.matcher(javadoc);
            while (relativePackage.find()) {
                String token = relativePackage.group(1);
                if (!isApprovedProjectReference("top.sywyar.pixivdownload." + token)) {
                    violations.add(documentationViolation(
                            sourceName, content, offset + relativePackage.start(1), "unapproved package", token));
                }
            }

            Matcher implementationTerm = FORBIDDEN_IMPLEMENTATION_TERM.matcher(javadoc);
            while (implementationTerm.find()) {
                violations.add(documentationViolation(sourceName, content,
                        offset + implementationTerm.start(), "implementation term", implementationTerm.group()));
            }

            Matcher pluginProse = CONCRETE_PLUGIN_PROSE.matcher(javadoc);
            while (pluginProse.find()) {
                violations.add(documentationViolation(sourceName, content,
                        offset + pluginProse.start(), "concrete plugin prose", pluginProse.group()));
            }
            Matcher pushChannelProse = CONCRETE_PUSH_CHANNEL_PROSE.matcher(javadoc);
            while (pushChannelProse.find()) {
                violations.add(documentationViolation(sourceName, content,
                        offset + pushChannelProse.start(),
                        "concrete push channel prose", pushChannelProse.group()));
            }

            for (Pattern pattern : PLUGIN_PRIVATE_LAYOUT_PATTERNS) {
                Matcher privateLayout = pattern.matcher(javadoc);
                while (privateLayout.find()) {
                    violations.add(documentationViolation(sourceName, content,
                            offset + privateLayout.start(), "plugin-private layout", privateLayout.group()));
                }
            }
        }
        return List.copyOf(violations);
    }

    private static List<String> concreteOwnerLiteralViolations(List<JavaSourceInput> sources) {
        Set<String> violations = new LinkedHashSet<>();
        if (sources.isEmpty()) {
            return List.of("javac source batch is empty");
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return List.of("system javac compiler is unavailable");
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        List<JavaFileObject> sourceFiles = sources.stream()
                .map(InMemoryJavaSource::new)
                .map(JavaFileObject.class::cast)
                .toList();
        JavacTask task = (JavacTask) compiler.getTask(
                null,
                null,
                diagnostics,
                List.of("-proc:none", "--release", "17"),
                null,
                sourceFiles);
        List<CompilationUnitTree> units = new ArrayList<>();
        try {
            task.parse().forEach(units::add);
        } catch (IOException failure) {
            violations.add("javac batch parse failed: " + failure.getClass().getSimpleName());
        }
        diagnostics.getDiagnostics().stream()
                .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                .map(CoreApiOwnershipGuardTest::parseError)
                .forEach(violations::add);
        if (units.size() != sources.size()) {
            violations.add("javac batch parsed " + units.size() + " of " + sources.size() + " source units");
        }

        SourcePositions positions;
        try {
            positions = Trees.instance(task).getSourcePositions();
        } catch (RuntimeException failure) {
            violations.add("javac source positions unavailable: " + failure.getClass().getSimpleName());
            return List.copyOf(violations);
        }
        for (CompilationUnitTree unit : units) {
            Set<Tree> approvedConstantTrees = approvedPublicStringConstantTrees(unit);
            new ConcreteOwnerLiteralScanner(unit, positions, approvedConstantTrees, violations)
                    .scan(unit, null);
        }
        return List.copyOf(violations);
    }

    private static String parseError(Diagnostic<? extends JavaFileObject> diagnostic) {
        String source = diagnostic.getSource() == null ? "<unknown>"
                : diagnostic.getSource().getName();
        return source + ":" + diagnostic.getLineNumber()
                + " parse ERROR " + diagnostic.getMessage(Locale.ROOT);
    }

    private static Set<Tree> approvedPublicStringConstantTrees(CompilationUnitTree unit) {
        Set<Tree> approved = Collections.newSetFromMap(new IdentityHashMap<>());
        new TreePathScanner<Void, Void>() {
            private final Deque<String> enclosingTypes = new ArrayDeque<>();

            @Override
            public Void visitClass(ClassTree node, Void unused) {
                String simpleName = node.getSimpleName().toString();
                enclosingTypes.addLast(simpleName.isEmpty() ? "<anonymous>" : simpleName);
                try {
                    return super.visitClass(node, unused);
                } finally {
                    enclosingTypes.removeLast();
                }
            }

            @Override
            public Void visitVariable(VariableTree node, Void unused) {
                TreePath parent = getCurrentPath().getParentPath();
                if (parent != null
                        && parent.getLeaf() instanceof ClassTree
                        && isExplicitPublicStaticFinalString(node)) {
                    String key = currentTypeName(unit, enclosingTypes)
                            + "#" + node.getName() + ":java.lang.String";
                    Object expected = APPROVED_PUBLIC_CONSTANTS.get(key);
                    String actual = foldStringLiteral(node.getInitializer());
                    if (expected instanceof String value && value.equals(actual)) {
                        new TreeScanner<Void, Void>() {
                            @Override
                            public Void scan(Tree tree, Void ignored) {
                                if (tree != null) {
                                    approved.add(tree);
                                }
                                return super.scan(tree, ignored);
                            }
                        }.scan(node.getInitializer(), null);
                    }
                }
                return super.visitVariable(node, unused);
            }
        }.scan(unit, null);
        return approved;
    }

    private static boolean isExplicitPublicStaticFinalString(VariableTree variable) {
        Set<javax.lang.model.element.Modifier> modifiers = variable.getModifiers().getFlags();
        return modifiers.contains(javax.lang.model.element.Modifier.PUBLIC)
                && modifiers.contains(javax.lang.model.element.Modifier.STATIC)
                && modifiers.contains(javax.lang.model.element.Modifier.FINAL)
                && variable.getType() != null
                && Set.of("String", "java.lang.String").contains(variable.getType().toString());
    }

    private static String foldStringLiteral(ExpressionTree expression) {
        if (expression instanceof LiteralTree literal && literal.getValue() instanceof String value) {
            return value;
        }
        if (expression instanceof ParenthesizedTree parenthesized) {
            return foldStringLiteral(parenthesized.getExpression());
        }
        if (expression instanceof BinaryTree binary && binary.getKind() == Tree.Kind.PLUS) {
            String left = foldStringLiteral(binary.getLeftOperand());
            String right = foldStringLiteral(binary.getRightOperand());
            return left == null || right == null ? null : left + right;
        }
        return null;
    }

    private static String currentTypeName(CompilationUnitTree unit, Deque<String> enclosingTypes) {
        String packageName = unit.getPackageName() == null ? "" : unit.getPackageName().toString();
        String nestedName = String.join("$", enclosingTypes);
        return packageName.isEmpty() ? nestedName : packageName + "." + nestedName;
    }

    private static boolean isMaximalFoldableString(TreePath path) {
        TreePath parent = path.getParentPath();
        while (parent != null && parent.getLeaf() instanceof ParenthesizedTree) {
            parent = parent.getParentPath();
        }
        return parent == null
                || !(parent.getLeaf() instanceof BinaryTree binary)
                || binary.getKind() != Tree.Kind.PLUS
                || foldStringLiteral(binary) == null;
    }

    private static final class ConcreteOwnerLiteralScanner extends TreePathScanner<Void, Void> {

        private final CompilationUnitTree unit;
        private final SourcePositions positions;
        private final Set<Tree> approvedConstantTrees;
        private final Set<String> violations;
        private final Deque<String> enclosingTypes = new ArrayDeque<>();

        private ConcreteOwnerLiteralScanner(CompilationUnitTree unit,
                                            SourcePositions positions,
                                            Set<Tree> approvedConstantTrees,
                                            Set<String> violations) {
            this.unit = unit;
            this.positions = positions;
            this.approvedConstantTrees = approvedConstantTrees;
            this.violations = violations;
        }

        @Override
        public Void visitClass(ClassTree node, Void unused) {
            String simpleName = node.getSimpleName().toString();
            enclosingTypes.addLast(simpleName.isEmpty() ? "<anonymous>" : simpleName);
            try {
                return super.visitClass(node, unused);
            } finally {
                enclosingTypes.removeLast();
            }
        }

        @Override
        public Void visitLiteral(LiteralTree node, Void unused) {
            if (!approvedConstantTrees.contains(node) && node.getValue() instanceof String value) {
                inspect(value, node, "string literal");
            }
            return super.visitLiteral(node, unused);
        }

        @Override
        public Void visitBinary(BinaryTree node, Void unused) {
            if (node.getKind() == Tree.Kind.PLUS
                    && !approvedConstantTrees.contains(node)
                    && isMaximalFoldableString(getCurrentPath())) {
                String folded = foldStringLiteral(node);
                if (folded != null) {
                    inspect(folded, node, "folded string literal");
                }
            }
            return super.visitBinary(node, unused);
        }

        private void inspect(String value, Tree tree, String kind) {
            String typeName = currentTypeName(unit, enclosingTypes);
            if (APPROVED_OWNER_LITERALS_BY_TYPE.getOrDefault(typeName, Set.of()).contains(value)) {
                return;
            }
            CONCRETE_PLUGIN_IDS.stream().sorted()
                    .filter(id -> containsOwnerToken(value, id))
                    .forEach(id -> violations.add(violation(tree, kind, "plugin", id, value, typeName)));
            CONCRETE_ENGINE_IDS.stream().sorted()
                    .filter(id -> containsOwnerToken(value, id))
                    .forEach(id -> violations.add(violation(tree, kind, "engine", id, value, typeName)));
        }

        private String violation(Tree tree,
                                 String kind,
                                 String ownerKind,
                                 String ownerId,
                                 String value,
                                 String typeName) {
            long offset = positions.getStartPosition(unit, tree);
            long line = offset < 0 || unit.getLineMap() == null
                    ? -1 : unit.getLineMap().getLineNumber(offset);
            return unit.getSourceFile().getName() + ":" + line
                    + " concrete " + ownerKind + " id " + ownerId
                    + " in " + kind + " " + displayLiteral(value)
                    + " of " + typeName;
        }
    }

    private static String displayLiteral(String value) {
        String visible = value
                .replace("\\", "\\\\")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
        return "\"" + (visible.length() <= 160 ? visible : visible.substring(0, 160) + "…") + "\"";
    }

    private static boolean containsOwnerToken(String value, String ownerId) {
        int offset = value.indexOf(ownerId);
        while (offset >= 0) {
            int end = offset + ownerId.length();
            boolean startsAtBoundary = offset == 0 || !Character.isLetterOrDigit(value.charAt(offset - 1));
            boolean endsAtBoundary = end == value.length() || !Character.isLetterOrDigit(value.charAt(end));
            if (startsAtBoundary && endsAtBoundary) {
                return true;
            }
            offset = value.indexOf(ownerId, offset + 1);
        }
        return false;
    }

    private static boolean isAllowedTypeReference(String rawToken,
                                                  String packageName,
                                                  Map<String, String> imports) {
        if (rawToken.startsWith("#")) {
            return true;
        }
        String token = rawToken.trim();
        int memberSeparator = token.indexOf('#');
        if (memberSeparator >= 0) {
            token = token.substring(0, memberSeparator);
        }
        int genericSeparator = token.indexOf('<');
        if (genericSeparator >= 0) {
            token = token.substring(0, genericSeparator);
        }
        while (token.endsWith("[]")) {
            token = token.substring(0, token.length() - 2);
        }
        if (token.startsWith("java.")) {
            return isLoadableJdkType(token);
        }
        if (token.startsWith("top.sywyar.pixivdownload.")) {
            return isApprovedTypeReference(token);
        }

        String rootSimpleName = token.contains(".") ? token.substring(0, token.indexOf('.')) : token;
        String imported = imports.get(rootSimpleName);
        if (imported != null) {
            String resolved = imported + token.substring(rootSimpleName.length());
            return resolved.startsWith("java.") ? isLoadableJdkType(resolved) : isApprovedTypeReference(resolved);
        }

        if (isApprovedTypeReference(packageName + "." + token)) {
            return true;
        }
        return isLoadableJdkType("java.lang." + token);
    }

    private static boolean isApprovedProjectReference(String reference) {
        if (isApprovedTypeReference(reference)) {
            return true;
        }
        return approvedTypes().stream()
                .map(CoreApiOwnershipGuardTest::packageName)
                .anyMatch(reference::equals);
    }

    private static boolean isApprovedTypeReference(String reference) {
        if (approvedTypes().contains(reference) || APPROVED_PUBLIC_NESTED_TYPES.contains(reference)) {
            return true;
        }
        return APPROVED_PUBLIC_NESTED_TYPES.stream()
                .map(name -> name.replace('$', '.'))
                .anyMatch(reference::equals);
    }

    private static boolean isLoadableJdkType(String reference) {
        if (!reference.startsWith("java.")) {
            return false;
        }
        String candidate = reference;
        while (true) {
            try {
                Class.forName(candidate, false, CoreApiOwnershipGuardTest.class.getClassLoader());
                return true;
            } catch (ClassNotFoundException ignored) {
                int separator = candidate.lastIndexOf('.');
                if (separator < "java.".length()) {
                    return false;
                }
                candidate = candidate.substring(0, separator) + '$' + candidate.substring(separator + 1);
            }
        }
    }

    private static boolean looksLikeTypeReference(String token) {
        String leaf = token.substring(token.lastIndexOf('.') + 1);
        return !leaf.equals(leaf.toUpperCase(Locale.ROOT));
    }

    private static String requiredPackageName(String content) {
        Matcher matcher = PACKAGE_DECLARATION.matcher(content);
        if (!matcher.find()) {
            throw new IllegalArgumentException("source fixture has no package declaration");
        }
        return matcher.group(1);
    }

    private static Map<String, String> importedTypes(String content) {
        Map<String, String> imports = new LinkedHashMap<>();
        Matcher matcher = IMPORT_DECLARATION.matcher(content);
        while (matcher.find()) {
            String imported = matcher.group(1);
            imports.put(simpleName(imported), imported);
        }
        return Map.copyOf(imports);
    }

    private static String documentationViolation(String sourceName,
                                                 String content,
                                                 int offset,
                                                 String kind,
                                                 String token) {
        return sourceName + ":" + lineNumber(content, offset) + " " + kind + " " + token;
    }

    private static int lineNumber(String content, int offset) {
        int line = 1;
        for (int index = 0; index < offset; index++) {
            if (content.charAt(index) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static String packageName(String fullyQualifiedName) {
        int separator = fullyQualifiedName.lastIndexOf('.');
        return separator < 0 ? "" : fullyQualifiedName.substring(0, separator);
    }

    private static String simpleName(String fullyQualifiedName) {
        int separator = fullyQualifiedName.lastIndexOf('.');
        return separator < 0 ? fullyQualifiedName : fullyQualifiedName.substring(separator + 1);
    }

    private static String relativeSource(Path source) {
        return productionSourceRoot().relativize(source.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private record JavaSourceInput(String name, String content) {

        private JavaSourceInput {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("source name must not be blank");
            }
            if (content == null) {
                throw new IllegalArgumentException("source content must not be null");
            }
        }
    }

    private static final class InMemoryJavaSource extends SimpleJavaFileObject {

        private final JavaSourceInput source;

        private InMemoryJavaSource(JavaSourceInput source) {
            super(sourceUri(source.name()), Kind.SOURCE);
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source.content();
        }

        @Override
        public String getName() {
            return source.name();
        }

        private static URI sourceUri(String sourceName) {
            String normalized = sourceName.replace('\\', '/')
                    .replace(" ", "%20")
                    .replace("#", "%23");
            return URI.create("mem:///" + normalized);
        }
    }
}
