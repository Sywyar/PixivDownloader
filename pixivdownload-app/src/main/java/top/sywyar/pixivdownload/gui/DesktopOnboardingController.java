package top.sywyar.pixivdownload.gui;

import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiExperienceProfile;
import top.sywyar.pixivdownload.plugin.api.gui.GuiOnboardingStepContribution;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode.ButtonStyle;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode.InputKind;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode.TextStyle;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode.TextToken;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static top.sywyar.pixivdownload.gui.DesktopUiNodes.*;
import static top.sywyar.pixivdownload.gui.GuiActionResponseSafety.responseDetail;

/**
 * 首次引导的状态、页面与提交动作。
 */
final class DesktopOnboardingController {
    private final AppDesktopUiModel owner;
    private final DesktopUiHost host;
    private final String rootFolder;
    private final AppDesktopUiModel.RendererContract rendererContract;
    private final Map<String, String> formValues;

    private volatile String welcomeNotice = "";
    private volatile long welcomeFormRevision;
    private volatile int welcomeStep;
    private volatile boolean onboardingBatchVisited;
    private volatile Set<String> completedOnboardingSteps = Set.of();
    private volatile boolean weakPasswordConfirmationPending;

    DesktopOnboardingController(
            AppDesktopUiModel owner,
            DesktopUiHost host,
            String rootFolder,
            AppDesktopUiModel.RendererContract rendererContract,
            Map<String, String> formValues
    ) {
        this.owner = owner;
        this.host = host;
        this.rootFolder = rootFolder;
        this.rendererContract = rendererContract;
        this.formValues = formValues;
        this.welcomeStep = initialWelcomeStep();
    }

    void passwordChanged() {
        weakPasswordConfirmationPending = false;
    }

    DesktopUiNode classicPage(Map<String, Runnable> nextActions) {
        int step = normalizeWelcomeStep(welcomeStep);
        if (step != welcomeStep) welcomeStep = step;
        return switch (step) {
            case 1 -> welcomeServiceStep(nextActions);
            case 2 -> welcomeConfigStep(nextActions);
            case 3 -> welcomeProxyStep(nextActions);
            case 4 -> welcomeStartStep(nextActions);
            case 5 -> welcomePluginStep(nextActions);
            case 6 -> welcomeAdvancedStep(nextActions);
            default -> welcomeDoneStep(nextActions);
        };
    }

    DesktopUiNode controlCenterPage(
            DesktopUiHost.OnboardingSnapshot onboarding,
            Map<String, Runnable> nextActions
    ) {
        int step = hostSetupWelcomeStep(onboarding);
        if (step != welcomeStep) welcomeStep = step;
        return switch (step) {
            case 1 -> welcomeServiceStep(nextActions);
            case 2 -> welcomeConfigStep(nextActions);
            case 3 -> welcomeProxyStep(nextActions);
            default -> welcomeDoneStep(nextActions);
        };
    }

    private DesktopUiNode welcomeServiceStep(Map<String, Runnable> nextActions) {
        List<DesktopUiNode> content = new ArrayList<>(List.of(
                raw(
                        "welcome.service.state",
                        owner.backendMessage(),
                        owner.backendSnapshot().state() == DesktopUiHost.BackendState.RUNNING ? TextStyle.SUCCESS : owner.backendSnapshot().state() == DesktopUiHost.BackendState.FAILED ? TextStyle.ERROR : TextStyle.WARNING
                ),
                bullet("welcome.service.point1", "gui.welcome.status.point1"),
                bullet("welcome.service.point2", "gui.welcome.status.point2")
        ));
        if (rendererContract.experienceProfile() == DesktopUiExperienceProfile.CLASSIC) {
            content.add(bullet("welcome.service.point3", "gui.welcome.status.point3"));
        }
        return welcomeStep(
                "welcome.service",
                "gui.welcome.status.title",
                "gui.welcome.status.subtitle",
                content,
                endRow(
                        "welcome.service.actions",
                        button(
                                "welcome.service.next",
                                "welcome.service.next",
                                "gui.welcome.nav.next",
                                owner.backendSnapshot().state() == DesktopUiHost.BackendState.RUNNING,
                                nextActions,
                                () -> goWelcomeStep(2)
                        )
                )
        );
    }

