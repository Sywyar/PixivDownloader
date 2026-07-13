package top.sywyar.pixivdownload.plugin.web;

import top.sywyar.pixivdownload.plugin.lifecycle.request.PluginRequestOwner;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;

/**
 * 一次插件 serving 接入的不可伪造句柄。即使复用同一个 {@link PluginRegistry.RegisteredPlugin} 对象，
 * 每次 stop → start 都会得到新句柄；旧句柄只能成为无效的迟到撤回请求。
 */
public final class PluginWebContributionHandle {

    private final PluginRegistry.RegisteredPlugin registered;
    private final PluginRequestOwner requestOwner;

    PluginWebContributionHandle(PluginRegistry.RegisteredPlugin registered, long servingId) {
        this.registered = registered;
        this.requestOwner = new PluginRequestOwner(registered.id(), registered.generation(), servingId);
    }

    PluginRegistry.RegisteredPlugin registered() {
        return registered;
    }

    public String pluginId() {
        return registered.id();
    }

    public long generation() {
        return registered.generation();
    }

    public long servingId() {
        return requestOwner.servingId();
    }

    public PluginRequestOwner requestOwner() {
        return requestOwner;
    }

    @Override
    public String toString() {
        return "PluginWebContributionHandle[pluginId=" + pluginId()
                + ", generation=" + generation() + ", servingId=" + servingId() + "]";
    }
}
