package top.sywyar.pixivdownload.plugin.runtime.http;

import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpClient;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpClientFactory;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpClientProfile;

import java.util.Objects;

/**
 * Mechanical Spring-Web adapter for the pure Plugin API outbound HTTP capability.
 */
public final class PluginRestTemplateAdapter {

    private PluginRestTemplateAdapter() {
    }

    public static ManagedPluginRestTemplate open(
            OutboundHttpClientFactory factory,
            OutboundHttpClientProfile profile
    ) {
        Objects.requireNonNull(factory, "factory");
        Objects.requireNonNull(profile, "profile");
        OutboundHttpClient client = Objects.requireNonNull(
                factory.open(profile),
                "factory returned null client");
        return new ManagedPluginRestTemplate(client);
    }
}
