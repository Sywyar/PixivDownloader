package top.sywyar.pixivdownload.plugin.api.web;

/**
 * 插件声明的静态资源。资源解析必须经声明方插件的 ClassLoader
 * （由注册中心按宿主登记的 owner 解析），不做全局 {@code classpath:} 扫描假设。
 * <p>
 * 本记录描述两种字形：
 * <ul>
 *   <li>目录贡献（{@code exactFile=false}）：{@code classpathLocation} 为 classpath 目录，{@code publicPathPrefix}
 *       为对外路径前缀，注册为 {@code <prefix>**}。</li>
 *   <li>精确文件贡献（{@code exactFile=true}）：{@code classpathLocation} 为 classpath 目录，{@code publicPathPrefix}
 *       为精确对外文件路径，注册为精确 URL pattern。资源为该目录下的同名文件。</li>
 * </ul>
 * 「谁能访问该路径」不在此表达：逐路径访问级别由 {@link WebRouteContribution} 经
 * {@code routes()} / {@code RouteAccessRegistry} 声明（同一目录前缀下不同文件可有不同访问级别，
 * 故访问必须按路径声明，无法挂在目录级的本记录上）。
 *
 * @param classpathLocation classpath 位置，如 {@code classpath:/static/pixiv-gallery/}
 * @param publicPathPrefix  对外公开路径（目录前缀或精确文件路径），如 {@code /pixiv-gallery/} 或 {@code /pixiv-stats.html}
 * @param exactFile         {@code true} 为精确文件映射，{@code false} 为目录前缀映射（默认）
 */
public record StaticResourceContribution(
        String classpathLocation,
        String publicPathPrefix,
        boolean exactFile
) {
    /** 目录贡献的便捷构造（{@code exactFile=false}）。 */
    public StaticResourceContribution(String classpathLocation, String publicPathPrefix) {
        this(classpathLocation, publicPathPrefix, false);
    }
}
