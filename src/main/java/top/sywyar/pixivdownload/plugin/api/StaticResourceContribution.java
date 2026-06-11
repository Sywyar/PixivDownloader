package top.sywyar.pixivdownload.plugin.api;

/**
 * 插件声明的静态资源目录。资源解析必须经声明方插件的 ClassLoader
 * （由注册中心按 {@code pluginId} 解析），不做全局 {@code classpath:} 扫描假设。
 *
 * @param pluginId          声明方插件 id
 * @param classpathLocation classpath 位置，如 {@code classpath:/static/pixiv-gallery/}
 * @param publicPathPrefix  对外公开路径前缀，如 {@code /pixiv-gallery/}
 * @param accessLevel       访问级别
 */
public record StaticResourceContribution(
        String pluginId,
        String classpathLocation,
        String publicPathPrefix,
        AccessLevel accessLevel
) {
}
