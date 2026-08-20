package top.sywyar.pixivdownload.gui;

import top.sywyar.pixivdownload.plugin.api.gui.RepositoryConfigEntry;
import top.sywyar.pixivdownload.plugin.api.gui.TrustedKeyConfigEntry;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Iterator;
import java.util.function.Consumer;

/** 仅供宿主拥有的桌面模型消费的应用内部业务操作。 */
public interface DesktopUiHost {
    /** @return 桌面界面显示的产品名称 */
    String applicationName();
    /** @return 公开项目 URL */
    String projectUrl();
    /** @return 公开发布页 URL */
    String releasesUrl();
    /** @return 默认稳定版更新清单 URL */
    String defaultUpdateManifestUrl();
    /** @return 默认 Nightly 更新清单 URL */
    String defaultNightlyUpdateManifestUrl();
    /** @return 宿主拥有的应用配置端口 */
    ConfigFile applicationConfig();
    /**
     * @param pluginId 已验证的插件 id
     * @return 宿主拥有的插件 properties 配置端口
     */
    ConfigFile pluginConfig(String pluginId);
    /** @return 桌面语言选择器中可见的 locale */
    java.util.List<UiLocale> visibleLocales();
    /**
     * @param tag 已持久化的 locale 标签或别名
     * @return 匹配的可见 locale；没有时为空
     */
    java.util.Optional<UiLocale> matchLocale(String tag);
    /**
     * @param requested 请求的 locale
     * @return 解析后的 locale 及从目标语言到源语言的有序回退链
     */
    UiLocaleResolution resolveLocale(java.util.Locale requested);
    /** @return 应用宿主策略后检测到的系统 locale */
    java.util.Locale detectSystemLocale();
    /**
     * 按宿主路径规则移除路径值末尾的分隔符。
     *
     * @param value 路径值
     * @return 不含末尾分隔符的路径值
     */
    String stripTrailingPathSeparators(String value);
    /** @return 默认代理主机 */
    String defaultProxyHost();
    /** @return 默认代理端口 */
    int defaultProxyPort();
    /** @return setup 接受的最小密码长度 */
    int minimumPasswordLength();
    /** @return setup 建议的密码长度 */
    int recommendedPasswordLength();
    /** @return 默认维护时间 */
    String defaultMaintenanceTime();
    /**
     * @param value 维护时间值
     * @return 该值是否有效
     */
    boolean validMaintenanceTime(String value);
    /** @return 宿主保留的仓库 id */
    java.util.Set<String> reservedPluginRepositoryIds();
    /**
     * @param keys 配置键
     * @return 已验证并规范化的键
     * @throws IOException 无法完成验证时抛出
     */
    java.util.Set<String> validatedConfigKeys(java.util.Collection<String> keys) throws java.io.IOException;
    /**
     * @param values 配置值
     * @return 已验证并规范化的值
     * @throws IOException 无法完成验证时抛出
     */
    java.util.Map<String,String> validatedConfigValues(java.util.Map<String,String> values) throws java.io.IOException;
    /**
     * @param key 配置键
     * @return 已验证并规范化的键
     * @throws IOException 键无效时抛出
     */
    String requireSafeConfigKey(String key) throws java.io.IOException;
    /**
     * @param value 配置值
     * @return 已验证并规范化的值
     * @throws IOException 值无效时抛出
     */
    String requireSafeConfigValue(String value) throws java.io.IOException;
    /**
     * 从宿主拥有的配置文件读取结构化插件仓库。
     *
     * @param configFile 宿主拥有的配置文件
     * @return 结构化仓库条目
     * @throws IOException 无法读取配置时抛出
     */
    default List<RepositoryConfigEntry> readPluginRepositories(ConfigFile configFile) throws IOException {
        throw new UnsupportedOperationException("Plugin repository persistence is not supported by this host");
    }
    /**
     * 向宿主拥有的配置文件写入结构化插件仓库。
     *
     * @param configFile 宿主拥有的配置文件
     * @param entries 结构化仓库条目
     * @throws IOException 无法写入配置时抛出
     */
    default void writePluginRepositories(ConfigFile configFile, List<RepositoryConfigEntry> entries) throws IOException {
        throw new UnsupportedOperationException("Plugin repository persistence is not supported by this host");
    }
    /** @return 以纯配置值表示的内置官方仓库信任根 */
    default TrustedKeyConfigEntry officialPluginRepositoryKey() {
        throw new UnsupportedOperationException("The official plugin repository key is not supported by this host");
    }

    /** 宿主拥有的配置持久化端口。 */
    interface ConfigFile {
        /**
         * @param key 配置键
         * @return 已配置的值；不存在时为 {@code null}
         * @throws IOException 无法读取文件时抛出
         */
        default String read(String key) throws java.io.IOException {
            return readAll(java.util.Set.of(key)).get(key);
        }
        /**
         * @param keys 配置键
         * @return 按名称索引的已配置值
         * @throws IOException 无法读取文件时抛出
         */
        java.util.Map<String,String> readAll(java.util.Collection<String> keys) throws java.io.IOException;
        /**
         * @param key 配置键
         * @param value 配置值
         * @throws IOException 无法写入文件时抛出
         */
        default void write(String key,String value) throws java.io.IOException {
            writeAll(java.util.Map.of(key,value == null ? "" : value));
        }
        /**
         * @param values 配置值
         * @throws IOException 无法写入文件时抛出
         */
        void writeAll(java.util.Map<String,String> values) throws java.io.IOException;
        /**
         * @param keys 配置键
         * @throws IOException 无法写入文件时抛出
         */
        void removeAll(java.util.Collection<String> keys) throws java.io.IOException;
        /**
         * @return 用于回滚的精确文件状态
         * @throws IOException 无法读取文件时抛出
         */
        ConfigSnapshot snapshot() throws java.io.IOException;
        /**
         * @param snapshot 要恢复的精确文件状态
         * @throws IOException 无法恢复文件时抛出
         */
        void restore(ConfigSnapshot snapshot) throws java.io.IOException;
    }

