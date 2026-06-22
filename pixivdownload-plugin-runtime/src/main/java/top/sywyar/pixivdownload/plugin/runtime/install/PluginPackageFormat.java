package top.sywyar.pixivdownload.plugin.runtime.install;

/**
 * 外置插件「可安装包」的内部布局形态。安装器据此决定如何落盘成 PF4J 可加载的产物。
 *
 * <p>两种受支持的布局（{@link top.sywyar.pixivdownload.plugin.runtime.install.PluginPackageReader} 据 zip 根
 * 内容判定，互斥；同时命中两类或都不命中即判为非法包）：
 * <ul>
 *   <li>{@link #EXPLODED_DIRECTORY}：zip 根直接含 PF4J 描述符 {@code plugin.properties}，其余为插件负载
 *       （{@code classes/}、{@code lib/*.jar}、资源）。这就是 PF4J 解压目录形态的插件布局——把这样的 zip 放进
 *       插件目录，PF4J 会原样解压成目录再加载。安装产物为规范命名的 {@code .zip}。</li>
 *   <li>{@link #SINGLE_JAR}：包内只有一个插件 jar（描述符在 jar 内），或上传物本身就是一个插件 jar。安装产物为
 *       规范命名的 {@code .jar}，PF4J 直接按 jar 插件加载。</li>
 * </ul>
 */
public enum PluginPackageFormat {

    /** 解压目录形态：zip 根含 {@code plugin.properties} + {@code classes/} / {@code lib/}。 */
    EXPLODED_DIRECTORY,

    /** 单 jar 形态：包内（zip 根）唯一一个插件 jar，或上传物本身就是插件 jar。 */
    SINGLE_JAR
}
