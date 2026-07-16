package top.sywyar.pixivdownload.plugin.management;

import org.springframework.http.HttpStatus;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginLifecycleException;

/**
 * 插件管理操作失败的稳定机器码：每个常量绑定一个固定 HTTP 状态与一条 i18n 文案 key。{@link #name()} 即对外返回给
 * 客户端的稳定 {@code code}（与界面语言 / 具体文案无关，供 Web / GUI 管理入口按机器语义分支处理，而不必解析本地化
 * 文本）；i18n key 仅用于把同一失败解析成人类可读 message。新增失败类别在此扩枚举（并补对应 {@code i18n/messages*}
 * 文案），不要在调用处散落裸字符串 code，也不要把界面文案当成机器分支依据。
 */
public enum PluginManagementErrorCode {

    /** 未知插件 id（既非内置、也未安装、也不在必选策略要求中）。 */
    UNKNOWN_PLUGIN(HttpStatus.NOT_FOUND, "plugin.manage.error.unknown"),

    /** 启用态写请求缺少必需的 enabled 布尔字段。 */
    INVALID_TOGGLE_REQUEST(HttpStatus.BAD_REQUEST, "plugin.manage.error.invalid-toggle-request"),

    /** 内置插件随主程序编译，不支持运行期热启停。 */
    BUILT_IN_PLUGIN(HttpStatus.CONFLICT, "plugin.manage.error.built-in"),

    /** 外置插件当前未激活（已被 {@code plugins.<id>.enabled} 配置禁用），运行期动词不可用。 */
    INACTIVE_PLUGIN(HttpStatus.CONFLICT, "plugin.manage.error.inactive"),

    /** 描述符声明了重启生效策略，不能经普通运行期动词热管理。 */
    RESTART_REQUIRED_PLUGIN(HttpStatus.CONFLICT, "plugin.manage.error.restart-required"),

    /** 必选插件不允许被停用类动词（quiesce / stop / unload）降级。 */
    REQUIRED_PLUGIN(HttpStatus.CONFLICT, "plugin.manage.error.required"),

    /** 当前运行期阶段不允许该流转（来自生命周期状态机 {@link PluginLifecycleException}）。 */
    ILLEGAL_TRANSITION(HttpStatus.CONFLICT, "plugin.manage.error.transition"),

    OPERATION_IN_PROGRESS(HttpStatus.CONFLICT, "plugin.manage.error.operation-in-progress"),

    DEPENDENCY_BLOCKED(HttpStatus.CONFLICT, "plugin.manage.error.dependency-blocked"),

    DEPENDENCY_UNSATISFIED(HttpStatus.CONFLICT, "plugin.manage.error.dependency-unsatisfied"),

    /** 插件期望启用态无法安全写入宿主配置。 */
    TOGGLE_PERSIST_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "plugin.manage.error.toggle-persist-failed"),

    /** 当前进程没有由桌面生命周期管理器持有的 RUNNING 后端上下文。 */
    BACKEND_RESTART_UNAVAILABLE(HttpStatus.CONFLICT, "plugin.manage.error.backend-restart-unavailable"),

    /** 已接受一个尚未触发的后端上下文重启请求。 */
    BACKEND_RESTART_PENDING(HttpStatus.CONFLICT, "plugin.manage.error.backend-restart-pending"),

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
