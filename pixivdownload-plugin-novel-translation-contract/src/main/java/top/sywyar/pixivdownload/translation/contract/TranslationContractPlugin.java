package top.sywyar.pixivdownload.translation.contract;

import org.pf4j.Plugin;
import top.sywyar.pixivdownload.plugin.api.plugin.*;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;
import java.util.List;

public class TranslationContractPlugin extends Plugin implements PixivPluginProvider {
    public PixivFeaturePlugin featurePlugin() {
        return new PixivFeaturePlugin() {
            public String id() { return "novel-translation-contract"; }
            public String displayName() { return "plugin.name"; }
            public String description() { return "plugin.summary"; }
            public PluginKind kind() { return PluginKind.FEATURE; }
            public List<I18nContribution> i18n() {
                return List.of(new I18nContribution(id(), "i18n.web.novel-translation-contract"));
            }
        };
    }
}
