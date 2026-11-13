package top.sywyar.pixivdownload.config;

/**
 * 跨插件边界共享的只读宿主下载设置。
 * 业务专用的执行设置仍由对应插件所有。
 */
public interface DownloadSettings {

    /**
     * 返回默认目录模板的固定前缀，供旧数据探测、归档和路径编解码使用。
     *
     * @return 保留相对或绝对属性的固定路径；不含作品变量
     */
    String getRootFolder();

    /**
     * 取得本次来源模式的最终作品目录模板；模式未配置或为空时使用默认模板。
     *
     * @param mode 取得模式的稳定代码；缺少来源信息时传 null
     * @return 完整目录模板，不含隐式追加层级
     */
    String directoryTemplate(String mode);

    /** {@return 已配置的模式覆盖快照；空值表示使用默认模板} */
    java.util.Map<String, String> directoryOverrides();

    /**
     * 返回最大值并发。
     *
     * @return 方法返回的数值
     */
    int getMaxConcurrent();
}
