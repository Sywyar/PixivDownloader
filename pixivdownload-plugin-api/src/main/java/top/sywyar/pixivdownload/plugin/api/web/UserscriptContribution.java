package top.sywyar.pixivdownload.plugin.api.web;

/**
 * 插件声明的油猴脚本（userscript）扫描来源。脚本资源解析必须经声明方插件的
 * ClassLoader（由注册中心按 {@code pluginId} 解析），不做全局 {@code classpath:} 扫描假设。
 *
 * @param pluginId         声明方插件 id
 * @param classpathPattern 油猴脚本 classpath 匹配模式，如
 *                         {@code classpath:/static/userscripts/*.user.js}
 */
public record UserscriptContribution(
        String pluginId,
        String classpathPattern
) {
}
