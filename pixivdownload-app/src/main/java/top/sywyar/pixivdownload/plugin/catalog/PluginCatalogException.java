package top.sywyar.pixivdownload.plugin.catalog;

import org.springframework.http.HttpStatus;
import top.sywyar.pixivdownload.plugin.install.PluginDependencyInstallResult;

import java.util.List;
import java.util.Objects;

/**
 * 受信 catalog 操作失败：携带稳定机器码 {@link PluginCatalogErrorCode}（决定 HTTP 状态与 i18n 文案 key）与可空诊断上下文
 * （目标插件 id、版本）。由 {@code PluginMarketController} 的 {@code @ExceptionHandler} 解析为「稳定 code + 本地化
 * message + 诊断字段」的错误响应。
 */
public class PluginCatalogException extends RuntimeException {

    private final PluginCatalogErrorCode code;
    private final String pluginId;
    private final String version;
    private final List<PluginDependencyInstallResult> dependencyInstallResults;

    public PluginCatalogException(PluginCatalogErrorCode code, String pluginId, String version, String detail) {
        this(code, pluginId, version, detail, List.of());
    }

    private PluginCatalogException(PluginCatalogErrorCode code, String pluginId, String version, String detail,
                                   List<PluginDependencyInstallResult> dependencyInstallResults) {
        super(detail);
        this.code = Objects.requireNonNull(code, "code");
        this.pluginId = pluginId;
        this.version = version;
        this.dependencyInstallResults = dependencyInstallResults != null
                ? List.copyOf(dependencyInstallResults) : List.of();
    }

    public PluginCatalogException(PluginCatalogErrorCode code, String detail) {
        this(code, null, null, detail);
    }

    /** 稳定机器码。 */
    public PluginCatalogErrorCode code() {
        return code;
    }

    /** 应返回的 HTTP 状态（由 {@link #code} 派生）。 */
    public HttpStatus status() {
        return code.status();
    }

    /** 本地化文案 key（由 {@link #code} 派生）。 */
    public String messageKey() {
        return code.messageKey();
    }

    /** 目标插件 id（可空）。 */
    public String pluginId() {
        return pluginId;
    }

    /** 目标版本（可空）。 */
    public String version() {
        return version;
    }

    /** 本次市场安装过程中已经自动安装成功的依赖插件结果。 */
    public List<PluginDependencyInstallResult> dependencyInstallResults() {
        return dependencyInstallResults;
    }

    public PluginCatalogException withDependencyInstallResults(
            List<PluginDependencyInstallResult> dependencyInstallResults) {
        if (dependencyInstallResults == null || dependencyInstallResults.isEmpty()) {
            return this;
        }
        PluginCatalogException enriched = new PluginCatalogException(
                code, pluginId, version, getMessage(), dependencyInstallResults);
        enriched.initCause(this);
        return enriched;
    }
}
