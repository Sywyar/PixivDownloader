package top.sywyar.pixivdownload.plugin;

import org.springframework.http.HttpStatus;

/**
 * 插件管理操作失败的稳定机器码：每个常量绑定一个固定 HTTP 状态与一条 i18n 文案 key。{@link #name()} 即对外返回给
 * 客户端的稳定 {@code code}（与界面语言 / 具体文案无关，供 Web / GUI 管理入口按机器语义分支处理，而不必解析本地化
 * 文本）；i18n key 仅用于把同一失败解析成人类可读 message。新增失败类别在此扩枚举（并补对应 {@code i18n/messages*}
 * 文案），不要在调用处散落裸字符串 code，也不要把界面文案当成机器分支依据。
 */
public enum PluginManagementErrorCode {

    /** 未知插件 id（既非内置、也未安装、也不在必选策略要求中）。 */
    UNKNOWN_PLUGIN(HttpStatus.NOT_FOUND, "plugin.manage.error.unknown"),

    /** 内置插件随主程序编译，不支持运行期热启停。 */
    BUILT_IN_PLUGIN(HttpStatus.CONFLICT, "plugin.manage.error.built-in"),

    /** 外置插件当前未激活（已被 {@code plugins.<id>.enabled} 配置禁用），运行期动词不可用。 */
    INACTIVE_PLUGIN(HttpStatus.CONFLICT, "plugin.manage.error.inactive"),

    /** startup-only 外置插件只在完整进程启动前生效，不支持普通热启停 / 卸载 / 重载。 */
    STARTUP_ONLY_PLUGIN(HttpStatus.CONFLICT, "plugin.manage.error.startup-only"),

    /** 必选插件不允许被停用类动词（quiesce / stop / unload）降级。 */
    REQUIRED_PLUGIN(HttpStatus.CONFLICT, "plugin.manage.error.required"),

    /** 当前运行期阶段不允许该流转（来自生命周期状态机 {@link PluginLifecycleException}）。 */
    ILLEGAL_TRANSITION(HttpStatus.CONFLICT, "plugin.manage.error.transition"),

    OPERATION_IN_PROGRESS(HttpStatus.CONFLICT, "plugin.manage.error.operation-in-progress"),

    DEPENDENCY_BLOCKED(HttpStatus.CONFLICT, "plugin.manage.error.dependency-blocked"),

    PHYSICAL_UNLOAD_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "plugin.manage.error.physical-unload-failed");

    private final HttpStatus status;
    private final String messageKey;

    PluginManagementErrorCode(HttpStatus status, String messageKey) {
        this.status = status;
        this.messageKey = messageKey;
    }

    /** 该失败类别对应的 HTTP 状态。 */
    public HttpStatus status() {
        return status;
    }

    /** 该失败类别的本地化文案 key（在后端 {@code i18n/messages*.properties} 中解析）。 */
    public String messageKey() {
        return messageKey;
    }
}
