package top.sywyar.pixivdownload.plugin.api.web;

/**
 * 插件声明的一份油猴脚本（userscript）。稳定安装标识与精确资源路径均由声明方拥有；
 * 宿主只按已盖章 owner 的 ClassLoader 解析资源并物化目录，不从文件名猜测插件私有身份。
 *
 * @param id                全局稳定的安装标识，用作 {@code /api/scripts/{id}/install} 路径段
 * @param classpathResource 精确的脚本 classpath 资源，如
 *                          {@code classpath:/static/userscripts/example.user.js}；不得使用通配符
 * @param i18nNamespace     声明方贡献的翻译命名空间；空字符串使用脚本元数据及既有工作台翻译。
 *                          名称和描述的键分别为 {@code script.meta.<id>.name} 和
 *                          {@code script.meta.<id>.description}
 */
public record UserscriptContribution(
        String id,
        String classpathResource,
        String i18nNamespace
) {
    /**
     * 声明不带独立翻译命名空间的脚本。
     * @param id 稳定安装标识
     * @param classpathResource 精确 classpath 资源
     */
    public UserscriptContribution(String id, String classpathResource) {
        this(id, classpathResource, "");
    }
}
