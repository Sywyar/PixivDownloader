package com.example.textmetrics.contract;

import org.pf4j.Plugin;
import org.pf4j.PluginWrapper;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivPluginProvider;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;

import java.util.List;

/** PF4J 入口；仅声明当前插件的贡献。 */
public final class ContractPlugin extends Plugin implements PixivPluginProvider {
    public ContractPlugin(PluginWrapper wrapper) { super(wrapper); }

    @Override
    public PixivFeaturePlugin featurePlugin() {
        String owner = getWrapper().getPluginId();
        return new PixivFeaturePlugin() {
            public String id() { return owner; }
            public String displayName() { return "plugin.name"; }
            public String description() { return "plugin.summary"; }
            public PluginKind kind() { return PluginKind.FEATURE; }
            public List<I18nContribution> i18n() {
                return List.of(new I18nContribution("text-metrics-contract", "i18n.web.text-metrics-contract"));
            }

        };
    }
}
