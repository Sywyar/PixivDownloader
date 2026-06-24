package top.sywyar.pixivdownload.plugin;

import org.springframework.http.HttpStatus;

import java.util.Objects;

/**
 * 插件管理操作失败：携带稳定机器码 {@link PluginManagementErrorCode}（决定 HTTP 状态与 i18n 文案 key）以及可空的
 * 诊断上下文（目标插件 id、尝试的运行期动词 token、当时已知的运行期阶段），供 {@link PluginManagementController}
 * 的异常处理器解析为「稳定 code + 本地化 message + 诊断字段」的错误响应。用于区分管理动词的前置守卫拒绝与非法状态
 * 流转：未知插件（404）、内置插件不可热管理 / 外置插件未激活 / 必选插件不可停用 / 当前状态不允许该流转（均 409）。
 */
public class PluginManagementException extends RuntimeException {

    private final PluginManagementErrorCode code;
    private final String pluginId;
    private final String action;
    private final PluginRuntimePhase runtimePhase;

    /**
     * @param code         稳定机器码（决定 HTTP 状态与 i18n 文案 key）
     * @param pluginId     目标插件 id（可空）
     * @param action       尝试执行的运行期动词 token（状态查询等无动词场景为 {@code null}）
     * @param runtimePhase 目标插件当时的运行期阶段（仅在已知时附带，否则 {@code null}）
     * @param detail       面向日志 / 兜底的非本地化说明（i18n 解析缺失时作为 message 兜底）
     */
    public PluginManagementException(PluginManagementErrorCode code, String pluginId, String action,
                                     PluginRuntimePhase runtimePhase, String detail) {
        super(detail);
        this.code = Objects.requireNonNull(code, "code");
        this.pluginId = pluginId;
        this.action = action;
        this.runtimePhase = runtimePhase;
    }

    /** 稳定机器码。 */
    public PluginManagementErrorCode code() {
        return code;
    }

    /** 应返回的 HTTP 状态（由 {@link #code} 派生）。 */
    public HttpStatus status() {
        return code.status();
    }

    /** 本地化文案 key（由 {@link #code} 派生，在后端 {@code i18n/messages*.properties} 中解析）。 */
    public String messageKey() {
        return code.messageKey();
    }

    /** 目标插件 id（可空）。 */
    public String pluginId() {
        return pluginId;
    }

    /** 尝试执行的运行期动词 token（可空）。 */
    public String action() {
        return action;
    }

    /** 目标插件当时的运行期阶段（可空）。 */
    public PluginRuntimePhase runtimePhase() {
        return runtimePhase;
    }
}