    /** 宿主拥有的配置文件的精确逐行快照。 */
    record ConfigSnapshot(boolean existed,java.util.List<String> lines) {
        /**
         * 对快照行执行防御性复制。
         *
         * @param existed 文件是否存在
         * @param lines 精确文件行
         */
        public ConfigSnapshot {
            lines=java.util.List.copyOf(lines == null ? java.util.List.of() : lines);
        }
    }

    /** 桌面 locale 描述符。 */
    record UiLocale(String tag,String nativeName,String resourceSuffix) {
        /** @return 此描述符对应的 JDK locale */
        public java.util.Locale toLocale(){return java.util.Locale.forLanguageTag(tag);}
    }

    /** 已解析的桌面 locale 与回退链。 */
    record UiLocaleResolution(UiLocale target,java.util.List<UiLocale> fallbackChain) {
        /**
         * 对回退链执行防御性复制。
         *
         * @param target 已解析的目标 locale
         * @param fallbackChain 有序回退链
         */
        public UiLocaleResolution {fallbackChain=java.util.List.copyOf(fallbackChain);}
    }

    /** 桌面界面渲染的稳定仓库代理选项。 */
    enum RepositoryProxyPolicy {
        /** 要求 HTTPS 直连。 */
        DIRECT_STRICT("direct-strict"),
        /** 允许使用已配置的受信代理。 */
        PROXY_TRUSTED("proxy-trusted"),
        /** 使用自定义仓库策略。 */
        CUSTOM("custom");
        /** 默认仓库代理策略。 */
        public static final RepositoryProxyPolicy DEFAULT=DIRECT_STRICT;
        private final String configId;
        RepositoryProxyPolicy(String configId){this.configId=configId;}
        /** @return 持久化的策略 id */
        public String configId(){return configId;}
        /**
         * @param raw 持久化的策略 id
         * @return 匹配的策略；没有时返回默认值
         */
        public static RepositoryProxyPolicy fromConfig(String raw){
            if(raw!=null){
                for(RepositoryProxyPolicy policy:values()){
                    if(policy.configId.equalsIgnoreCase(raw.trim())){return policy;}
                }
            }
            return DEFAULT;
        }
    }
    /**
     * 返回本地化的宿主消息。
     *
     * @param code 稳定消息码
     * @param arguments 消息参数
     * @return 本地化消息
     */
    String message(String code, Object... arguments);
    /**
     * 返回应用版本。
     *
     * @return 应用版本
     */
    String applicationVersion();
    /**
     * 返回进程是否由打包后的可执行文件启动。
     *
     * @return 进程是否由可执行文件启动
     */
    boolean launchedFromExecutable();
    /**
     * 返回当前版本是否为 Nightly 构建。
     *
     * @return 当前版本是否为 Nightly
     */
    boolean currentVersionNightly();
    /**
     * 返回调用本地 GUI API 所需的密钥 token。
     *
     * @return 用于 GUI API 的 token
     */
    String guiToken();
    /**
     * 返回承载 GUI token 的 HTTP header 名称。
     *
     * @return GUI token header 名称
     */
    String guiTokenHeader();
    /**
     * 返回应用数据目录。
     *
     * @return 数据目录
     */
    Path dataDirectory();
    /**
     * 返回桌面界面状态目录。
     *
     * @return GUI 状态目录
     */
    Path guiStateDirectory();
    /**
     * 返回公开插件安装目录。
     *
     * @return 插件安装目录
     */
    Path pluginsDirectory();
    /**
     * 返回插件拥有的配置路径。
     *
     * @param pluginId 已验证的插件 id
     * @param extension 不含前导点的文件扩展名
     * @return 插件拥有的配置路径
     */
    Path resolvePluginConfigPath(String pluginId, String extension);
    /**
     * 返回应用根目录对应的图片分类器状态路径。
     *
     * @param rootFolder 应用根目录
     * @return 图片分类器状态路径
     */
    Path resolveImageClassifierPath(String rootFolder);
    /**
     * 返回应用根目录对应的 setup 配置路径。
     *
     * @param rootFolder 应用根目录
     * @return setup 配置路径
     */
    Path resolveSetupConfigPath(String rootFolder);
    /**
     * 返回应用根目录对应的数据库路径。
     *
     * @param rootFolder 应用根目录
     * @return 数据库路径
     */
    Path resolveDatabasePath(String rootFolder);
    /**
     * 返回已配置的下载根目录，缺失时返回给定回退值。
     *
     * @param configPath 宿主配置路径
     * @param defaultRootFolder 回退根目录
     * @return 已配置或回退的下载根目录
     */
    String readDownloadRootFromConfig(Path configPath, String defaultRootFolder);
    /**
     * 返回规范化的应用根目录。
     *
     * @param rootFolder 要规范化的根目录
     * @return 规范化后的根目录
     */
    String normalizeRootFolder(String rootFolder);
    /**
     * 使用操作系统浏览器打开一个受信应用 URI。
     *
     * @param uri 受信应用 URI
     * @throws Exception 操作系统无法打开该 URI 时抛出
     */
    default void openExternalUri(java.net.URI uri) throws Exception {
        throw new UnsupportedOperationException("Opening external URIs is not supported by this host");
    }
    /**
     * 使用操作系统默认应用打开一个受信本地路径。
     *
     * @param path 受信本地路径
     * @throws Exception 操作系统无法打开该路径时抛出
     */
    default void openLocalPath(Path path) throws Exception {
        throw new UnsupportedOperationException("Opening local paths is not supported by this host");
    }

