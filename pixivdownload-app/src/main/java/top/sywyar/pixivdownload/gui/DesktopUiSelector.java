package top.sywyar.pixivdownload.gui;

import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiProvider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Deterministic desktop UI selection: explicit id, then one default, then the only provider. */
final class DesktopUiSelector {
    private DesktopUiSelector() {}

    static Selection select(String configuredId, List<DesktopUiProvider> providers) {
        Map<String, DesktopUiProvider> byId = new LinkedHashMap<>();
        for (DesktopUiProvider provider : providers == null ? List.<DesktopUiProvider>of() : providers) {
            if (provider == null || provider.id() == null || provider.id().isBlank()) continue;
            String id = provider.id().trim().toLowerCase(Locale.ROOT);
            if (byId.putIfAbsent(id, provider) != null) throw new IllegalStateException("Duplicate desktop UI provider id: " + id);
        }
        String configured = configuredId == null ? "" : configuredId.trim().toLowerCase(Locale.ROOT);
        if (!configured.isEmpty() && byId.containsKey(configured)) return new Selection(byId.get(configured), null);

        List<DesktopUiProvider> defaults = byId.values().stream().filter(DesktopUiProvider::defaultProvider).toList();
        if (defaults.size() > 1) throw new IllegalStateException("Multiple default desktop UI providers: " + defaults.stream().map(DesktopUiProvider::id).toList());
        DesktopUiProvider fallback = defaults.size() == 1 ? defaults.get(0) : byId.size() == 1 ? byId.values().iterator().next() : null;
        if (fallback == null) throw new IllegalStateException("No unambiguous desktop UI provider is available");
        String diagnostic = configured.isEmpty() ? null : "Configured desktop UI provider '" + configured + "' is unavailable; using '" + fallback.id() + "'";
        return new Selection(fallback, diagnostic);
    }

    record Selection(DesktopUiProvider provider, String diagnostic) {}
}
