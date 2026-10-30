package top.sywyar.pixivdownload.plugin.api.service;

/**
 * 宿主盖章的精确服务发布身份；不代表用户权限，也不能用来选择替代实例。
 * @param pluginId 功能插件身份
 * @param packageId 物理插件包身份
 * @param generation 加载代际
 * @param publicationId 当前运行时内的发布序号
 */
public record ServicePublication(String pluginId, String packageId, long generation, long publicationId) {}