    private DesktopUiNode welcomeConfigStep(Map<String, Runnable> nextActions) {
        DesktopUiHost.OnboardingSnapshot onboarding = host.onboardingState(rootFolder);
        List<DesktopUiNode> content = new ArrayList<>();
        DesktopUiNode actions;
        if (onboarding.setupComplete()) {
            content.add(text(
                    "welcome.config.done",
                    "gui.welcome.config.done",
                    TextStyle.SUCCESS
            ));
            actions = endRow(
                    "welcome.config.actions",
                    backWelcomeButton("welcome.config.back", 1, nextActions),
                    nextWelcomeButton("welcome.config.next", 3, nextActions)
            );
        } else {
            content.add(bullet(
                    "welcome.config.account",
                    welcomeKey(
                            "gui.welcome.config.point.account",
                            "desktop.ui.onboarding.account.point.credentials"
                    )
            ));
            content.add(new DesktopUiNode.Form(
                    "welcome.config.form",
                    DesktopUiNode.FormStyle.COMPACT,
                    null,
                    List.of(
                            new DesktopUiNode.FormRow(
                                    "welcome.config.username",
                                    key("gui.welcome.config.username"),
                                    null,
                                    input(
                                            "welcome.username.input",
                                            "welcome.username",
                                            "gui.welcome.config.username",
                                            null,
                                            InputKind.TEXT,
                                            form("welcome.username", ""),
                                            !owner.busy()
                                    ),
                                    null
                            ),
                            new DesktopUiNode.FormRow(
                                    "welcome.config.password",
                                    key("gui.welcome.config.password"),
                                    null,
                                    new DesktopUiNode.TextInput(
                                            "welcome.password.input",
                                            "welcome.password",
                                            key("gui.welcome.config.password"),
                                            null,
                                            InputKind.PASSWORD,
                                            "",
                                            18,
                                            1,
                                            !owner.busy() && owner.backendSnapshot().state() == DesktopUiHost.BackendState.RUNNING,
                                            welcomeFormRevision
                                    ),
                                    null
                            )
                    )
            ));
            if (!welcomeNotice.isBlank())
                content.add(status("welcome.config.notice", welcomeNotice));
            content.add(secondary(
                    "welcome.config.change",
                    welcomeKey(
                            "gui.welcome.config.point.change",
                            "desktop.ui.onboarding.account.point.change"
                    )
            ));
            actions = endRow(
                    "welcome.config.actions",
                    backWelcomeButton("welcome.config.back", 1, nextActions),
                    button(
                            "welcome.config.submit",
                            "welcome.config.submit",
                            "gui.welcome.config.submit",
                            !owner.busy() && owner.backendSnapshot().state() == DesktopUiHost.BackendState.RUNNING,
                            nextActions,
                            this::submitSetup
                    )
            );
        }
        return welcomeStep(
                "welcome.config",
                welcomeKey("gui.welcome.config.title", "desktop.ui.onboarding.account.title"),
                welcomeKey("gui.welcome.config.body", "desktop.ui.onboarding.account.body"),
                content,
                actions
        );
    }

    private DesktopUiNode welcomeProxyStep(Map<String, Runnable> nextActions) {
        boolean enabled = boolForm("welcome.proxy.enabled", true);
        List<DesktopUiNode> content = new ArrayList<>();
        content.add(bullet("welcome.proxy.usage", "gui.welcome.proxy.point.usage"));
        content.add(bullet("welcome.proxy.docker", "gui.welcome.proxy.point.docker"));
        content.add(toggle(
                "welcome.proxy.enabled.input",
                "welcome.proxy.enabled",
                "gui.welcome.proxy.enabled",
                enabled,
                !owner.busy()
        ));
        content.add(new DesktopUiNode.Form(
                "welcome.proxy.form",
                DesktopUiNode.FormStyle.COMPACT,
                null,
                List.of(
                        new DesktopUiNode.FormRow(
                                "welcome.proxy.host",
                                key("gui.welcome.proxy.host"),
                                null,
                                input(
                                        "welcome.proxy.host.input",
                                        "welcome.proxy.host",
                                        "gui.welcome.proxy.host",
                                        null,
                                        InputKind.TEXT,
                                        form("welcome.proxy.host", host.defaultProxyHost()),
                                        !owner.busy() && enabled
                                ),
                                null
                        ),
                        new DesktopUiNode.FormRow(
                                "welcome.proxy.port",
                                key("gui.welcome.proxy.port"),
                                null,
                                input(
                                        "welcome.proxy.port.input",
                                        "welcome.proxy.port",
                                        "gui.welcome.proxy.port",
                                        null,
                                        InputKind.NUMBER,
                                        form("welcome.proxy.port", Integer.toString(host.defaultProxyPort())),
                                        !owner.busy() && enabled
                                ),
                                null
                        )
                )
        ));
        if (!welcomeNotice.isBlank()) content.add(status(
                "welcome.proxy.notice",
                welcomeNotice
        ));
        content.add(secondary(
                "welcome.proxy.change",
                welcomeKey(
                        "gui.welcome.proxy.point.change",
                        "desktop.ui.onboarding.proxy.point.change"
                )
        ));
        return welcomeStep(
                "welcome.proxy",
                "gui.welcome.proxy.title",
                welcomeKey("gui.welcome.proxy.body", "desktop.ui.onboarding.proxy.body"),
                content,
                endRow(
                        "welcome.proxy.actions",
                        backWelcomeButton("welcome.proxy.back", 2, nextActions),
                        button(
                                "welcome.proxy.next",
                                "welcome.proxy.next",
                                "gui.welcome.nav.next",
                                !owner.busy(),
                                nextActions,
                                this::saveWelcomeProxy
                        )
                )
        );
    }

