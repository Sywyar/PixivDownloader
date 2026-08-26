package top.sywyar.pixivdownload.plugin.api.gui;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 进程生命周期内提供给选中桌面 GUI 的稳定业务上下文。
 * 页面、控件、对话框和短暂交互状态均由 GUI provider 自己拥有。
 */
public final class DesktopUiContext {
    private final boolean startupLaunch;
    private final int serverPort;
    private final String rootFolder;
    private final Path configPath;
    private final String selectedProviderId;
    private final DesktopUiHost host;
    private final List<DesktopUiPluginSnapshot> startupPlugins;
    private final Supplier<List<DesktopUiPluginSnapshot>> currentPlugins;
    private final Function<DesktopUiText, String> textResolver;
    private final Supplier<String> themePreference;

    /**
     * 创建一个只在当前桌面进程中使用的业务上下文。
     *
     * @param startupLaunch 是否由应用启动流程打开桌面界面
     * @param serverPort 本地服务端口
     * @param rootFolder 下载根目录
     * @param configPath 主配置文件路径
     * @param host 工具包无关的宿主业务能力
     * @param startupPlugins 启动时已经冻结的活动插件快照
     * @param currentPlugins 当前活动插件快照读取器
     * @param textResolver 本地化文本解析器
     * @param themePreference 当前共享主题偏好读取器
     */
    public DesktopUiContext(
            boolean startupLaunch,
            int serverPort,
            String rootFolder,
            Path configPath,
            DesktopUiHost host,
            List<DesktopUiPluginSnapshot> startupPlugins,
            Supplier<List<DesktopUiPluginSnapshot>> currentPlugins,
            Function<DesktopUiText, String> textResolver,
            Supplier<String> themePreference
    ) {
        this(startupLaunch, serverPort, rootFolder, configPath, "", host, startupPlugins,
                currentPlugins, textResolver, themePreference);
    }

    /**
     * 创建带当前实际提供者标识的桌面业务上下文。
     *
     * @param startupLaunch 是否由应用启动流程打开桌面界面
     * @param serverPort 本地服务端口
     * @param rootFolder 下载根目录
     * @param configPath 主配置文件路径
     * @param selectedProviderId 本次启动实际选中的桌面 UI 提供者 id
     * @param host 工具包无关的宿主业务能力
     * @param startupPlugins 启动时已经冻结的活动插件快照
     * @param currentPlugins 当前活动插件快照读取器
     * @param textResolver 本地化文本解析器
     * @param themePreference 当前共享主题偏好读取器
     */
    public DesktopUiContext(
            boolean startupLaunch,
            int serverPort,
            String rootFolder,
            Path configPath,
            String selectedProviderId,
            DesktopUiHost host,
            List<DesktopUiPluginSnapshot> startupPlugins,
            Supplier<List<DesktopUiPluginSnapshot>> currentPlugins,
            Function<DesktopUiText, String> textResolver,
            Supplier<String> themePreference
    ) {
        if (serverPort < 1 || serverPort > 65_535) {
            throw new IllegalArgumentException("serverPort out of range");
        }
        this.startupLaunch = startupLaunch;
        this.serverPort = serverPort;
        this.rootFolder = Objects.requireNonNull(rootFolder, "rootFolder");
        this.configPath = Objects.requireNonNull(configPath, "configPath");
        this.selectedProviderId = selectedProviderId == null ? "" : selectedProviderId.trim();
        this.host = Objects.requireNonNull(host, "host");
        this.startupPlugins = List.copyOf(Objects.requireNonNull(startupPlugins, "startupPlugins"));
        this.currentPlugins = Objects.requireNonNull(currentPlugins, "currentPlugins");
        this.textResolver = Objects.requireNonNull(textResolver, "textResolver");
        this.themePreference = Objects.requireNonNull(themePreference, "themePreference");
        currentPluginSnapshots();
    }

    /**
     * @return 是否由应用启动流程打开桌面界面
     */
    public boolean startupLaunch() {
        return startupLaunch;
    }

    /**
     * @return 本地服务端口
     */
    public int serverPort() {
        return serverPort;
    }

    /**
     * @return 下载根目录
     */
    public String rootFolder() {
        return rootFolder;
    }

    /**
     * @return 主配置文件路径
     */
    public Path configPath() {
        return configPath;
    }

    /**
     * @return 本次启动实际选中的桌面 UI 提供者 id
     */
    public String selectedProviderId() {
        return selectedProviderId;
    }

    /**
     * @return 工具包无关的宿主业务能力
     */
    public DesktopUiHost host() {
        return host;
    }

    /**
     * @return 启动时已经冻结的活动插件快照
     */
    public List<DesktopUiPluginSnapshot> startupPluginSnapshots() {
        return startupPlugins;
    }

    /**
     * @return 当前活动插件的不可变快照
     */
    public List<DesktopUiPluginSnapshot> currentPluginSnapshots() {
        List<DesktopUiPluginSnapshot> snapshots = currentPlugins.get();
        return List.copyOf(Objects.requireNonNull(snapshots, "currentPlugins returned null"));
    }

    /**
     * 通过宿主拥有的资源解析一条本地化文本语义。
     *
     * @param text 待解析的文本语义
     * @return 当前语言下的显示文本
     */
    public String resolveText(DesktopUiText text) {
        return Objects.requireNonNull(textResolver.apply(Objects.requireNonNull(text, "text")),
                "textResolver returned null");
    }

    /**
     * 请求宿主有序退出当前应用进程。
     */
    public void requestApplicationExit() {
        host.requestApplicationExit();
    }

    /**
     * @return 规范化后的共享主题偏好；缺失时为 {@code system}
     */
    public String themePreference() {
        String value = themePreference.get();
        return value == null || value.isBlank()
                ? "system"
                : value.trim().toLowerCase(Locale.ROOT);
    }
}
