package top.sywyar.pixivdownload.plugin.recovery;

import top.sywyar.pixivdownload.plugin.api.web.HttpApiNullable;

import top.sywyar.pixivdownload.plugin.api.web.HttpApiAccess;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import top.sywyar.pixivdownload.plugin.api.web.HttpApiGroup;
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

import top.sywyar.pixivdownload.plugin.runtime.status.RecoveryModeDecision;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.function.BooleanSupplier;

/** ADMIN 路由和 CSRF 过滤器之外，恢复操作还要求本机请求和当下的恢复状态。 */
@HttpApiGroup("core-system")
@RestController
@RequestMapping("/api/plugins/recovery")
public class RecoveryActionController {
    private final RecoveryModeService recovery;
    private final top.sywyar.pixivdownload.core.db.DatabaseTarget database;
    private final PluginStatusService plugins;
    private final ApplicationRestartService application;
    private final AppMessages messages;
    private final AppLocaleResolver locales;
    private final java.util.function.Supplier<DesktopUiFailure> desktopFailure;

    @Autowired
    public RecoveryActionController(RecoveryModeService recovery, PluginStatusService plugins,
            ApplicationRestartService application, AppMessages messages, AppLocaleResolver locales,
            top.sywyar.pixivdownload.core.db.DatabaseTarget database) {
        this(recovery, plugins, application, messages, locales, database, GuiLauncher::desktopRecoveryFailure);
    }

    RecoveryActionController(RecoveryModeService recovery, PluginStatusService plugins,
            ApplicationRestartService application, AppMessages messages, AppLocaleResolver locales,
            top.sywyar.pixivdownload.core.db.DatabaseTarget database,
            java.util.function.Supplier<DesktopUiFailure> desktopFailure) {
        this.recovery = recovery;
        this.database = database;
        this.plugins = plugins;
        this.application = application;
        this.messages = messages;
        this.locales = locales;
        this.desktopFailure = desktopFailure;
    }

    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json",
            schema = @Schema(implementation = RecoveryView.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @GetMapping
    public ResponseEntity<?> status(HttpServletRequest request) {
        var guiFailure = desktopFailure.get();
        boolean noGui = guiFailure != null;
        var errors = new java.util.ArrayList<String>();
        RecoveryModeDecision decision;
        try {
            decision = recovery.decision();
        } catch (RuntimeException failure) {
            if (!noGui) return error(request, 503, "recovery.action.failed");
            decision = RecoveryModeDecision.operational();
            errors.add(failure.toString());
        }
        var gate = plugins.recoveryGateSnapshot();
        boolean recoveryMode = decision.active();
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
        var locale = locales.resolveLocale(request);
        var serviceAdvice = new LinkedHashMap<String, String>();
        var blockedProviders = new java.util.TreeSet<String>();
        if (gate.safeToScan()) {
            for (var reason : decision.reasons()) {
                var details = new java.util.ArrayList<String>();
                for (var provider : new java.util.TreeMap<>(reason.blockingProviders()).entrySet()) {
                    if (provider.getKey().equals(reason.pluginId())) continue;
                    blockedProviders.add(provider.getKey());
                    details.add(messages.get(locale, "plugin.recovery.blocking-provider", reason.pluginId(),
                            provider.getKey(), messages.get(locale, "FAILED".equals(provider.getValue())
                                    ? "gui.plugins.status.FAILED" : "gui.plugins.phase." + provider.getValue())));
                }
                if (reason.missingDependencyId() != null || "plugin.recovery.not-serving".equals(reason.messageKey()))
                    details.add(messages.get(locale, reason.messageKey(), reason.messageArguments()));
                if (!details.isEmpty()) serviceAdvice.put(reason.pluginId(), String.join("\n", details));
            }
        }
        var guidance = RecoveryGuidance.from(gate, guiFailure, report, errors,
                recovery.databaseUnavailable(), plugins.runtimeLoadFailures(), serviceAdvice);
        return ResponseEntity.ok(new RecoveryView(active, recoveryMode, noGui,
                active && NetworkUtils.isTrustedLocalRequest(request),
                !gate.safeToScan(),
                Stream.concat(serviceAdvice.values().stream(), guidance.adviceKeys().stream().map(key -> messages.get(locale, key)))
                        .distinct().collect(Collectors.joining("\n\n")),
                guidance.localizedErrors(key -> messages.get(locale, key)),
                RecoveryPluginFocus.from(gate.safeToScan(), noGui, recovery.databaseUnavailable(),
                        database.providerOwner(), report, failures.keySet(), serviceAdvice.keySet(), blockedProviders)));
    }

    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json",
            schema = @Schema(implementation = ActionResult.class)))
    @ApiResponse(responseCode = "403", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @HttpApiAccess(HttpApiAccess.Condition.LOCAL_ONLY)
    @PostMapping("/restart")
    public ResponseEntity<?> restart(HttpServletRequest request) {
        return act(request, application::requestRestart);
    }

    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json",
            schema = @Schema(implementation = ActionResult.class)))
    @ApiResponse(responseCode = "403", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @HttpApiAccess(HttpApiAccess.Condition.LOCAL_ONLY)
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
            boolean actionsAllowed, boolean installationBlocked, @HttpApiNullable String advice, List<String> errors,
            RecoveryPluginFocus focus) { }
}
