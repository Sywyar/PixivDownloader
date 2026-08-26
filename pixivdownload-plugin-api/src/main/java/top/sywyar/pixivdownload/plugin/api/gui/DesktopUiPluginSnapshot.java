package top.sywyar.pixivdownload.plugin.api.gui;

import top.sywyar.pixivdownload.plugin.api.web.NavigationContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;

import java.util.List;

/**
 * 活动插件向桌面 GUI 暴露的不可变业务语义快照。
 * 该快照不得携带插件实例、classloader、PF4J 对象或工具包组件。
 *
 * @param id 功能插件标识符
 * @param builtIn 是否为内置插件
 * @param packageId 插件包标识符
 * @param generation 插件包物理代际
 * @param desktopUiProvider 是否提供桌面界面
 * @param displayNamespace 可选显示文案命名空间
 * @param displayNameKey 显示名称消息键
 * @param themes 主题语义贡献
 * @param configContributions 配置语义贡献
 * @param onboardingSteps 引导步骤语义贡献
 * @param routes 活动路由声明
 * @param navigation 活动导航声明
 */
public record DesktopUiPluginSnapshot(
        String id,
        boolean builtIn,
        String packageId,
        long generation,
        boolean desktopUiProvider,
        String displayNamespace,
        String displayNameKey,
        List<GuiThemeContribution> themes,
        List<GuiConfigContribution> configContributions,
        List<GuiOnboardingStepContribution> onboardingSteps,
        List<WebRouteContribution> routes,
        List<NavigationContribution> navigation
) {
    /**
     * 校验标识与代际，并把所有贡献复制为不可变列表。
     *
     * @param id 功能插件标识符
     * @param builtIn 是否为内置插件
     * @param packageId 插件包标识符
     * @param generation 插件包物理代际
     * @param desktopUiProvider 是否提供桌面界面
     * @param displayNamespace 可选显示文案命名空间
     * @param displayNameKey 显示名称消息键
     * @param themes 主题语义贡献
     * @param configContributions 配置语义贡献
     * @param onboardingSteps 引导步骤语义贡献
     * @param routes 活动路由声明
     * @param navigation 活动导航声明
     */
    public DesktopUiPluginSnapshot {
        id = requireId(id, "id");
        packageId = requireId(packageId, "packageId");
        if (generation < 0L) throw new IllegalArgumentException("generation must not be negative");
        displayNamespace = blankToNull(displayNamespace);
        if (displayNamespace != null) requireId(displayNamespace, "displayNamespace");
        displayNameKey = displayNameKey == null ? "" : displayNameKey.trim();
        themes = List.copyOf(themes == null ? List.of() : themes);
        configContributions = List.copyOf(configContributions == null ? List.of() : configContributions);
        onboardingSteps = List.copyOf(onboardingSteps == null ? List.of() : onboardingSteps);
        routes = List.copyOf(routes == null ? List.of() : routes);
        navigation = List.copyOf(navigation == null ? List.of() : navigation);
    }

    /**
     * @return 用于比较同一插件 publication 身份的稳定指纹
     */
    public Fingerprint fingerprint() {
        return new Fingerprint(id, builtIn, packageId, generation);
    }

    /**
     * @return 保留 owner 与回退值的显示名称文本语义
     */
    public DesktopUiText displayName() {
        String fallback = id;
        return displayNameKey.isBlank()
                ? DesktopUiText.raw(fallback)
                : DesktopUiText.plugin(displayNamespace, displayNameKey, fallback);
    }

    /**
     * 桌面快照用于识别插件 publication 的稳定纯值。
     *
     * @param id 功能插件标识符
     * @param builtIn 是否为内置插件
     * @param packageId 插件包标识符
     * @param generation 插件包物理代际
     */
    public record Fingerprint(String id, boolean builtIn, String packageId, long generation) {
        /**
         * 校验指纹中的标识符与物理代际。
         *
         * @param id 功能插件标识符
         * @param builtIn 是否为内置插件
         * @param packageId 插件包标识符
         * @param generation 插件包物理代际
         */
        public Fingerprint {
            id = requireId(id, "id");
            packageId = requireId(packageId, "packageId");
            if (generation < 0L) throw new IllegalArgumentException("generation must not be negative");
        }
    }

    private static String requireId(String value, String name) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException(name + " must be a stable id");
        }
        return value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
