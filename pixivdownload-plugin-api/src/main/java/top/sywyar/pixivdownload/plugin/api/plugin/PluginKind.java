package top.sywyar.pixivdownload.plugin.api.plugin;

/**
 * 插件类别。
 */
public enum PluginKind {

    /**
     * 核心类别：承载应用基础能力的声明。只有宿主以 {@code BUILT_IN} 来源注册的 CORE 才结构性不可禁用；
     * 外置插件声明本类别不会获得必选性，外置必选事实仍只能由宿主 {@code RequiredPluginPolicy} 提出。
     */
    CORE,

    /** 功能类别：通常受插件开关控制；是否被宿主列为必选不由本枚举决定。 */
    FEATURE
}
