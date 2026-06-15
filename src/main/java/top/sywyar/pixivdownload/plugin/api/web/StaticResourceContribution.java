package top.sywyar.pixivdownload.plugin.api.web;

/**
 * 插件声明的静态资源目录。资源解析必须经声明方插件的 ClassLoader
 * （由注册中心按 {@code pluginId} 解析），不做全局 {@code classpath:} 扫描假设。
 * <p>
 * 本记录只描述 serving（字节从哪个 classpath 位置、经哪个 ClassLoader 解析、挂到哪个对外前缀）。
 * 「谁能访问该前缀」不在此表达：逐路径访问级别由 {@link WebRouteContribution} 经
 * {@code routes()} / {@code RouteAccessRegistry} 声明（同一目录前缀下不同文件可有不同访问级别，
 * 故访问必须按路径声明，无法挂在目录级的本记录上）。
 *
 * @param pluginId          声明方插件 id
 * @param classpathLocation classpath 位置，如 {@code classpath:/static/pixiv-gallery/}
 * @param publicPathPrefix  对外公开路径前缀，如 {@code /pixiv-gallery/}
 */
public record StaticResourceContribution(
        String pluginId,
        String classpathLocation,
        String publicPathPrefix
) {
}
