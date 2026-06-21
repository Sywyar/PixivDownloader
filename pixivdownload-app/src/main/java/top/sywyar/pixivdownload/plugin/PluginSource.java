package top.sywyar.pixivdownload.plugin;

/**
 * 插件来源：{@link PluginRegistry} 区分内置插件与外置插件两类来源，用于诊断、冲突报告与导航排序等场景。
 *
 * <ul>
 *   <li>{@link #BUILT_IN}：随主程序编译进 boot jar、由各 {@code XxxPluginConfiguration} 装配的内置插件，
 *       其类与资源由应用 classloader 加载。</li>
 *   <li>{@link #EXTERNAL}：从 {@code plugins/} 目录加载的外置插件，其类与资源由各自插件 classloader 加载
 *       （经发现桥接接入，见 {@code PixivPluginDiscoveryBridge}）。</li>
 * </ul>
 */
public enum PluginSource {

    /** 内置插件（随 boot jar 携带，应用 classloader 加载）。 */
    BUILT_IN,

    /** 外置插件（{@code plugins/} 目录加载，插件 classloader 加载）。 */
    EXTERNAL
}
