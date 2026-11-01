package top.sywyar.pixivdownload.plugin.runtime.context;

import top.sywyar.pixivdownload.plugin.api.security.OperationGuard;
import top.sywyar.pixivdownload.plugin.api.service.PluginServices;

/** 宿主在 child refresh 前以可信服务访问器建立 owner 绑定，不接受自报插件身份。 */
public interface PluginOperationGuardFactory {
    OperationGuard create(PluginServices services);
}