    private DesktopUiNode welcomeStartStep(Map<String, Runnable> nextActions) {
        return welcomeStep(
                "welcome.start",
                "gui.welcome.start.title",
                "gui.welcome.start.body",
                List.of(
                        bullet("welcome.start.kinds", "gui.welcome.start.point.kinds"),
                        bullet("welcome.start.keepopen", "gui.welcome.start.point.keepopen"),
                        bullet("welcome.start.formats", "gui.welcome.start.point.formats"),
                        button(
                                "welcome.start.open",
                                "welcome.start.open",
                                "gui.welcome.start.button",
                                true,
                                nextActions,
                                () -> owner.openWeb("/pixiv-batch.html")
                        ),
                        secondary("welcome.start.waiting", "gui.welcome.start.waiting")
                ),
                endRow(
                        "welcome.start.actions",
                        backWelcomeButton("welcome.start.back", 3, nextActions),
                        nextWelcomeButton(
                                "welcome.start.next",
                                onboardingPluginStep().isPresent() ? 5 : 6,
                                nextActions
                        )
                )
        );
    }

    private DesktopUiNode welcomePluginStep(Map<String, Runnable> nextActions) {
        Optional<PluginOnboardingStep> selected = onboardingPluginStep();
        if (selected.isEmpty()) return welcomeAdvancedStep(nextActions);
        PluginOnboardingStep entry = selected.orElseThrow();
        GuiOnboardingStepContribution step = entry.step();
        String base = "welcome.plugin." + safeId(step.stepId());
        List<DesktopUiNode> nodes = new ArrayList<>();
        int index = 0;
        for (String bullet : step.bulletKeys())
            nodes.add(new DesktopUiNode.Text(
                    base + ".bullet." + index++,
                    token(step.i18nNamespace(), bullet, bullet),
                    TextStyle.BULLET,
                    true,
                    true
            ));
        String openAction = base + ".open";
        nextActions.put(openAction, () -> owner.openWeb(step.actionHref()));
        nodes.add(new DesktopUiNode.Button(
                base + ".open.button",
                openAction,
                token(step.i18nNamespace(), step.actionLabelKey(), step.actionLabelKey()),
                null,
                ButtonStyle.NORMAL,
                true
        ));
        nodes.add(new DesktopUiNode.Text(
                base + ".waiting",
                token(step.i18nNamespace(), step.waitingKey(), step.waitingKey()),
                TextStyle.SECONDARY,
                true,
                true
        ));
        return welcomeStep(
                base,
                token(step.i18nNamespace(), step.titleKey(), step.titleKey()),
                token(step.i18nNamespace(), step.bodyKey(), step.bodyKey()),
                nodes,
                endRow(
                        base + ".actions",
                        backWelcomeButton(base + ".back", 4, nextActions),
                        nextWelcomeButton(base + ".finish", 6, nextActions)
                )
        );
    }

