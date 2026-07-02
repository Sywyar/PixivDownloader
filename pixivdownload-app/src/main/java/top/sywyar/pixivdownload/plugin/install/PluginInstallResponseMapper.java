package top.sywyar.pixivdownload.plugin.install;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.i18n.AppLocaleResolver;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginInstallOutcome;
import top.sywyar.pixivdownload.plugin.management.PluginManagementController;

/**
 * 把后端事实 {@link PluginInstallReport} 映射为对外 {@link PluginInstallResponse}（叠加稳定 {@code outcome} + HTTP 状态 +
 * 本地化 {@code message}）。本地包上传安装（{@link PluginManagementController}）与受信仓库市场安装
 * （{@code PluginMarketController}）共用同一映射，使两条安装路径的响应形态 / 状态分档 / 文案完全一致。
 */
@Component
public class PluginInstallResponseMapper {

    private final AppMessages messages;
    private final AppLocaleResolver localeResolver;

    public PluginInstallResponseMapper(AppMessages messages, AppLocaleResolver localeResolver) {
        this.messages = messages;
        this.localeResolver = localeResolver;
    }

    /** 按请求语言解析 message、按结果分类派生 HTTP 状态，构造统一的安装响应实体。 */
    public ResponseEntity<PluginInstallResponse> toResponse(PluginInstallReport report, HttpServletRequest request) {
        PluginInstallOutcome outcome = report.outcome();
        HttpStatus httpStatus = PluginInstallOutcomeMapping.httpStatus(outcome);
        String fallback = report.diagnostics().isEmpty() ? outcome.name() : report.diagnostics().get(0);
        String message = messages.getOrDefault(localeResolver.resolveLocale(request),
                PluginInstallOutcomeMapping.messageKey(outcome), fallback);
        PluginInstallResponse body = new PluginInstallResponse(
                outcome.name(),
                report.accepted(),
                report.effectiveAfterRestart(),
                httpStatus.value(),
                message,
                report.pluginId(),
                report.version(),
                report.previousVersion(),
                report.pluginId(),
                report.version(),
                report.operation() != null ? report.operation().name() : null,
                report.runtimePhase() != null ? report.runtimePhase().name() : null,
                report.updated(),
                report.dependencies(),
                report.unsatisfiedDependencies(),
                report.dependencyProblems(),
                report.diagnostics(),
                report.transactionId(),
                report.activated(),
                report.rolledBack(),
                report.rollbackVersion(),
                report.dependencyInstallResults());
        return ResponseEntity.status(httpStatus).body(body);
    }
}
