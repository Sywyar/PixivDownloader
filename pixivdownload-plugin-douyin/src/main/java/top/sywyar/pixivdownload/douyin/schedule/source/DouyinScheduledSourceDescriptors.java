package top.sywyar.pixivdownload.douyin.schedule.source;

import top.sywyar.pixivdownload.douyin.schedule.codec.DouyinScheduleCodec;
import top.sywyar.pixivdownload.douyin.source.DouyinSourceTypes;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceDescriptor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceFrontendContribution;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourcePresentation;
import top.sywyar.pixivdownload.plugin.api.web.DownloadAcquisitionMode;

import java.util.List;
import java.util.Set;

/** 八类稳定抖音计划来源的纯数据描述符事实源。 */
public final class DouyinScheduledSourceDescriptors {

    public static final String CREDENTIAL_POLICY_ID = "douyin.cookie";
    public static final String GUARD_ID = "douyin.risk";
    public static final String FRONTEND_MODULE_URL =
            "/pixiv-douyin-download/douyin-schedule-sources.js";

    private static final ScheduledSourceFrontendContribution FRONTEND =
            new ScheduledSourceFrontendContribution(
                    ScheduledSourceFrontendContribution.CURRENT_CONTRACT_VERSION,
                    FRONTEND_MODULE_URL);

    private static final List<ScheduledSourceDescriptor> DESCRIPTORS = List.of(
            descriptor(
                    DouyinSourceTypes.USER,
                    "schedule.source.user",
                    Set.of(DownloadAcquisitionMode.USER_PROFILE.code())),
            descriptor(
                    DouyinSourceTypes.SEARCH,
                    "schedule.source.search",
                    Set.of(DownloadAcquisitionMode.SEARCH.code())),
            descriptor(
                    DouyinSourceTypes.COLLECTION,
                    "schedule.source.collection",
                    Set.of(DownloadAcquisitionMode.SERIES_COLLECTION.code())),
            descriptor(
                    DouyinSourceTypes.MUSIC,
                    "schedule.source.music",
                    Set.of(DownloadAcquisitionMode.SERIES_COLLECTION.code())),
            descriptor(
                    DouyinSourceTypes.ACCOUNT_OWN_WORKS,
                    "schedule.source.account-own",
                    Set.of(DownloadAcquisitionMode.QUICK.code())),
            descriptor(
                    DouyinSourceTypes.ACCOUNT_LIKED_WORKS,
                    "schedule.source.account-liked",
                    Set.of(DownloadAcquisitionMode.QUICK.code())),
            descriptor(
                    DouyinSourceTypes.ACCOUNT_FAVORITE_WORKS,
                    "schedule.source.account-favorite",
                    Set.of(DownloadAcquisitionMode.QUICK.code())),
            descriptor(
                    DouyinSourceTypes.ACCOUNT_FAVORITE_COLLECTION,
                    "schedule.source.account-favorite-collection",
                    Set.of(DownloadAcquisitionMode.QUICK.code())));

    private DouyinScheduledSourceDescriptors() {
    }

    public static List<ScheduledSourceDescriptor> createAll() {
        return DESCRIPTORS;
    }

    private static ScheduledSourceDescriptor descriptor(
            String sourceType,
            String presentationKeyPrefix,
            Set<String> acquisitionModes) {
        return new ScheduledSourceDescriptor(
                sourceType,
                Set.of(),
                DouyinScheduleCodec.DEFINITION_SCHEMA,
                DouyinScheduleCodec.DEFINITION_VERSION,
                new ScheduledSourcePresentation(
                        "douyin",
                        presentationKeyPrefix + ".name",
                        presentationKeyPrefix + ".description",
                        "video",
                        "red"),
                acquisitionModes,
                Set.of(DouyinScheduleCodec.WORK_TYPE),
                Set.of(CREDENTIAL_POLICY_ID),
                Set.of(GUARD_ID),
                FRONTEND);
    }
}