    private DesktopUiNode welcomeAdvancedStep(Map<String, Runnable> nextActions) {
        boolean ffmpegReady = host.locateFfmpeg().isPresent();
        return welcomeStep(
                "welcome.advanced",
                "gui.welcome.advanced.title",
                "gui.welcome.advanced.body",
                List.of(
                        text(
                                "welcome.scripts.title",
                                "gui.welcome.scripts.title",
                                TextStyle.HEADING
                        ),
                        secondary("welcome.scripts.intro", "gui.welcome.scripts.intro"),
                        bullet("welcome.scripts.page", "gui.welcome.scripts.point.page"),
                        bullet("welcome.scripts.toolbox", "gui.welcome.scripts.point.toolbox"),
                        secondary("welcome.scripts.install", "gui.welcome.scripts.install"),
                        text("welcome.ffmpeg.title", "gui.welcome.ffmpeg.title", TextStyle.HEADING),
                        secondary("welcome.ffmpeg.intro", "gui.welcome.ffmpeg.intro"),
                        raw(
                                "welcome.ffmpeg.state",
                                host.message(
                                        "gui.welcome.ffmpeg.state",
                                        host.message(ffmpegReady ? "gui.welcome.ffmpeg.state.ready" : "gui.welcome.ffmpeg.state.missing")
                                ),
                                ffmpegReady ? TextStyle.SUCCESS : TextStyle.WARNING
                        ),
                        secondary("welcome.ffmpeg.install", "gui.welcome.ffmpeg.install"),
                        text(
                                "welcome.reopen.title",
                                "gui.welcome.done.reopen.title",
                                TextStyle.HEADING
                        ),
                        secondary("welcome.reopen.body", "gui.welcome.done.reopen")
                ),
                endRow(
                        "welcome.advanced.actions",
                        backWelcomeButton(
                                "welcome.advanced.back",
                                onboardingPluginStep().isPresent() ? 5 : 4,
                                nextActions
                        ),
                        nextWelcomeButton("welcome.advanced.next", 7, nextActions)
                )
        );
    }

    private DesktopUiNode welcomeDoneStep(Map<String, Runnable> nextActions) {
        if (rendererContract.experienceProfile() == DesktopUiExperienceProfile.CONTROL_CENTER) {
            return welcomeStep(
                    "welcome.done",
                    "gui.welcome.done.title",
                    "desktop.ui.onboarding.done.body",
                    List.of(
                            bullet(
                                    "welcome.done.account",
                                    "desktop.ui.onboarding.done.point.account"
                            ),
                            bullet("welcome.done.proxy", "desktop.ui.onboarding.done.point.proxy")
                    ),
                    endRow(
                            "welcome.done.actions",
                            backWelcomeButton("welcome.done.back", 3, nextActions),
                            button(
                                    "welcome.done.finish",
                                    "welcome.done.finish",
                                    "desktop.ui.onboarding.done.button",
                                    !owner.busy(),
                                    nextActions,
                                    this::finishOnboarding
                            )
                    )
            );
        }
        return welcomeStep(
                "welcome.done",
                "gui.welcome.done.title",
                "gui.welcome.done.body",
                List.of(
                        bullet("welcome.done.start", "gui.welcome.done.point.start"),
                        bullet("welcome.done.advanced", "gui.welcome.done.point.advanced")
                ),
                endRow(
                        "welcome.done.actions",
                        backWelcomeButton("welcome.done.back", 6, nextActions),
                        button(
                                "welcome.done.finish",
                                "welcome.done.finish",
                                "gui.welcome.done.button",
                                !owner.busy(),
                                nextActions,
                                this::finishOnboarding
                        )
                )
        );
    }

    private DesktopUiNode welcomeStep(
            String id,
            String titleKey,
            String bodyKey,
            List<? extends DesktopUiNode> content,
            DesktopUiNode actions
    ) {
        return welcomeStep(
                id,
                key(titleKey),
                key(bodyKey),
                content,
                actions
        );
    }

    private DesktopUiNode welcomeStep(
            String id,
            TextToken title,
            TextToken body,
            List<? extends DesktopUiNode> content,
            DesktopUiNode actions
    ) {
        return new DesktopUiNode.Dock(
                id + ".layout",
                16,
                column(
                        id + ".header",
                        List.of(
                                new DesktopUiNode.Text(
                                        id + ".title",
                                        title,
                                        TextStyle.TITLE,
                                        true,
                                        false
                                ),
                                new DesktopUiNode.Text(
                                        id + ".body",
                                        body,
                                        TextStyle.SECONDARY,
                                        true,
                                        false
                                )
                        )
                ),
                scroll(id + ".scroll", column(id + ".content", content)),
                actions,
                null,
                null
        );
    }

