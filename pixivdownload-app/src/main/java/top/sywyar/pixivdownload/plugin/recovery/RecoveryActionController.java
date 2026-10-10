package top.sywyar.pixivdownload.plugin.recovery;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.common.NetworkUtils;
import top.sywyar.pixivdownload.gui.GuiLauncher;
import top.sywyar.pixivdownload.gui.DesktopUiFailure;
import top.sywyar.pixivdownload.gui.bootstrap.ApplicationRestartService;
import top.sywyar.pixivdownload.i18n.AppLocaleResolver;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;
import top.sywyar.pixivdownload.plugin.management.PluginStatusService;

import java.util.List;
import java.util.function.BooleanSupplier;

/** ADMIN 路由和 CSRF 过滤器之外，恢复操作还要求本机请求和当下的恢复状态。 */
@RestController
@RequestMapping("/api/plugins/recovery")
public class RecoveryActionController {
    private final RecoveryModeService recovery;
    private final PluginStatusService plugins;
    private final ApplicationRestartService application;
    private final AppMessages messages;
    private final AppLocaleResolver locales;
    private final java.util.function.Supplier<DesktopUiFailure> desktopFailure;

    @Autowired
    public RecoveryActionController(RecoveryModeService recovery, PluginStatusService plugins,
            ApplicationRestartService application, AppMessages messages, AppLocaleResolver locales) {
        this(recovery, plugins, application, messages, locales, GuiLauncher::desktopRecoveryFailure);
    }

    RecoveryActionController(RecoveryModeService recovery, PluginStatusService plugins,
            ApplicationRestartService application, AppMessages messages, AppLocaleResolver locales,
            java.util.function.Supplier<DesktopUiFailure> desktopFailure) {
        this.recovery = recovery;
        this.plugins = plugins;
        this.application = application;
        this.messages = messages;
        this.locales = locales;
        this.desktopFailure = desktopFailure;
    }

    @GetMapping
    public ResponseEntity<?> status(HttpServletRequest request) {
        var guiFailure = desktopFailure.get();
        boolean noGui = guiFailure != null;
        var errors = new java.util.ArrayList<String>();
        boolean recoveryMode;
        try {
            recoveryMode = recovery.isActive();
        } catch (RuntimeException failure) {
            if (!noGui) return error(request, 503, "recovery.action.failed");
            recoveryMode = false;
            errors.add(failure.toString());
        }
        var gate = plugins.recoveryGateSnapshot();
        boolean active = recoveryMode || noGui;
        if (!active) return ResponseEntity.ok(
                new RecoveryView(false, false, false, false, !gate.safeToScan(), null, List.of(),
                        new RecoveryPluginFocus(java.util.Map.of(), List.of())));
        var failures = plugins.failureSnapshot();
        failures.forEach((id, failure) -> errors.add(id + ": " + failure));
        var report = top.sywyar.pixivdownload.plugin.runtime.status.PluginStatusReport.empty();
        if (gate.safeToScan()) {
            try {
                report = plugins.report();
            } catch (RuntimeException failure) {
                errors.add(failure.toString());
            }
        }
        var guidance = RecoveryGuidance.from(gate, guiFailure, report, errors);
        return ResponseEntity.ok(new RecoveryView(active, recoveryMode, noGui,
                active && NetworkUtils.isTrustedLocalRequest(request),
                !gate.safeToScan(),
                guidance.adviceKeys().stream().map(key -> messages.get(locales.resolveLocale(request), key))
                        .collect(java.util.stream.Collectors.joining("\n\n")),
                guidance.localizedErrors(key -> messages.get(locales.resolveLocale(request), key)),
                RecoveryPluginFocus.from(gate.safeToScan(), noGui, report, failures.keySet())));
    }

    @PostMapping("/restart")
    public ResponseEntity<?> restart(HttpServletRequest request) {
        return act(request, application::requestRestart);
    }

    @PostMapping("/exit")
    public ResponseEntity<?> exit(HttpServletRequest request) {
        return act(request, application::requestExit);
    }

    private ResponseEntity<?> act(HttpServletRequest request, BooleanSupplier action) {
        if (!NetworkUtils.isTrustedLocalRequest(request)) return error(request, 403, "recovery.action.local-only");
        try {
            if (desktopFailure.get() == null && !recovery.isActive())
                return error(request, 409, "recovery.action.unavailable");
        } catch (RuntimeException failure) {
            return error(request, 503, "recovery.action.failed");
        }
        if (!action.getAsBoolean()) return error(request, 503, "recovery.action.failed");
        return ResponseEntity.ok(new ActionResult(true));
    }

    private ResponseEntity<?> error(HttpServletRequest request, int status, String code) {
        return ResponseEntity.status(status).body(ApiErrorResponse.of(code,
                messages.get(locales.resolveLocale(request), code)));
    }

    public record ActionResult(boolean accepted) { }
    public record RecoveryView(boolean active, boolean recoveryMode, boolean desktopUnavailable,
            boolean actionsAllowed, boolean installationBlocked, String advice, List<String> errors,
            RecoveryPluginFocus focus) { }
}