    /**
     * 将纯文本复制到桌面剪贴板。
     *
     * @param text 要复制的文本
     * @throws Exception 桌面剪贴板不可用时抛出
     */
    default void copyText(String text) throws Exception {
        throw new UnsupportedOperationException("The desktop clipboard is not supported by this host");
    }
    /**
     * 返回当前后端生命周期快照。
     *
     * @return 后端快照
     */
    BackendSnapshot backendSnapshot();
    /**
     * 订阅后端生命周期变化。
     *
     * @param listener 生命周期监听器
     * @return 订阅句柄
     */
    AutoCloseable subscribeBackend(Consumer<BackendSnapshot> listener);
    /**
     * 启动后端，并在成功后调用回调。
     *
     * @param afterStart 成功回调
     * @return 操作是否被接受
     */
    boolean startBackend(Runnable afterStart);
    /**
     * 停止后端，并在成功后调用回调。
     *
     * @param afterStop 成功回调
     * @return 操作是否被接受
     */
    boolean stopBackend(Runnable afterStop);
    /**
     * 重启后端，并在成功后调用回调。
     *
     * @param afterRestart 成功回调
     * @return 操作是否被接受
     */
    boolean restartBackend(Runnable afterRestart);
    /**
     * 通过已认证的本地 GUI 端点请求完整重启应用进程。
     *
     * @return 重启请求是否被接受
     */
    default boolean restartApplication() {
        GuiResponse response = guiPostJson("restart", Map.of(), 5000);
        return response.reachable() && response.is2xx();
    }
    /**
     * 请求当前应用进程优雅退出。
     * Renderer 必须把进程所有权交给宿主，不得自行终止 JVM。
     */
    default void requestApplicationExit() {
        throw new UnsupportedOperationException("Application exit is not supported by this host");
    }
    /**
     * 返回是否支持操作系统自动启动。
     *
     * @return 是否支持自动启动
     */
    boolean autoStartSupported();
    /**
     * 返回是否已启用操作系统自动启动。
     *
     * @return 是否已启用自动启动
     */
    boolean autoStartEnabled();
    /**
     * 更新操作系统自动启动状态。
     *
     * @param enabled 请求的状态
     * @throws IOException 无法更新操作系统条目时抛出
     * @throws InterruptedException 更新过程被中断时抛出
     */
    void setAutoStartEnabled(boolean enabled) throws IOException, InterruptedException;
    /**
     * 读取一个插件拥有的凭据。
     *
     * @param ownerPluginId 凭据 owner id
     * @return 已解密的凭据值
     * @throws IOException 无法读取凭据存储时抛出
     */
    Map<String, String> readCredentials(String ownerPluginId) throws IOException;
    /**
     * 应用一个插件的凭据更新。
     *
     * @param ownerPluginId 凭据 owner id
     * @param updates 凭据更新
     * @throws IOException 无法更新凭据存储时抛出
     */
    void updateCredentials(String ownerPluginId, Map<String, String> updates) throws IOException;
    /**
     * 持有所请求的凭据 owner 锁时执行操作。
     *
     * @param ownerPluginIds 凭据 owner id
     * @param operation 要执行的操作
     * @throws IOException 获取锁或执行操作失败时抛出
     */
    void withCredentialLocks(Collection<String> ownerPluginIds, IoOperation operation) throws IOException;
    /**
     * 捕获一个插件拥有的加密凭据文件。
     *
     * @param ownerPluginId 凭据 owner id
     * @return 防御性凭据快照
     * @throws IOException 无法读取凭据存储时抛出
     */
    CredentialSnapshot snapshotCredentials(String ownerPluginId) throws IOException;
    /**
     * 恢复先前捕获的凭据快照。
     *
     * @param ownerPluginId 凭据 owner id
     * @param snapshot 要恢复的快照
     * @throws IOException 无法恢复凭据存储时抛出
     */
    void restoreCredentials(String ownerPluginId, CredentialSnapshot snapshot) throws IOException;
    /**
     * 定位可用的 FFmpeg 安装。
     *
     * @return 找到的可用安装；没有时为空
     */
    Optional<FfmpegInstallation> locateFfmpeg();
    /**
     * 返回为宿主管理的 FFmpeg 安装预留的目录。
     *
     * @return 受管 FFmpeg 目录
     */
    Path managedFfmpegDirectory();
    /**
     * 创建并返回为宿主管理的 FFmpeg 安装预留的目录。
     *
     * @return 已准备的受管 FFmpeg 目录
     * @throws IOException 无法准备目录时抛出
     */
    default Path prepareManagedFfmpegDirectory() throws IOException {
        throw new UnsupportedOperationException("Managed FFmpeg storage is not supported by this host");
    }
    /**
     * 返回此宿主能否安装 FFmpeg。
     *
     * @return 是否支持受管安装
     */
    boolean supportsManagedFfmpegInstall();
    /**
     * 安装宿主管理的 FFmpeg，并报告粗粒度进度。
     *
     * @param proxy 可选下载代理
     * @param listener 进度监听器
     * @return 已安装的 FFmpeg 路径
     * @throws IOException 安装失败时抛出
     * @throws InterruptedException 安装被中断时抛出
     */
    FfmpegInstallation installManagedFfmpeg(FfmpegProxy proxy, FfmpegProgressListener listener) throws IOException, InterruptedException;
    /**
     * 返回当前维护任务快照。
     *
     * @return 维护快照
     */
    MaintenanceSnapshot maintenanceSnapshot();
    /**
     * 返回默认作品元数据回填选项。
     *
     * @return 默认回填选项
     */
    BackfillOptions defaultBackfillOptions();
    /**
     * 返回数据库是否支持请求的回填列。
     *
     * @param column 请求的列
     * @return 是否支持该列
     */
    boolean supportsBackfillColumn(DatabaseColumn column);
    /**
     * 统计作品元数据回填候选项。
     *
     * @param options 回填选项
     * @return 候选项数量
     * @throws Exception 无法检查数据库时抛出
     */
    int countBackfillCandidates(BackfillOptions options) throws Exception;
    /**
     * 执行作品元数据回填。
     *
     * @param options 回填选项
     * @return 聚合结果
     * @throws Exception 回填失败时抛出
     */
    BackfillSummary runBackfill(BackfillOptions options) throws Exception;
    /**
     * 统计旧 JSON 到数据库迁移的候选项。
     *
     * @param options 迁移选项
     * @return 候选项数量
     * @throws Exception 无法检查旧状态时抛出
     */
    int countMigrationCandidates(MigrationOptions options) throws Exception;
    /**
     * 执行旧 JSON 到数据库的迁移。
     *
     * @param options 迁移选项
     * @param reporter 进度报告器
     * @return 聚合结果
     * @throws Exception 迁移失败时抛出
     */
    MigrationSummary runMigration(MigrationOptions options, Consumer<String> reporter) throws Exception;
    /**
     * 为桌面工具打开隔离日志会话。
     *
     * @param stem 稳定日志文件主干名
     * @return 活动日志会话
     * @throws Exception 无法创建日志会话时抛出
     */
    ToolLogSession openToolLog(String stem) throws Exception;

