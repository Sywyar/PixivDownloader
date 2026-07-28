package top.sywyar.pixivdownload.plugin.api.http;

/**
 * Stable host capability for opening plugin-owned outbound HTTP clients.
 */
@FunctionalInterface
public interface OutboundHttpClientFactory {

    OutboundHttpClient open(OutboundHttpClientProfile profile);
}
