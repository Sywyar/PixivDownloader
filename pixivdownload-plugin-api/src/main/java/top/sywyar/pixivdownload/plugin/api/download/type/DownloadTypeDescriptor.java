package top.sywyar.pixivdownload.plugin.api.download.type;

import java.util.List;

/**
 * 插件声明的下载工作台作品类型。宿主为 descriptor 盖上可信 owner 与 publication 身份，插件不在纯数据契约中
 * 自报 owner；下载页 UI 槽位则由独立的 Web UI slot contribution 声明。
 *
 * @param contractVersion  前端行为模块契约版本，当前为 1
 * @param type             全局唯一作品类型 id
 * @param displayNamespace 展示名 i18n namespace
 * @param displayI18nKey   展示名 i18n key（纯 key）
 * @param order            展示排序
 * @param iconKey          受控图标 token
 * @param colorToken       受控颜色 token
 * @param moduleUrl        前端行为模块 URL；必填，注册时还须校验为声明方拥有的同源绝对路径
 * @param acquisitionModes 支持的取得模式
 * @param cancelSupported  是否支持按不透明 work key 取消单项任务
 * @param filters          该类型暴露的筛选契约 id
 * @param settings         该类型暴露的设置契约 id
 * @param i18nNamespace    该类型前端文案所在 namespace
 */
public record DownloadTypeDescriptor(
        int contractVersion,
        String type,
        String displayNamespace,
        String displayI18nKey,
        int order,
        String iconKey,
        String colorToken,
        String moduleUrl,
        List<DownloadAcquisitionMode> acquisitionModes,
        boolean cancelSupported,
        List<String> filters,
        List<String> settings,
        String i18nNamespace
) {

    public static final int CURRENT_CONTRACT_VERSION = 1;

    public DownloadTypeDescriptor {
        if (contractVersion <= 0) {
            throw new IllegalArgumentException("contractVersion must be positive");
        }
        if (moduleUrl == null || moduleUrl.isBlank()) {
            throw new IllegalArgumentException("moduleUrl must not be blank");
        }
        moduleUrl = moduleUrl.trim();
        acquisitionModes = acquisitionModes == null ? List.of() : List.copyOf(acquisitionModes);
        filters = filters == null ? List.of() : List.copyOf(filters);
        settings = settings == null ? List.of() : List.copyOf(settings);
    }
}
