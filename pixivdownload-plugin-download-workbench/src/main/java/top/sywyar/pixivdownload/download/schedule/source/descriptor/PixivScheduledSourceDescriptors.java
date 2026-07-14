package top.sywyar.pixivdownload.download.schedule.source.descriptor;

import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceDescriptor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceFrontendContribution;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourcePresentation;
import top.sywyar.pixivdownload.plugin.api.web.DownloadAcquisitionMode;
import top.sywyar.pixivdownload.schedule.persistence.PixivSchedulePersistenceCodec;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 七类 Pixiv 计划任务来源的纯数据描述符事实源。 */
public final class PixivScheduledSourceDescriptors {

    public static final String OVERUSE_GUARD_ID = "pixiv-overuse";
    public static final String FRONTEND_MODULE_URL =
            "/pixiv-batch/pixiv-schedule-sources.js";

    private static final ScheduledSourceFrontendContribution FRONTEND =
            new ScheduledSourceFrontendContribution(
                    ScheduledSourceFrontendContribution.CURRENT_CONTRACT_VERSION,
                    FRONTEND_MODULE_URL);

    private static final Set<String> ILLUST_ONLY = Set.of(
            PixivSchedulePersistenceCodec.WORK_TYPE_ILLUST);
    private static final Set<String> ILLUST_OR_NOVEL = Set.of(
            PixivSchedulePersistenceCodec.WORK_TYPE_ILLUST,
            PixivSchedulePersistenceCodec.WORK_TYPE_NOVEL);

    private static final List<ScheduledSourceDescriptor> DESCRIPTORS = List.of(
            descriptor(
                    "user-new",
                    "schedule.type.user-new",
                    Set.of(DownloadAcquisitionMode.USER_PROFILE.code(), DownloadAcquisitionMode.QUICK.code()),
                    ILLUST_OR_NOVEL),
            descriptor(
                    "user-request",
                    "schedule.type.user-request",
                    Set.of(DownloadAcquisitionMode.USER_PROFILE.code(), DownloadAcquisitionMode.QUICK.code()),
                    ILLUST_ONLY),
            descriptor(
                    "search",
                    "schedule.type.search",
                    Set.of(DownloadAcquisitionMode.SEARCH.code()),
                    ILLUST_OR_NOVEL),
            descriptor(
                    "series",
                    "schedule.type.series",
                    Set.of(DownloadAcquisitionMode.SERIES_COLLECTION.code()),
                    ILLUST_OR_NOVEL),
            descriptor(
                    "my-bookmarks",
                    "schedule.type.my-bookmarks",
                    Set.of(DownloadAcquisitionMode.QUICK.code()),
                    ILLUST_OR_NOVEL),
            descriptor(
                    "follow-latest",
                    "schedule.type.follow-latest",
                    Set.of(DownloadAcquisitionMode.QUICK.code()),
                    ILLUST_ONLY),
            descriptor(
                    "collection",
                    "schedule.type.collection",
                    Set.of(DownloadAcquisitionMode.QUICK.code()),
                    ILLUST_OR_NOVEL));

    private PixivScheduledSourceDescriptors() {
    }

    /** 返回固定顺序、不可变的七类来源描述符。 */
    public static List<ScheduledSourceDescriptor> createAll() {
        return DESCRIPTORS;
    }

    private static ScheduledSourceDescriptor descriptor(
            String sourceType,
            String displayKey,
            Set<String> acquisitionModes,
            Set<String> possibleWorkTypes) {
        return new ScheduledSourceDescriptor(
                sourceType,
                legacyAliases(sourceType),
                PixivSchedulePersistenceCodec.DEFINITION_SCHEMA,
                PixivSchedulePersistenceCodec.DEFINITION_VERSION,
                new ScheduledSourcePresentation(
                        "batch", displayKey, displayKey, "schedule", "pixiv"),
                acquisitionModes,
                possibleWorkTypes,
                Set.of(PixivSchedulePersistenceCodec.CREDENTIAL_POLICY_ID),
                Set.of(OVERUSE_GUARD_ID),
                FRONTEND);
    }

    private static Set<String> legacyAliases(String sourceType) {
        return PixivSchedulePersistenceCodec.legacySourceAliases().entrySet().stream()
                .filter(entry -> sourceType.equals(entry.getValue()))
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