    private DesktopUiNode.Button backWelcomeButton(
            String id,
            int target,
            Map<String, Runnable> nextActions
    ) {
        return button(
                id,
                id,
                "gui.welcome.nav.prev",
                !owner.busy(),
                nextActions,
                () -> goWelcomeStep(target)
        );
    }

    private DesktopUiNode.Button nextWelcomeButton(
            String id,
            int target,
            Map<String, Runnable> nextActions
    ) {
        return button(
                id,
                id,
                "gui.welcome.nav.next",
                !owner.busy(),
                nextActions,
                () -> goWelcomeStep(target)
        );
    }

    private void goWelcomeStep(int target) {
        welcomeStep = normalizeWelcomeStep(Math.max(1, Math.min(7, target)));
        welcomeNotice = "";
        host.saveOnboardingProgress(welcomeStep);
        owner.rebuild();
    }

    private int normalizeWelcomeStep(int step) {
        return step == 5 && onboardingPluginStep().isEmpty() ? 6 : step;
    }

    private String welcomeKey(String classicKey, String controlCenterKey) {
        return rendererContract.experienceProfile() == DesktopUiExperienceProfile.CONTROL_CENTER ? controlCenterKey : classicKey;
    }

    private int initialWelcomeStep() {
        DesktopUiHost.OnboardingSnapshot onboarding = host.onboardingState(rootFolder);
        if (rendererContract.experienceProfile() == DesktopUiExperienceProfile.CONTROL_CENTER) {
            return hostSetupWelcomeStep(onboarding);
        }
        int incomplete = owner.backendSnapshot().state() == DesktopUiHost.BackendState.RUNNING ? !onboarding.setupComplete() ? 2 : !onboarding.proxyConfigured() ? 3 : 4 : 1;
        return normalizeWelcomeStep(Math.max(
                incomplete,
                Math.max(1, Math.min(7, onboarding.progress()))
        ));
    }

    private int hostSetupWelcomeStep(DesktopUiHost.OnboardingSnapshot onboarding) {
        if (owner.backendSnapshot().state() != DesktopUiHost.BackendState.RUNNING) return 1;
        if (!onboarding.setupComplete()) return 2;
        return onboarding.proxyConfigured() ? 4 : 3;
    }

    private Optional<PluginOnboardingStep> onboardingPluginStep() {
        Map<String, GuiOnboardingStepContribution> unique = new LinkedHashMap<>();
        Set<String> duplicates = new LinkedHashSet<>();
        for (DesktopUiPluginSource source : owner.currentSources()) {
            try {
                List<GuiOnboardingStepContribution> steps = source.plugin().guiOnboardingSteps();
                if (steps == null) continue;
                for (GuiOnboardingStepContribution step : steps) {
                    if (step == null || !validOnboardingStep(step) || duplicates.contains(step.stepId()))
                        continue;
                    if (unique.putIfAbsent(step.stepId(), step) != null) {
                        unique.remove(step.stepId());
                        duplicates.add(step.stepId());
                    }
                }
            } catch (RuntimeException ignored) {
                // 隔离可选插件的引导异常。
            }
        }
        return unique.values().stream().sorted(Comparator.comparingInt(GuiOnboardingStepContribution::order).thenComparing(
                GuiOnboardingStepContribution::stepId)).findFirst().map(PluginOnboardingStep::new);
    }

    private static boolean validOnboardingStep(GuiOnboardingStepContribution step) {
        return validId(step.stepId()) && validId(step.i18nNamespace()) && validId(step.titleKey()) && validId(
                step.bodyKey()) && validId(step.actionLabelKey()) && safeHref(step.actionHref()) && validId(
                step.waitingKey()) && validId(step.completionKey()) && step.bulletKeys().stream().allMatch(
                DesktopUiNodes::validId);
    }

    private void submitSetup() {
        String username = form("welcome.username", "").trim();
        String password = form("welcome.password", "");
        if (username.isBlank()) {
            welcomeNotice = host.message("gui.welcome.config.invalid.username");
            owner.rebuild();
            return;
        }
        if (password.length() < host.minimumPasswordLength()) {
            welcomeNotice = host.message("gui.welcome.config.invalid.password");
            owner.rebuild();
            return;
        }
        if (password.length() < host.recommendedPasswordLength() && !weakPasswordConfirmationPending) {
            weakPasswordConfirmationPending = true;
            welcomeNotice = host.message("gui.welcome.config.password-warning.message");
            owner.rebuild();
            return;
        }
        weakPasswordConfirmationPending = false;
        welcomeNotice = host.message("gui.welcome.config.submitting");
        owner.runBusy(() -> {
            DesktopUiHost.GuiResponse response = host.guiPostJson(
                    "setup/init",
                    Map.of(
                            "username",
                            username,
                            "password",
                            password,
                            "mode",
                            "solo"
                    ),
                    5_000
            );
            if (response.is2xx()) {
                formValues.remove("welcome.password");
                welcomeFormRevision++;
                goWelcomeStep(3);
            } else {
                welcomeNotice = host.message(
                        "gui.welcome.config.failed",
                        responseDetail(response)
                );
            }
        });
    }

