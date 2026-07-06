package top.sywyar.pixivdownload.plugin.api.web;

import java.util.List;

/**
 * 下载工作台作品类型的稳定 descriptor。它补足 {@link QueueTypeContribution} 原有的最小注册信息，
 * 让后端、前端行为模块和计划任务 / 画廊边界对同一个 type 的能力有统一声明来源。
 *
 * @param contractVersion 前端行为模块契约版本，当前为 1
 * @param pluginId        声明方插件 id
 * @param type            全局唯一作品类型 id
 * @param displayNamespace 展示名 i18n namespace
 * @param displayI18nKey  展示名 i18n key（纯 key）
 * @param order           展示排序
 * @param iconKey         受控图标 token
 * @param colorToken      受控颜色 token
 * @param moduleUrl       前端行为模块 URL；内置类型可为 {@code null}
 * @param acquisitionModes 支持的取得模式
 * @param queue           队列操作能力
 * @param schedule        计划任务能力
 * @param filters         该类型暴露的筛选契约 id
 * @param settings        该类型暴露的设置契约 id
 * @param uiSlots         该类型声明的下载页 UI slot target
 * @param i18nNamespace   该类型前端文案所在 namespace
 * @param gallery         画廊接入能力
 */
public record DownloadTypeDescriptor(
        int contractVersion,
        String pluginId,
        String type,
        String displayNamespace,
        String displayI18nKey,
        int order,
        String iconKey,
        String colorToken,
        String moduleUrl,
        List<DownloadAcquisitionMode> acquisitionModes,
        DownloadQueueCapabilities queue,
        DownloadScheduleCapabilities schedule,
        List<String> filters,
        List<String> settings,
        List<String> uiSlots,
        String i18nNamespace,
        DownloadGalleryCapabilities gallery
) {

    public static final int CURRENT_CONTRACT_VERSION = 1;

    public DownloadTypeDescriptor {
        contractVersion = contractVersion <= 0 ? CURRENT_CONTRACT_VERSION : contractVersion;
        acquisitionModes = acquisitionModes == null ? List.of() : List.copyOf(acquisitionModes);
        queue = queue == null ? DownloadQueueCapabilities.clearOnly() : queue;
        schedule = schedule == null ? DownloadScheduleCapabilities.notSaveable() : schedule;
        filters = filters == null ? List.of() : List.copyOf(filters);
        settings = settings == null ? List.of() : List.copyOf(settings);
        uiSlots = uiSlots == null ? List.of() : List.copyOf(uiSlots);
        gallery = gallery == null ? DownloadGalleryCapabilities.none() : gallery;
    }

    /**
     * 兼容旧队列类型贡献的最小 descriptor。旧构造器调用方会得到可校验的 descriptor，但新类型应显式声明完整能力。
     */
    public static DownloadTypeDescriptor legacy(String pluginId,
                                                String type,
                                                String displayNamespace,
                                                String displayI18nKey,
                                                int order,
                                                String moduleUrl) {
        return new DownloadTypeDescriptor(
                CURRENT_CONTRACT_VERSION,
                pluginId,
                type,
                displayNamespace,
                displayI18nKey,
                order,
                null,
                null,
                moduleUrl,
                List.of(
                        DownloadAcquisitionMode.SINGLE_IMPORT,
                        DownloadAcquisitionMode.USER_PROFILE,
                        DownloadAcquisitionMode.SERIES_COLLECTION,
                        DownloadAcquisitionMode.SEARCH,
                        DownloadAcquisitionMode.QUICK),
                DownloadQueueCapabilities.clearOnly(),
                DownloadScheduleCapabilities.notSaveable(),
                List.of(),
                List.of(),
                List.of(),
                displayNamespace,
                DownloadGalleryCapabilities.none());
    }
}
