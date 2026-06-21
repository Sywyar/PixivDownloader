package top.sywyar.pixivdownload.plugin.api.plugin;

/**
 * 插件类别。
 */
public enum PluginKind {

    /** 核心插件：承载应用基础能力的声明，不可禁用。 */
    CORE,

    /** 功能插件：可独立启用/禁用的业务功能。 */
    FEATURE
}
