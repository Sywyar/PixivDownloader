package com.example.textmetrics.provider;

import org.pf4j.Plugin;
import org.pf4j.PluginWrapper;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivPluginProvider;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;
import com.example.textmetrics.api.TextMetrics;
import top.sywyar.pixivdownload.plugin.api.service.ServiceKey;
import top.sywyar.pixivdownload.plugin.api.service.ServiceExport;
import java.util.List;

/** PF4J 入口；仅声明当前插件的贡献。 */
public final class ProviderPlugin extends Plugin implements PixivPluginProvider {
    public ProviderPlugin(PluginWrapper wrapper) { super(wrapper); }

    @Override
    public PixivFeaturePlugin featurePlugin() {
        String owner = getWrapper().getPluginId();
        return new PixivFeaturePlugin() {
            public String id() { return owner; }
            public String displayName() { return "plugin.name"; }
            public String description() { return "plugin.summary"; }
            public PluginKind kind() { return PluginKind.FEATURE; }
            public List<I18nContribution> i18n() {
                return List.of(new I18nContribution("text-metrics-provider", "i18n.web.text-metrics-provider"));
            }
            public List<ServiceExport<?>> serviceExports() {
                return List.of(new ServiceExport<>(new ServiceKey<>("text-metrics-contract", TextMetrics.class), "textMetrics"));
            }
        };
    }
}