    /**
     * 对宿主拥有的本地 GUI API 执行一次已认证请求。
     *
     * @param request 工具包中立的 GUI 请求
     * @return 工具包中立的 GUI 响应
     */
    default GuiResponse exchangeGui(GuiRequest request) {
        throw new UnsupportedOperationException("Local GUI requests are not supported by this host");
    }

    /**
     * 读取一个本地 GUI 端点。相对路径在 {@code /api/gui/} 下解析。
     *
     * @param path 相对或绝对的本地 GUI 路径
     * @param readTimeoutMillis 读取超时毫秒数
     * @return 工具包中立的 GUI 响应
     */
    default GuiResponse guiGet(String path, int readTimeoutMillis) {
        return exchangeGui(GuiRequest.get(path, readTimeoutMillis));
    }

    /** @return 当前受保护的本机控制中心快照响应 */
    default GuiResponse controlCenterSnapshot() {
        return guiGet("control-center", 2_000);
    }

    /**
     * 向一个本地 GUI 端点提交与 JSON 兼容的 JDK 值。
     *
     * @param path 相对或绝对的本地 GUI 路径
     * @param body 与 JSON 兼容的 JDK 值
     * @param readTimeoutMillis 读取超时毫秒数
     * @return 工具包中立的 GUI 响应
     */
    default GuiResponse guiPostJson(String path, Object body, int readTimeoutMillis) {
        return exchangeGui(GuiRequest.json(path, body, readTimeoutMillis, null));
    }

    /**
     * 携带发现期绑定的 owner header，提交插件拥有的 GUI 动作。
     *
     * @param path 相对或绝对的本地 GUI 路径
     * @param body 与 JSON 兼容的 JDK 值
     * @param readTimeoutMillis 读取超时毫秒数
     * @param ownerPluginId 发现期绑定的 owner 插件 id
     * @return 工具包中立的 GUI 响应
     */
    default GuiResponse guiPostJson(String path, Object body, int readTimeoutMillis, String ownerPluginId) {
        return exchangeGui(GuiRequest.json(path, body, readTimeoutMillis, ownerPluginId));
    }

    /**
     * 向一个本地 GUI 端点发送表单请求。
     *
     * @param method HTTP 方法
     * @param path 相对或绝对的本地 GUI 路径
     * @param body 表单编码正文；没有时为 {@code null}
     * @param readTimeoutMillis 读取超时毫秒数
     * @return 工具包中立的 GUI 响应
     */
    default GuiResponse guiForm(String method, String path, String body, int readTimeoutMillis) {
        return exchangeGui(GuiRequest.form(method, path, body, readTimeoutMillis));
    }

    /**
     * 返回宿主拥有的引导持久化快照。
     *
     * @param rootFolder 应用根目录
     * @return 引导状态
     */
    default OnboardingSnapshot onboardingState(String rootFolder) {
        throw new UnsupportedOperationException("Onboarding state is not supported by this host");
    }

    /**
     * @param step 当前引导页索引
     * @return 状态是否已持久化
     */
    default boolean saveOnboardingProgress(int step) { return false; }
    /** @return 状态是否已持久化 */
    default boolean markOnboardingSeen() { return false; }
    /** @return 状态是否已持久化 */
    default boolean markOnboardingProxyConfigured() { return false; }
    /** @return 状态是否已持久化 */
    default boolean markOnboardingFinished() { return false; }
    /** @return 状态是否已清除 */
    default boolean clearOnboardingState() { return false; }

    /** 本地 GUI 传输支持的请求正文编码。 */
    enum GuiBodyFormat {
        /** 请求没有正文。 */ NONE,
        /** 请求正文是与 JSON 兼容的 JDK 值。 */ JSON,
        /** 请求正文采用表单编码。 */ FORM
    }

