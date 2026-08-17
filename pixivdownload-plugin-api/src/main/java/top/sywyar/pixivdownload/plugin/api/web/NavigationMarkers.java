package top.sywyar.pixivdownload.plugin.api.web;

/**
 * 导航贡献共享的语义标记。
 *
 * <p>标记不是 slot。中性消费者可以据此定位承担特定角色的入口，而无需依赖插件 ID 或 URL 路径。
 */
public final class NavigationMarkers {

    private NavigationMarkers() {
    }

    /** 首次引导下载成功后高亮的入口。 */
    public static final String FIRST_DOWNLOAD_RESULT = "first-download-result";
}
