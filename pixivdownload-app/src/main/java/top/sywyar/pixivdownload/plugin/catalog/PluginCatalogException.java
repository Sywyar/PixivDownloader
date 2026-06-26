package top.sywyar.pixivdownload.plugin.catalog;

import org.springframework.http.HttpStatus;

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

    public PluginCatalogException(PluginCatalogErrorCode code, String pluginId, String version, String detail) {
        super(detail);
        this.code = Objects.requireNonNull(code, "code");
        this.pluginId = pluginId;
        this.version = version;
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
}
