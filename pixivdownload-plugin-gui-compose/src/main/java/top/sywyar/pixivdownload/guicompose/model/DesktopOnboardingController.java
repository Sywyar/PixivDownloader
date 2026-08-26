package top.sywyar.pixivdownload.guicompose.model;

import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiPluginSnapshot;
import top.sywyar.pixivdownload.plugin.api.gui.GuiOnboardingStepContribution;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.InputKind;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.TextStyle;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.TextToken;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static top.sywyar.pixivdownload.guicompose.model.DesktopUiNodes.*;
import static top.sywyar.pixivdownload.guicompose.model.GuiActionResponseSafety.responseDetail;

/**
 * 首次引导的状态、页面与提交动作。
 */
final class DesktopOnboardingController {
    private static final int STEP_SERVICE = 1;
    private static final int STEP_CONFIG = 2;
    private static final int STEP_PROXY = 3;
    private static final int STEP_START = 4;
    private static final int STEP_GUIDE = 5;
    private static final int STEP_ADVANCED = 6;
    private static final int STEP_DONE = 7;

    private final ComposeDesktopUiModel owner;
    private final DesktopUiHost host;
    private final String rootFolder;
    private final Map<String, String> formValues;

    private volatile String welcomeNotice = "";
    private volatile TextStyle welcomeNoticeStyle = TextStyle.ERROR;
    private volatile long welcomeFormRevision;
    private volatile int welcomeStep;
    private volatile boolean weakPasswordConfirmationPending;
    private volatile boolean batchVisited;
    private volatile Set<String> completedSteps = Set.of();

    DesktopOnboardingController(
            ComposeDesktopUiModel owner,
            DesktopUiHost host,
            String rootFolder,
            Map<String, String> formValues
    ) {
        this.owner = owner;
        this.host = host;
        this.rootFolder = rootFolder;
        this.formValues = formValues;
        initializeProxyDefaults(
                formValues,
                host.defaultProxyHost(),
                host.defaultProxyPort()
        );
        this.welcomeStep = initialWelcomeStep();
    }

    void passwordChanged() {
        weakPasswordConfirmationPending = false;
    }