    /** 工具包中立的本地 GUI 请求。 */
    record GuiRequest(String method, String path, Object body, GuiBodyFormat bodyFormat,
                      int readTimeoutMillis, int maxResponseBytes, String ownerPluginId,
                      String languageTag) {
        private static final int MAX_GET_BYTES = 1024 * 1024;
        private static final int MAX_POST_BYTES = 64 * 1024;

        /**
         * 验证并规范化一个本地 GUI 请求。
         *
         * @param method HTTP 方法
         * @param path 相对或绝对的本地 GUI 路径
         * @param body 请求正文
         * @param bodyFormat 请求正文格式
         * @param readTimeoutMillis 读取超时毫秒数
         * @param maxResponseBytes 接受的最大响应大小
         * @param ownerPluginId 发现期绑定的 owner 插件 id；没有时为空
         * @param languageTag 请求的响应语言
         */
        public GuiRequest {
            method = method == null ? "GET" : method.trim().toUpperCase(java.util.Locale.ROOT);
            if (!method.equals("GET") && !method.equals("POST")) {
                throw new IllegalArgumentException("Only GET and POST are supported");
            }
            String requestedPath = path == null ? "" : path.trim();
            path = requestedPath.startsWith("/api/gui/")
                    ? requestedPath
                    : "/api/gui/" + requestedPath.replaceFirst("^/+", "");
            if (path.contains("..") || path.indexOf('\r') >= 0 || path.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("Unsafe local GUI path");
            }
            bodyFormat = bodyFormat == null ? GuiBodyFormat.NONE : bodyFormat;
            readTimeoutMillis = Math.max(1, readTimeoutMillis);
            maxResponseBytes = Math.max(1, maxResponseBytes);
            ownerPluginId = ownerPluginId == null || ownerPluginId.isBlank() ? null : ownerPluginId.trim();
            languageTag = languageTag == null || languageTag.isBlank()
                    ? java.util.Locale.getDefault().toLanguageTag()
                    : languageTag;
        }

        /**
         * @param path 相对或绝对的本地 GUI 路径
         * @param timeout 读取超时毫秒数
         * @return 有界 GET 请求
         */
        public static GuiRequest get(String path, int timeout) {
            return new GuiRequest("GET", path, null, GuiBodyFormat.NONE, timeout, MAX_GET_BYTES, null, null);
        }
        /**
         * @param path 相对或绝对的本地 GUI 路径
         * @param body 与 JSON 兼容的 JDK 值
         * @param timeout 读取超时毫秒数
         * @param ownerPluginId 发现期绑定的 owner 插件 id；没有时为空
         * @return 有界 JSON POST 请求
         */
        public static GuiRequest json(String path, Object body, int timeout, String ownerPluginId) {
            return new GuiRequest("POST", path, body, GuiBodyFormat.JSON, timeout, MAX_POST_BYTES,
                    ownerPluginId, null);
        }
        /**
         * @param method HTTP 方法
         * @param path 相对或绝对的本地 GUI 路径
         * @param body 表单编码正文；没有时为 {@code null}
         * @param timeout 读取超时毫秒数
         * @return 有界表单请求
         */
        public static GuiRequest form(String method, String path, String body, int timeout) {
            return new GuiRequest(method, path, body, body == null ? GuiBodyFormat.NONE : GuiBodyFormat.FORM,
                    timeout, MAX_POST_BYTES, null, null);
        }
    }

    /** 一次本地 GUI 请求的可达性、HTTP 状态与解析后响应。 */
    record GuiResponse(boolean reachable, int status, GuiValue body, String rawBody,
                       boolean bodyLimitExceeded) {
        /**
         * 规范化缺失的原始响应正文。
         *
         * @param reachable 是否已到达本地端点
         * @param status HTTP 状态；不可达时为零
         * @param body 可用时的解析后响应正文
         * @param rawBody 原始响应正文
         * @param bodyLimitExceeded 响应是否超过大小上限
         */
        public GuiResponse { rawBody = rawBody == null ? "" : rawBody; }
        /** @return 响应是否为 2xx 状态 */
        public boolean is2xx() { return status >= 200 && status < 300; }
        /** @return 响应状态是否为 200 */
        public boolean successful() { return status == 200; }
        /** @return 是否已解析响应正文 */
        public boolean responseParsed() { return body != null; }
        /** @return 表示本地端点不可达的响应 */
        public static GuiResponse unreachable() { return new GuiResponse(false, 0, null, "", false); }
    }

