package top.sywyar.pixivdownload.runtimeprobe;

import org.pf4j.PluginWrapper;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.sdk.SdkVersion;

/** 验证外置插件通过宿主提供的 SDK 元数据 API 初始化。 */
public class SdkContractProbePlugin extends BootstrapProbePlugin {

    public SdkContractProbePlugin(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public PixivFeaturePlugin featurePlugin() {
        if (!SdkVersion.isSameRelease(SdkVersion.VERSION, SdkVersion.VERSION)) {
            throw new IllegalStateException("SDK contract unavailable");
        }
        return super.featurePlugin();
    }
}
