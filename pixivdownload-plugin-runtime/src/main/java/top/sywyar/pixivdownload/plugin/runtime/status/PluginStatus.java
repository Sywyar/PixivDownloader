package top.sywyar.pixivdownload.plugin.runtime.status;

/**
 * 插件状态模型：描述一个插件在核心壳眼中的生命周期 / 健康状态。状态由
 * {@link PluginStatusEvaluator} 综合「运行时观测到的生命周期 + 描述符校验 + 核心 API 兼容 + 依赖可达性 +
 * 必选插件策略」推导，可由后端服务查询，不依赖任何 UI。
 *
 * <p>前八个是一个具体插件实例的生命周期 / 健康态；后两个描述「被要求但不可用」——其要求来源既可以是某插件声明的
 * <b>非可选依赖</b>，也可以是核心 / 发行 / catalog 策略对某 pluginId 的<b>必选要求</b>（{@link RequiredPluginPolicy}）。
 * 两类要求共用同一对状态语义。
 */
public enum PluginStatus {

    /** 已安装：插件包在场（磁盘 / 注册中心），尚未解析。 */
    INSTALLED,

    /** 已解析：描述符已解析且校验通过、依赖可解析，可进入加载。 */
    RESOLVED,

    /** 已加载：插件类已加载、实例已创建，但尚未启动。 */
    LOADED,

    /** 已启动：插件处于活动状态、其贡献已生效。 */
    STARTED,

    /** 已停止：曾启动、现已停止（生命周期停止，区别于从未启动）。 */
    STOPPED,

    /** 已禁用：经开关 / 策略禁用，不会启动（区别于运行期停止）。 */
    DISABLED,

    /** 失败：加载 / 启动出错，或描述符非法（缺字段 / 非法字段）。 */
    FAILED,

    /** 不兼容：插件声明的核心 API 版本要求（{@code requires}）不被当前核心满足。 */
    INCOMPATIBLE,

    /** 缺少必需项：某非可选依赖或某必选 pluginId 未安装（依赖方因此不可启动）。 */
    MISSING_REQUIRED,

    /** 必需项不兼容：某非可选依赖或某必选 pluginId 已安装但版本 / API 不兼容（依赖方因此不可启动）。 */
    INCOMPATIBLE_REQUIRED;

    /** 是否为「插件不可用 / 不健康」状态（非 {@link #STARTED} 的健康活动态）。 */
    public boolean isHealthy() {
        return this == STARTED;
    }

    /** 是否为「被要求但不可用」状态（依赖 / 必选策略未满足）。 */
    public boolean isUnmetRequirement() {
        return this == MISSING_REQUIRED || this == INCOMPATIBLE_REQUIRED;
    }
}
