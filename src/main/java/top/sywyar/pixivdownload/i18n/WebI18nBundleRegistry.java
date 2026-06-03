package top.sywyar.pixivdownload.i18n;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class WebI18nBundleRegistry {

    private final Map<String, String> namespaces = new LinkedHashMap<>();

    public WebI18nBundleRegistry() {
        namespaces.put("common", "i18n.web.common");
        namespaces.put("setup", "i18n.web.setup");
        namespaces.put("login", "i18n.web.login");
        namespaces.put("intro", "i18n.web.intro");
        namespaces.put("batch", "i18n.web.batch");
        namespaces.put("gallery", "i18n.web.gallery");
        namespaces.put("stats", "i18n.web.stats");
        namespaces.put("duplicates", "i18n.web.duplicates");
        namespaces.put("artwork", "i18n.web.artwork");
        namespaces.put("showcase", "i18n.web.showcase");
        namespaces.put("series", "i18n.web.series");
        namespaces.put("novel", "i18n.web.novel");
        namespaces.put("translate", "i18n.web.translate");
        namespaces.put("narration", "i18n.web.narration");
        namespaces.put("monitor", "i18n.web.monitor");
        namespaces.put("userscript", "i18n.web.userscript");
        namespaces.put("invite", "i18n.web.invite");
        namespaces.put("tour", "i18n.web.tour");
        namespaces.put("maintenance", "i18n.web.maintenance");
    }

    public String resolveBaseName(String namespace) {
        return namespaces.get(namespace);
    }

    public List<String> supportedNamespaces() {
        return List.copyOf(namespaces.keySet());
    }
}
