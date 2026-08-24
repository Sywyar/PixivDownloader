package top.sywyar.pixivdownload.gui.i18n;

import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;

import java.util.List;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * Resolves plugin-owned i18n keys for Swing-side contribution rendering.
 */
public final class PluginContributionText {

    private static final ResourceBundle.Control PLUGIN_BUNDLE_CONTROL =
            ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES);
    private static final char BOM = '\uFEFF';

    private PluginContributionText() {
    }

    public static String resolve(List<I18nContribution> i18nContributions, ClassLoader classLoader,
                                 String namespace, String key) {
        if (namespace == null || key == null) {
            return key;
        }
        ClassLoader effectiveClassLoader = classLoader == null
                ? PluginContributionText.class.getClassLoader()
                : classLoader;
        List<I18nContribution> safeContributions = i18nContributions == null
                ? List.of()
                : i18nContributions;
        for (I18nContribution ns : safeContributions) {
            if (ns == null || !namespace.equals(ns.namespace())) {
                continue;
            }
            try {
                ResourceBundle bundle = ResourceBundle.getBundle(
                        ns.baseName(), GuiMessages.currentLocale(), effectiveClassLoader, PLUGIN_BUNDLE_CONTROL);
                String resolved = getString(bundle, key);
                if (resolved != null) {
                    return resolved;
                }
            } catch (MissingResourceException ignored) {
                // Missing locale bundle falls back to the raw key below.
            }
        }
        return key;
    }

    private static String getString(ResourceBundle bundle, String key) {
        if (bundle.containsKey(key)) {
            return bundle.getString(key);
        }
        for (String bundleKey : bundle.keySet()) {
            if (key.equals(normalizeKey(bundleKey))) {
                return bundle.getString(bundleKey);
            }
        }
        return null;
    }

    private static String normalizeKey(String key) {
        if (key != null && !key.isEmpty() && key.charAt(0) == BOM) {
            return key.substring(1);
        }
        return key;
    }
}
