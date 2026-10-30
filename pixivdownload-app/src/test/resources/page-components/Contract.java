package example.pages.wrapper;

import org.pf4j.*;
import top.sywyar.pixivdownload.plugin.api.plugin.*;

public final class Contract extends Plugin implements PixivPluginProvider {
    public Contract(PluginWrapper wrapper) { super(wrapper); }
    public PixivFeaturePlugin featurePlugin() {
        return new PixivFeaturePlugin() {
            public String id() { return "page-checklist-contract"; }
            public String displayName() { return "plugin.name"; }
            public String description() { return "plugin.summary"; }
            public PluginKind kind() { return PluginKind.FEATURE; }
        };
    }
}
