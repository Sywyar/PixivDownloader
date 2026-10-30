package top.sywyar.pixivdownload.plugin.runtime.context;

import top.sywyar.pixivdownload.plugin.api.credential.PluginCredentials;
import top.sywyar.pixivdownload.plugin.api.resource.PluginResources;

/** 宿主装配桥；插件只注入已绑定可信 owner 和资源 generation 的句柄。 */
@FunctionalInterface
public interface PluginCredentialsFactory {
    PluginCredentials create(String pluginId, PluginResources resources);
}