    private void saveWelcomeProxy() {
        String hostValue = form("welcome.proxy.host", "").trim();
        int port = intForm("welcome.proxy.port", 0);
        boolean enabled = boolForm("welcome.proxy.enabled", true);
        if (enabled && hostValue.isBlank()) {
            welcomeNotice = host.message("gui.welcome.proxy.invalid.host");
            owner.rebuild();
            return;
        }
        if (enabled && (port < 1 || port > 65_535)) {
            welcomeNotice = host.message("gui.welcome.proxy.invalid.port");
            owner.rebuild();
            return;
        }
        if (!enabled && hostValue.isBlank()) hostValue = host.defaultProxyHost();
        if (!enabled && (port < 1 || port > 65_535)) port = host.defaultProxyPort();
        String savedHost = hostValue;
        int savedPort = port;
        owner.runBusy(() -> {
            try {
                host.applicationConfig().writeAll(Map.of(
                        "proxy.enabled",
                        Boolean.toString(enabled),
                        "proxy.host",
                        savedHost,
                        "proxy.port",
                        Integer.toString(savedPort)
                ));
                host.markOnboardingProxyConfigured();
                host.guiPostJson(
                        "config/reload",
                        Map.of(
                                "changedKeys",
                                List.of("proxy.enabled", "proxy.host", "proxy.port")
                        ),
                        5_000
                );
                goWelcomeStep(4);
            } catch (Exception failure) {
                welcomeNotice = host.message("gui.welcome.proxy.failed", safeMessage(failure));
            }
        });
    }

    private void finishOnboarding() {
        if (!host.onboardingState(rootFolder).setupComplete()) {
            welcomeNotice = host.message("gui.welcome.config.waiting");
            welcomeStep = 2;
            owner.rebuild();
            return;
        }
        host.markOnboardingSeen();
        host.markOnboardingFinished();
        owner.rebuild();
    }

    void refreshState() {
        if (rendererContract.experienceProfile() == DesktopUiExperienceProfile.CONTROL_CENTER) {
            int next = hostSetupWelcomeStep(host.onboardingState(rootFolder));
            if (next != welcomeStep) {
                welcomeStep = next;
                host.saveOnboardingProgress(next);
            }
            return;
        }
        DesktopUiHost.GuiResponse response = host.guiGet("onboarding", 2_000);
        if (!response.is2xx() || response.body() == null) return;
        onboardingBatchVisited |= response.body().path("batchVisited").asBoolean(false);
        Set<String> completed = new LinkedHashSet<>();
        for (DesktopUiHost.GuiValue value : response.body().path("completedSteps")) {
            if (value != null && value.isTextual() && !value.asText().isBlank())
                completed.add(value.asText().trim());
        }
        completedOnboardingSteps = Set.copyOf(completed);
        int next = welcomeStep;
        if (next == 1 && owner.backendSnapshot().state() == DesktopUiHost.BackendState.RUNNING)
            next = 2;
        if (next == 4 && onboardingBatchVisited) next = onboardingPluginStep().isPresent() ? 5 : 6;
        Optional<PluginOnboardingStep> pluginStep = onboardingPluginStep();
        if (next == 5 && pluginStep.isPresent() && completedOnboardingSteps.contains(pluginStep.orElseThrow().step().completionKey())) {
            host.markOnboardingSeen();
            next = 6;
        }
        if (next != welcomeStep) {
            welcomeStep = next;
            host.saveOnboardingProgress(next);
        }
    }

    private String form(String key, String fallback) {
        return formValues.getOrDefault(key, fallback);
    }

    private boolean boolForm(String key, boolean fallback) {
        return Boolean.parseBoolean(form(key, Boolean.toString(fallback)));
    }

    private int intForm(String key, int fallback) {
        return parseInt(form(key, null), fallback);
    }

    private record PluginOnboardingStep(GuiOnboardingStepContribution step) {
    }
}