    /** 仅由 JDK map、list 和标量类型支持的只读 JSON 值。 */
    final class GuiValue implements Iterable<GuiValue> {
        private static final Object MISSING = new Object();
        private static final GuiValue MISSING_VALUE = new GuiValue(MISSING);
        private final Object value;
        private GuiValue(Object value) { this.value = value; }
        /**
         * @param value JDK map、list、标量或 {@code null}
         * @return 只读 GUI 值
         */
        public static GuiValue of(Object value) { return new GuiValue(value); }
        /**
         * @param field 对象字段名
         * @return 字段值或缺失哨兵
         */
        public GuiValue path(String field) {
            if (value instanceof Map<?, ?> map && map.containsKey(field)) return of(map.get(field));
            return MISSING_VALUE;
        }
        /**
         * @param index 数组索引
         * @return 索引值或缺失哨兵
         */
        public GuiValue path(int index) {
            if (value instanceof List<?> list && index >= 0 && index < list.size()) return of(list.get(index));
            return MISSING_VALUE;
        }
        /**
         * @param field 对象字段名
         * @return 字段值；缺失时为 {@code null}
         */
        public GuiValue get(String field) {
            return value instanceof Map<?, ?> map && map.containsKey(field) ? of(map.get(field)) : null;
        }
        /**
         * @param field 对象字段名
         * @return 是否存在非 null 字段
         */
        public boolean hasNonNull(String field) {
            GuiValue child = get(field);
            return child != null && !child.isNull();
        }
        /** @return 此值是否为缺失哨兵 */
        public boolean isMissingNode() { return value == MISSING; }
        /** @return 此值是否为 JSON null */
        public boolean isNull() { return value == null; }
        /** @return 此值是否为数组 */
        public boolean isArray() { return value instanceof List<?>; }
        /** @return 此值是否为对象 */
        public boolean isObject() { return value instanceof Map<?, ?>; }
        /** @return 此值是否为布尔值 */
        public boolean isBoolean() { return value instanceof Boolean; }
        /** @return 此值是否为数值 */
        public boolean isNumber() { return value instanceof Number; }
        /** @return 此值是否为文本 */
        public boolean isTextual() { return value instanceof String; }
        /** @return 此值是否为标量或 JSON null */
        public boolean isValueNode() {
            return value == null || value instanceof String || value instanceof Number || value instanceof Boolean;
        }
        /** @return 此集合或文本值是否为空 */
        public boolean isEmpty() {
            if (value instanceof List<?> list) return list.isEmpty();
            if (value instanceof Map<?, ?> map) return map.isEmpty();
            if (value instanceof String text) return text.isEmpty();
            return false;
        }
        /** @return 文本形式的标量值；非标量时为空字符串 */
        public String asText() { return asText(""); }
        /**
         * @param fallback 非标量或 null 值的回退值
         * @return 文本形式的标量值；不可用时返回回退值
         */
        public String asText(String fallback) {
            return isValueNode() && value != null ? String.valueOf(value) : fallback;
        }
        /** @return 布尔形式的值；不可用时为 {@code false} */
        public boolean asBoolean() { return asBoolean(false); }
        /**
         * @param fallback 非布尔值的回退值
         * @return 布尔形式的值；不可用时返回回退值
         */
        public boolean asBoolean(boolean fallback) {
            if (value instanceof Boolean bool) return bool;
            if (value instanceof String text) {
                if ("true".equalsIgnoreCase(text)) return true;
                if ("false".equalsIgnoreCase(text)) return false;
            }
            return fallback;
        }
        /** @return 整数形式的值；不可用时为零 */
        public int asInt() { return asInt(0); }
        /**
         * @param fallback 非整数值的回退值
         * @return 整数形式的值；不可用时返回回退值
         */
        public int asInt(int fallback) {
            if (value instanceof Number number) return number.intValue();
            try { return value == null ? fallback : Integer.parseInt(String.valueOf(value)); }
            catch (NumberFormatException ignored) { return fallback; }
        }
        /** @return long 整数形式的值；不可用时为零 */
        public long asLong() { return asLong(0L); }
        /**
         * @param fallback 非整数值的回退值
         * @return long 整数形式的值；不可用时返回回退值
         */
        public long asLong(long fallback) {
            if (value instanceof Number number) return number.longValue();
            try { return value == null ? fallback : Long.parseLong(String.valueOf(value)); }
            catch (NumberFormatException ignored) { return fallback; }
        }
        /** @return 数组值迭代器；其它类型返回空迭代器 */
        @Override public Iterator<GuiValue> iterator() {
            if (!(value instanceof List<?> list)) return java.util.Collections.emptyIterator();
            return list.stream().map(GuiValue::of).iterator();
        }
    }

    /** 已持久化的引导状态及 setup 完成状态。 */
    record OnboardingSnapshot(boolean seen, boolean proxyConfigured, int progress,
                              boolean finished, boolean setupComplete) {
        /** @return 引导与 setup 是否均已完成 */
        public boolean complete() { return finished && setupComplete; }
    }