    DesktopUiNode controlCenterPage(
            Map<String, Runnable> nextActions
    ) {
        return switch (welcomeStep) {
            case STEP_SERVICE -> welcomeServiceStep(nextActions);
            case STEP_CONFIG -> welcomeConfigStep(nextActions);
            case STEP_PROXY -> welcomeProxyStep(nextActions);
            case STEP_START -> welcomeStartStep(nextActions);
            case STEP_GUIDE -> welcomeGuideStep(nextActions);
            case STEP_ADVANCED -> welcomeAdvancedStep(nextActions);
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
                    "desktop.ui.onboarding.account.point.credentials"
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
            content.add(secondary(
                    "welcome.config.change",
                    "desktop.ui.onboarding.account.point.change"
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
                "desktop.ui.onboarding.account.title",
                "desktop.ui.onboarding.account.body",
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
        content.add(secondary(
                "welcome.proxy.change",
                "desktop.ui.onboarding.proxy.point.change"
        ));
        return welcomeStep(
                "welcome.proxy",
                "gui.welcome.proxy.title",
                "desktop.ui.onboarding.proxy.body",
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
        List<DesktopUiNode> content = new ArrayList<>(List.of(
                bullet("welcome.start.kinds", "gui.welcome.start.point.kinds"),
                bullet("welcome.start.keepopen", "gui.welcome.start.point.keepopen"),
                bullet("welcome.start.formats", "gui.welcome.start.point.formats"),
                button(
                        "welcome.start.open",
                        "welcome.start.open",
                        "gui.welcome.start.button",
                        !owner.busy(),
                        nextActions,
                        () -> owner.openWeb("/pixiv-batch.html")
                ),
                secondary("welcome.start.waiting", "gui.welcome.start.waiting")
        ));
        return welcomeStep(
                "welcome.start",
                "gui.welcome.start.title",
                "gui.welcome.start.body",
                content,
                endRow(
                        "welcome.start.actions",
                        backWelcomeButton("welcome.start.back", STEP_PROXY, nextActions),
                        nextWelcomeButton(
                                "welcome.start.next",
                                stepAfterStart(),
                                nextActions
                        )
                )
        );
    }

    private DesktopUiNode welcomeGuideStep(Map<String, Runnable> nextActions) {
        GuiOnboardingStepContribution step = guideStep();
        if (step == null) return welcomeAdvancedStep(nextActions);
        List<DesktopUiNode> content = new ArrayList<>();
        for (int index = 0; index < step.bulletKeys().size(); index++) {
            content.add(new DesktopUiNode.Text(
                    "welcome.guide.bullet." + index,
                    token(step.i18nNamespace(), step.bulletKeys().get(index), step.bulletKeys().get(index)),
                    TextStyle.BULLET,
                    true,
                    false
            ));
        }
        String openAction = "welcome.guide.open";
        nextActions.put(openAction, () -> owner.openWeb(step.actionHref()));
        content.add(new DesktopUiNode.Button(
                "welcome.guide.open",
                openAction,
                token(step.i18nNamespace(), step.actionLabelKey(), step.actionLabelKey()),
                null,
                DesktopUiNode.ButtonStyle.NORMAL,
                !owner.busy()
        ));
        content.add(new DesktopUiNode.Text(
                "welcome.guide.waiting",
                token(step.i18nNamespace(), step.waitingKey(), step.waitingKey()),
                TextStyle.SECONDARY,
                true,
                false
        ));
        return welcomeStep(
                "welcome.guide",
                token(step.i18nNamespace(), step.titleKey(), step.titleKey()),
                token(step.i18nNamespace(), step.bodyKey(), step.bodyKey()),
                content,
                endRow(
                        "welcome.guide.actions",
                        backWelcomeButton("welcome.guide.back", STEP_START, nextActions),
                        button(
                                "welcome.guide.finish",
                                "welcome.guide.finish",
                                "gui.welcome.nav.finish",
                                !owner.busy(),
                                nextActions,
                                this::finishGuideStep
                        )
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
                        text("welcome.advanced.scripts.title", "gui.welcome.scripts.title", TextStyle.HEADING),
                        text("welcome.advanced.scripts.intro", "gui.welcome.scripts.intro", TextStyle.BODY),
                        bullet("welcome.advanced.scripts.page", "gui.welcome.scripts.point.page"),
                        bullet("welcome.advanced.scripts.toolbox", "gui.welcome.scripts.point.toolbox"),
                        text("welcome.advanced.scripts.install", "gui.welcome.scripts.install", TextStyle.BODY),
                        text("welcome.advanced.ffmpeg.title", "gui.welcome.ffmpeg.title", TextStyle.HEADING),
                        text("welcome.advanced.ffmpeg.intro", "gui.welcome.ffmpeg.intro", TextStyle.BODY),
                        new DesktopUiNode.Text(
                                "welcome.advanced.ffmpeg.state",
                                appToken(
                                        "gui.welcome.ffmpeg.state",
                                        host.message(ffmpegReady
                                                ? "gui.welcome.ffmpeg.state.ready"
                                                : "gui.welcome.ffmpeg.state.missing")
                                ),
                                ffmpegReady ? TextStyle.SUCCESS : TextStyle.WARNING,
                                true,
                                false
                        ),
                        text("welcome.advanced.ffmpeg.install", "gui.welcome.ffmpeg.install", TextStyle.BODY),
                        text("welcome.advanced.reopen.title", "gui.welcome.done.reopen.title", TextStyle.HEADING),
                        text("welcome.advanced.reopen", "gui.welcome.done.reopen", TextStyle.BODY)
                ),
                endRow(
                        "welcome.advanced.actions",
                        backWelcomeButton(
                                "welcome.advanced.back",
                                guideStep() == null ? STEP_START : STEP_GUIDE,
                                nextActions
                        ),
                        nextWelcomeButton("welcome.advanced.next", STEP_DONE, nextActions)
                )
        );
    }

    private DesktopUiNode welcomeDoneStep(Map<String, Runnable> nextActions) {
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
                            backWelcomeButton("welcome.done.back", STEP_ADVANCED, nextActions),
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
                welcomeFooter(id, actions),
                null,
                null
        );
    }

    private DesktopUiNode welcomeFooter(String id, DesktopUiNode actions) {
        if (welcomeNotice.isBlank()) return actions;
        return column(
                id + ".footer",
                raw(id + ".notice", welcomeNotice, welcomeNoticeStyle),
                actions
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
        welcomeStep = Math.max(STEP_SERVICE, Math.min(STEP_DONE, target));
        welcomeNotice = "";
        welcomeNoticeStyle = TextStyle.ERROR;
        host.saveOnboardingProgress(welcomeStep);
        owner.rebuild();
    }

    private int initialWelcomeStep() {
        DesktopUiHost.OnboardingSnapshot onboarding = host.onboardingState(rootFolder);
        int required = hostSetupWelcomeStep(onboarding);
        if (required < STEP_START) return required;
        return Math.max(STEP_START, Math.min(STEP_DONE, onboarding.progress()));
    }

    private int hostSetupWelcomeStep(DesktopUiHost.OnboardingSnapshot onboarding) {
        if (owner.backendSnapshot().state() != DesktopUiHost.BackendState.RUNNING) return STEP_SERVICE;
        if (!onboarding.setupComplete()) return STEP_CONFIG;
        return onboarding.proxyConfigured() ? STEP_START : STEP_PROXY;
    }

    private void submitSetup() {
        String username = form("welcome.username", "").trim();
        String password = form("welcome.password", "");
        if (username.isBlank()) {
            setWelcomeNotice(host.message("gui.welcome.config.invalid.username"), TextStyle.ERROR);
            owner.rebuild();
            return;
        }
        if (password.length() < host.minimumPasswordLength()) {
            setWelcomeNotice(host.message("gui.welcome.config.invalid.password"), TextStyle.ERROR);
            owner.rebuild();
            return;
        }
        if (password.length() < host.recommendedPasswordLength() && !weakPasswordConfirmationPending) {
            weakPasswordConfirmationPending = true;
            setWelcomeNotice(
                    host.message("gui.welcome.config.password-warning.message"),
                    TextStyle.ERROR
            );
            owner.rebuild();
            return;
        }
        weakPasswordConfirmationPending = false;
        setWelcomeNotice(host.message("gui.welcome.config.submitting"), TextStyle.EMPHASIS);
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
                goWelcomeStep(STEP_PROXY);
            } else {
                setWelcomeNotice(
                        host.message("gui.welcome.config.failed", responseDetail(response)),
                        TextStyle.ERROR
                );
            }
        });
    }

    private void saveWelcomeProxy() {
        String hostValue = form("welcome.proxy.host", "").trim();
        int port = intForm("welcome.proxy.port", 0);
        boolean enabled = boolForm("welcome.proxy.enabled", true);
        if (enabled && hostValue.isBlank()) {
            setWelcomeNotice(host.message("gui.welcome.proxy.invalid.host"), TextStyle.ERROR);
            owner.rebuild();
            return;
        }
        if (enabled && (port < 1 || port > 65_535)) {
            setWelcomeNotice(host.message("gui.welcome.proxy.invalid.port"), TextStyle.ERROR);
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
                goWelcomeStep(STEP_START);
            } catch (Exception failure) {
                setWelcomeNotice(
                        host.message("gui.welcome.proxy.failed", safeMessage(failure)),
                        TextStyle.ERROR
                );
            }
        });
    }

    private void finishGuideStep() {
        GuiOnboardingStepContribution step = guideStep();
        if (step != null) {
            Set<String> next = new LinkedHashSet<>(completedSteps);
            next.add(step.completionKey());
            completedSteps = Set.copyOf(next);
        }
        host.markOnboardingSeen();
        goWelcomeStep(STEP_ADVANCED);
    }

    private void finishOnboarding() {
        if (!host.onboardingState(rootFolder).setupComplete()) {
            setWelcomeNotice(host.message("gui.welcome.config.waiting"), TextStyle.ERROR);
            welcomeStep = STEP_CONFIG;
            owner.rebuild();
            return;
        }
        host.markOnboardingSeen();
        host.markOnboardingFinished();
        owner.rebuild();
    }

    void refreshState() {
        DesktopUiHost.OnboardingSnapshot onboarding = host.onboardingState(rootFolder);
        if (onboarding.finished()) return;
        int next = welcomeStep;
        if (next == STEP_SERVICE) next = hostSetupWelcomeStep(onboarding);
        if (next >= STEP_ADVANCED) return;
        if (owner.backendSnapshot().state() == DesktopUiHost.BackendState.RUNNING) {
            try {
                DesktopUiHost.GuiResponse response = host.guiGet("onboarding", 2_000);
                if (response.is2xx() && response.body() != null) {
                    batchVisited |= response.body().path("batchVisited").asBoolean(false);
                    completedSteps = completedSteps(response.body().path("completedSteps"));
                }
            } catch (RuntimeException ignored) {
                // 后端轮询会继续重试；保留上次已观测到的进度。
            }
        }
        if (next == STEP_START && batchVisited) next = stepAfterStart();
        if (next == STEP_GUIDE && guideStepComplete()) {
            host.markOnboardingSeen();
            next = STEP_ADVANCED;
        }
        if (next != welcomeStep) {
            welcomeStep = next;
            host.saveOnboardingProgress(next);
        }
    }

    private int stepAfterStart() {
        return guideStep() == null ? STEP_ADVANCED : STEP_GUIDE;
    }

    private boolean guideStepComplete() {
        GuiOnboardingStepContribution step = guideStep();
        return step == null || completedSteps.contains(step.completionKey());
    }

    private GuiOnboardingStepContribution guideStep() {
        return firstGuideStep(owner.currentSources());
    }

    static GuiOnboardingStepContribution firstGuideStep(List<DesktopUiPluginSnapshot> sources) {
        return sources.stream()
                .flatMap(source -> source.onboardingSteps().stream())
                .filter(DesktopOnboardingController::validGuideStep)
                .sorted(Comparator.comparingInt(GuiOnboardingStepContribution::order)
                        .thenComparing(GuiOnboardingStepContribution::stepId))
                .findFirst()
                .orElse(null);
    }

    private static boolean validGuideStep(GuiOnboardingStepContribution step) {
        return step != null
                && validId(step.stepId())
                && validId(step.i18nNamespace())
                && validId(step.titleKey())
                && validId(step.bodyKey())
                && step.bulletKeys().stream().allMatch(DesktopUiNodes::validId)
                && validId(step.actionLabelKey())
                && step.actionHref() != null
                && step.actionHref().startsWith("/")
                && validId(step.waitingKey())
                && validId(step.completionKey());
    }

    private static Set<String> completedSteps(DesktopUiHost.GuiValue node) {
        if (node == null || !node.isArray()) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        for (DesktopUiHost.GuiValue item : node) {
            if (item != null && item.isTextual() && !item.asText().isBlank()) {
                result.add(item.asText().trim());
            }
        }
        return Set.copyOf(result);
    }

    static void initializeProxyDefaults(
            Map<String, String> values,
            String hostValue,
            int port
    ) {
        values.putIfAbsent("welcome.proxy.enabled", "true");
        values.putIfAbsent("welcome.proxy.host", hostValue);
        values.putIfAbsent("welcome.proxy.port", Integer.toString(port));
    }

    private void setWelcomeNotice(String notice, TextStyle style) {
        welcomeNotice = notice;
        welcomeNoticeStyle = style;
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

}
