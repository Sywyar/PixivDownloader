package top.sywyar.pixivdownload.plugin.install;

import org.springframework.http.HttpStatus;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginInstallOutcome;

import java.util.Locale;

/**
 * 把本地插件包安装结果分类 {@link PluginInstallOutcome}（住 {@code plugin-runtime}、JDK-only、不含 HTTP / i18n）映射到
 * app 层的 HTTP 状态与 i18n 文案 key。安装器对所有「已决结局」都<b>返回</b>结果对象（不抛业务异常），故本映射既覆盖
 * accepted（200）也覆盖各类拒绝 / 失败。
 *
 * <p>{@link #httpStatus} 用 exhaustive switch（新增 outcome 编译期强制补映射），i18n key 经
 * {@link #messageKey} 由枚举名规范派生（{@code plugin.install.outcome.<kebab>}）——新增 outcome 必须同步补
 * {@code i18n/messages*.properties} 对应文案（由 {@code PluginInstallOutcomeMappingTest} 守护「每个 outcome 的 key
 * 在两种语言下都有非空文案」）。code 取 {@link PluginInstallOutcome#name()}，与界面语言无关。
 */
public final class PluginInstallOutcomeMapping {

    private PluginInstallOutcomeMapping() {
    }

    /**
     * 该结果分类对应的 HTTP 状态：accepted（新装 / 升级 / 降级 / 已存在）→ 200；包内容非法（空 / 损坏 / 缺描述符 /
     * 歧义 / 描述符非法 / Zip Slip）→ 400；资源规模超限（Zip Bomb 防护）→ 413；完整性校验不通过 → 422；与核心 API、
     * 依赖或现存更高版本冲突（不兼容 / 依赖未满足 / 拒绝降级）→ 409；安装期服务端 IO 失败 → 500。
     */
    public static HttpStatus httpStatus(PluginInstallOutcome outcome) {
        return switch (outcome) {
            case INSTALLED, UPGRADED, DOWNGRADED, DUPLICATE -> HttpStatus.OK;
            case REJECTED_EMPTY, REJECTED_MALFORMED, REJECTED_NO_DESCRIPTOR, REJECTED_AMBIGUOUS,
                    REJECTED_INVALID, REJECTED_UNSAFE -> HttpStatus.BAD_REQUEST;
            case REJECTED_TOO_LARGE -> HttpStatus.PAYLOAD_TOO_LARGE;
            case REJECTED_INTEGRITY -> HttpStatus.UNPROCESSABLE_ENTITY;
            case REJECTED_INCOMPATIBLE, REJECTED_DEPENDENCY, DOWNGRADE_REJECTED -> HttpStatus.CONFLICT;
            case FAILED -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    /**
     * 该结果分类的本地化文案 key（在后端 {@code i18n/messages*.properties} 中解析）：
     * {@code plugin.install.outcome.} + 枚举名小写短横线（如 {@code REJECTED_NO_DESCRIPTOR} →
     * {@code plugin.install.outcome.rejected-no-descriptor}）。
     */
    public static String messageKey(PluginInstallOutcome outcome) {
        return "plugin.install.outcome." + outcome.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
