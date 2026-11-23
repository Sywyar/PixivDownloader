package top.sywyar.pixivdownload.gui.config;

import top.sywyar.pixivdownload.guiswing.SwingHost;
import top.sywyar.pixivdownload.plugin.api.gui.RepositoryConfigEntry;
import top.sywyar.pixivdownload.plugin.api.gui.RepositorySettingsValidation;
import java.util.List;

/** Swing 表单复用工具包无关的仓库设置校验。 */
public final class RepositoryConfigValidator {
    private RepositoryConfigValidator() {}
    static final long MAX_BYTES_LIMIT = RepositorySettingsValidation.MAX_BYTES_LIMIT;
    static final long MAX_TIMEOUT_MS = RepositorySettingsValidation.MAX_TIMEOUT_MS;
    public static String validateId(String id, List<RepositoryConfigEntry> others) {
        return RepositorySettingsValidation.validateId(id, others, SwingHost.host().reservedPluginRepositoryIds());
    }
    public static String validateManifestUrl(String value) { return RepositorySettingsValidation.validateManifestUrl(value); }
    public static String validateManifestUrl(String value, boolean strict) { return RepositorySettingsValidation.validateManifestUrl(value, strict); }
    public static String validateProxyPolicy(String value) { return RepositorySettingsValidation.validateProxyPolicy(value); }
    public static String validateTimeoutOverride(String value) { return RepositorySettingsValidation.validateTimeoutOverride(value); }
    public static String validateSizeOverride(String value) { return RepositorySettingsValidation.validateSizeOverride(value); }
    public static long parseOverride(String value) { return RepositorySettingsValidation.parseOverride(value); }
}