    /** 向桌面 provider 暴露的稳定后端生命周期状态。 */
    enum BackendState {
        /** 后端已停止。 */
        STOPPED,
        /** 后端正在启动。 */
        STARTING,
        /** 后端正在运行。 */
        RUNNING,
        /** 后端正在停止。 */
        STOPPING,
        /** 后端启动或运行失败。 */
        FAILED
    }
    /**
     * 后端生命周期状态及最近一次失败（如有）。
     *
     * @param state 当前生命周期状态
     * @param lastError 最近一次生命周期失败；没有时为空
     */
    record BackendSnapshot(BackendState state, Throwable lastError) {}
    /**
     * 一个插件加密凭据文件的防御性快照。
     *
     * @param existed 凭据文件是否存在
     * @param content 加密文件字节
     */
    record CredentialSnapshot(boolean existed, byte[] content) {
        /**
         * 构造时复制凭据字节。
         *
         * @param existed 凭据文件是否存在
         * @param content 加密文件字节
         */
        public CredentialSnapshot { content = content == null ? new byte[0] : content.clone(); }
        /**
         * 返回已捕获凭据字节的副本。
         *
         * @return 复制后的凭据字节
         */
        @Override public byte[] content() { return content.clone(); }
    }
    /**
     * FFmpeg 安装的路径与来源元数据。
     *
     * @param ffmpegPath FFmpeg 可执行文件路径
     * @param ffprobePath FFprobe 可执行文件路径
     * @param homeDir 安装主目录
     * @param source 安装来源
     */
    record FfmpegInstallation(Path ffmpegPath, Path ffprobePath, Path homeDir, FfmpegSource source) {}
    /** FFmpeg 安装的稳定来源。 */
    enum FfmpegSource {
        /** 安装在宿主管理的存储中。 */ MANAGED,
        /** 随应用分发包提供。 */ BUNDLED,
        /** 从操作系统发现。 */ SYSTEM
    }
    /**
     * 仅用于受管 FFmpeg 下载的代理设置。
     *
     * @param enabled 是否启用代理
     * @param host 代理主机
     * @param port 代理端口
     */
    record FfmpegProxy(boolean enabled, String host, int port) {
        /**
         * 将缺失的代理主机规范化为空字符串。
         *
         * @param enabled 是否启用代理
         * @param host 代理主机
         * @param port 代理端口
         */
        public FfmpegProxy { host = host == null ? "" : host.trim(); }
    }
    /** 安装受管 FFmpeg 时报告的稳定阶段。 */
    enum FfmpegInstallStage {
        /** 正在建立下载连接。 */ CONNECTING,
        /** 正在下载归档。 */ DOWNLOADING,
        /** 正在解压已下载的归档。 */ EXTRACTING,
        /** 受管安装已完成。 */ COMPLETED
    }
    /** 接收安装受管 FFmpeg 时的粗粒度进度。 */
    @FunctionalInterface interface FfmpegProgressListener {
        /**
         * 报告当前阶段和工作单元。
         *
         * @param stage 稳定进度阶段
         * @param current 已完成单元数
         * @param total 总单元数；未知时为零
         */
        void onProgress(FfmpegInstallStage stage, long current, long total);
    }
    /**
     * 桌面回填工具请求的数据库列。
     *
     * @param tableName 表名
     * @param columnName 列名
     */
    record DatabaseColumn(String tableName, String columnName) {}
    /**
     * 用户选择的作品元数据回填选项。
     *
     * @param dbPath 数据库路径
     * @param proxyHost 代理主机
     * @param proxyPort 代理端口
     * @param useProxy 是否启用代理
     * @param delayMs 请求间隔毫秒数
     * @param limit 最大候选项数量
     * @param dryRun 是否避免持久化写入
     */
    record BackfillOptions(String dbPath, String proxyHost, int proxyPort, boolean useProxy, long delayMs, int limit, boolean dryRun) {}
    /**
     * 一次作品元数据回填的聚合结果。
     *
     * @param totalCandidates 候选项总数
     * @param processed 已处理数量
     * @param filledAuthor 已填充作者值数量
     * @param filledR18 已填充成人标记数量
     * @param filledAi 已填充 AI 标记数量
     * @param filledDescription 已填充描述数量
     * @param filledTags 已填充标签集数量
     * @param filledSeries 已填充系列值数量
     * @param deletedCount 观察到的已删除记录数量
     * @param skipped 已跳过数量
     * @param previouslyUnreachable 先前不可达数量
     * @param newlyUnreachable 新增不可达数量
     * @param dryRun 是否禁用持久化写入
     * @param rateLimited 是否因限流停止
     */
    record BackfillSummary(int totalCandidates, int processed, int filledAuthor, int filledR18, int filledAi,
                           int filledDescription, int filledTags, int filledSeries, int deletedCount, int skipped,
                           int previouslyUnreachable, int newlyUnreachable, boolean dryRun, boolean rateLimited) {}
    /**
     * 用户选择的旧数据迁移路径。
     *
     * @param dbPath 目标数据库路径
     * @param rootFolder 旧数据根目录
     */
    record MigrationOptions(String dbPath, String rootFolder) {}
    /**
     * 一次旧数据迁移的聚合结果。
     *
     * @param totalCandidates 候选项总数
     * @param migrated 已迁移数量
     * @param skipped 已跳过数量
     * @param historyFileMissing 旧历史文件是否缺失
     * @param message 摘要消息
     */
    record MigrationSummary(int totalCandidates, int migrated, int skipped, boolean historyFileMissing, String message) {}
    /**
     * 活动维护任务的只读进度快照。
     *
     * @param active 维护是否活动
     * @param trigger 触发器标识符
     * @param index 当前任务索引
     * @param total 任务总数
     * @param taskName 当前任务名称
     * @param taskStartedAt 任务开始时间的 epoch 毫秒值
     * @param unitsDone 已完成工作单元数
     * @param unitsTotal 工作单元总数
     */
    record MaintenanceSnapshot(boolean active, String trigger, int index, int total, String taskName,
                               long taskStartedAt, int unitsDone, int unitsTotal) {}
    /** 持有宿主锁时执行的 I/O 操作。 */
    @FunctionalInterface interface IoOperation {
        /**
         * 执行操作。
         *
         * @throws IOException 操作失败时抛出
         */
        void run() throws IOException;
    }
    /** 一次桌面工具调用拥有的隔离 HTML 日志会话。 */
    interface ToolLogSession extends AutoCloseable {
        /**
         * 返回稳定的最新日志路径。
         *
         * @return 最新日志路径
         */
        Path latestPath();
        /**
         * 返回本次调用的不可变日志路径。
         *
         * @return 本次调用的日志路径
         */
        Path sessionPath();
        /**
         * 在系统浏览器中打开最新日志。
         *
         * @throws Exception 无法打开浏览器时抛出
         */
        void openLatestInBrowser() throws Exception;
        /** 关闭日志会话。 */
        @Override void close();
    }

    /**
     * 检查所选数据库中记录的作品目录。
     *
     * @param databasePath SQLite 数据库路径
     * @return 活动作总数和不可访问条目
     * @throws Exception 无法读取数据库时抛出
     */
    default FolderCheckResult checkArtworkFolders(Path databasePath) throws Exception {
        throw new UnsupportedOperationException("Artwork folder checking is not supported by this host");
    }

    /**
     * 更新一个作品的原始目录或移动后目录。
     *
     * @param databasePath SQLite 数据库路径
     * @param artworkId 作品标识符
     * @param moved 是否选择移动后目录列
     * @param newPath 替换目录路径
     * @throws Exception 更新失败时抛出
     */
    default void updateArtworkFolder(Path databasePath, long artworkId, boolean moved, String newPath) throws Exception {
        throw new UnsupportedOperationException("Artwork folder updates are not supported by this host");
    }

    /**
     * 加载已持久化的图片分类器设置。
     *
     * @param rootFolder 应用下载根目录
     * @return 分类器设置
     * @throws IOException 无法读取设置时抛出
     */
    default ImageClassifierSettings loadImageClassifierSettings(String rootFolder) throws IOException {
        throw new UnsupportedOperationException("Image classifier settings are not supported by this host");
    }

    /**
     * 持久化图片分类器设置。
     *
     * @param rootFolder 应用下载根目录
     * @param settings 要持久化的设置
     * @throws IOException 无法写入设置时抛出
     */
    default void saveImageClassifierSettings(String rootFolder, ImageClassifierSettings settings) throws IOException {
        throw new UnsupportedOperationException("Image classifier settings are not supported by this host");
    }

    /**
     * 检查分类器路径是否为现存目录。
     *
     * @param path 要检查的路径
     * @return 该路径是否为现存目录
     */
    default boolean isImageClassifierDirectory(Path path) {
        throw new UnsupportedOperationException("Image classifier paths are not supported by this host");
    }

