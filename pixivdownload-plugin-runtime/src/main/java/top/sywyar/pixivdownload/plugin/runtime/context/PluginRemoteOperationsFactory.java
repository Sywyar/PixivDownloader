package top.sywyar.pixivdownload.plugin.runtime.context;

import top.sywyar.pixivdownload.plugin.api.service.PluginServices;
import top.sywyar.pixivdownload.plugin.api.web.RemoteOperations;

/** 父 context 提供实现；子 context 只得到当前 publication 的远程操作入口。 */
@FunctionalInterface
public interface PluginRemoteOperationsFactory {
    RemoteOperations create(String pluginId, PluginServices services);
}