    /**
     * 按显示顺序列出分类器工作目录。
     *
     * @param parent 父目录
     * @return 有序子目录
     * @throws IOException 无法读取目录时抛出
     */
    default List<Path> listImageClassifierFolders(Path parent) throws IOException {
        throw new UnsupportedOperationException("Image classifier folders are not supported by this host");
    }

    /**
     * 列出一个分类器工作目录中支持的图片。
     *
     * @param folder 工作目录
     * @return 有序图片路径
     * @throws IOException 无法读取目录时抛出
     */
    default List<Path> listImageClassifierImages(Path folder) throws IOException {
        throw new UnsupportedOperationException("Image classifier images are not supported by this host");
    }

    /**
     * 仅在分类器工作目录为空时删除它。
     *
     * @param folder 工作目录
     * @throws IOException 无法检查或删除目录时抛出
     */
    default void deleteImageClassifierFolderIfEmpty(Path folder) throws IOException {
        throw new UnsupportedOperationException("Image classifier cleanup is not supported by this host");
    }

    /**
     * 解析已配置的分类器服务，包括 HTTP/HTTPS 回退。
     *
     * @param configuredUrl 已配置的服务 URL
     * @return 可用性和实际响应的 URL
     */
    default ImageClassifierServer checkImageClassifierServer(String configuredUrl) {
        throw new UnsupportedOperationException("Image classifier server checks are not supported by this host");
    }

    /**
     * 解析分类器目录对应的作品身份与可选元数据。
     *
     * @param folder 分类器工作目录
     * @param server 先前解析的服务状态
     * @return 作品元数据；无法解析出正数作品 ID 时为空
     */
    default Optional<ImageClassifierArtwork> resolveImageClassifierArtwork(Path folder, ImageClassifierServer server) {
        throw new UnsupportedOperationException("Image classifier artwork lookup is not supported by this host");
    }

    /**
     * 复制、移除并记录一个已分类作品。
     *
     * <p>复制失败会回滚并抛出异常。源文件删除失败会报告给
     * {@code deleteFailureHandler}；返回 {@code true} 会重试删除，返回
     * {@code false} 则保留已复制的目标文件及其余源文件。</p>
     *
     * @param sourceFolder 源工作目录
     * @param images 源图片
     * @param artworkId 作品标识符
     * @param targetFolder 选择的分类目标目录
     * @param server 先前解析的服务状态
     * @param deleteFailureHandler 源文件删除失败时的用户决策回调
     * @return 实际目标目录
     * @throws IOException 创建目标目录或复制失败时抛出
     */
    default Path classifyImageFolder(Path sourceFolder, List<Path> images, long artworkId, Path targetFolder,
                                     ImageClassifierServer server,
                                     ImageClassifierDeleteFailureHandler deleteFailureHandler) throws IOException {
        throw new UnsupportedOperationException("Image classification is not supported by this host");
    }

    /**
     * 目录检查器报告的作品目录。
     *
     * @param artworkId 作品标识符
     * @param title 作品标题
     * @param path 不可访问路径；可能为 {@code null}
     * @param moved 路径是否来自移动后目录列
     */
    record FolderArtwork(long artworkId, String title, String path, boolean moved) {}

    /**
     * 目录检查的聚合结果。
     *
     * @param total 活动作总数
     * @param inaccessible 不可访问的作品目录
     */
    record FolderCheckResult(int total, List<FolderArtwork> inaccessible) {
        /**
         * 复制结果列表，防止调用方修改宿主状态。
         *
         * @param total 活动作总数
         * @param inaccessible 不可访问的作品目录
         */
        public FolderCheckResult {
            inaccessible = inaccessible == null ? List.of() : List.copyOf(inaccessible);
        }
    }

    /**
     * 一个已配置的分类器目标。
     *
     * @param folder 目标目录
     * @param remark 用户可见备注
     */
    record ImageClassifierTarget(String folder, String remark) {}

    /**
     * 已持久化的分类器设置。
     *
     * @param defaultFolder 默认源父目录
     * @param showSkipButton 是否显示跳过按钮
     * @param serverUrl 已配置的后端 URL
     * @param targets 分类器目标
     */
    record ImageClassifierSettings(String defaultFolder, boolean showSkipButton, String serverUrl,
                                   List<ImageClassifierTarget> targets) {
        /**
         * 规范化可为 null 的标量值并复制目标列表。
         *
         * @param defaultFolder 默认源父目录
         * @param showSkipButton 是否显示跳过按钮
         * @param serverUrl 已配置的后端 URL
         * @param targets 分类器目标
         */
        public ImageClassifierSettings {
            defaultFolder = defaultFolder == null ? "" : defaultFolder;
            serverUrl = serverUrl == null || serverUrl.isBlank() ? "http://localhost:6999" : serverUrl.trim();
            targets = targets == null ? List.of() : List.copyOf(targets);
        }
    }

    /**
     * 已解析的分类器服务状态。
     *
     * @param available 服务是否成功响应
     * @param url 已配置或成功解析的 URL
     */
    record ImageClassifierServer(boolean available, String url) {}

    /**
     * 分类器视图使用的作品元数据。
     *
     * @param artworkId 作品标识符
     * @param title 可选标题
     * @param xRestrict 可选 Pixiv 限制值
     */
    record ImageClassifierArtwork(long artworkId, String title, Integer xRestrict) {}

    /** 接收源文件删除失败，并返回是否应重试删除。 */
    @FunctionalInterface
    interface ImageClassifierDeleteFailureHandler {
        /**
         * 处理一次删除失败。
         *
         * @param detail 失败详情
         * @param sourceFolder 仍存在的源目录
         * @return {@code true} 表示重试，{@code false} 表示保留其余源文件
         */
        boolean retry(String detail, Path sourceFolder);
    }
}
