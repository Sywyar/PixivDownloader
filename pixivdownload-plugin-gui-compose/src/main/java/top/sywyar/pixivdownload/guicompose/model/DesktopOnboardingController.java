package top.sywyar.pixivdownload.guicompose.model;

import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.InputKind;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.TextStyle;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.TextToken;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static top.sywyar.pixivdownload.guicompose.model.DesktopUiNodes.*;
import static top.sywyar.pixivdownload.guicompose.model.GuiActionResponseSafety.responseDetail;

/**
 * 首次引导的状态、页面与提交动作。
 */
final class DesktopOnboardingController {
    private final ComposeDesktopUiModel owner;
    private final DesktopUiHost host;
    private final String rootFolder;
    private final Map<String, String> formValues;

    private volatile String welcomeNotice = "";
    private volatile long welcomeFormRevision;
    private volatile int welcomeStep;
    private volatile boolean weakPasswordConfirmationPending;

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
        this.welcomeStep = initialWelcomeStep();
    }

    void passwordChanged() {
        weakPasswordConfirmationPending = false;
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
            if (!welcomeNotice.isBlank())
                content.add(status("welcome.config.notice", welcomeNotice));
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
        if (!welcomeNotice.isBlank()) content.add(status(
                "welcome.proxy.notice",
                welcomeNotice
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

    private DesktopUiNode welcomeDoneStep(Map<String, Runnable> nextActions) {
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
        welcomeStep = Math.max(1, Math.min(4, target));
        welcomeNotice = "";
        host.saveOnboardingProgress(welcomeStep);
        owner.rebuild();
    }

    private int initialWelcomeStep() {
        DesktopUiHost.OnboardingSnapshot onboarding = host.onboardingState(rootFolder);
        return hostSetupWelcomeStep(onboarding);
    }

    private int hostSetupWelcomeStep(DesktopUiHost.OnboardingSnapshot onboarding) {
        if (owner.backendSnapshot().state() != DesktopUiHost.BackendState.RUNNING) return 1;
        if (!onboarding.setupComplete()) return 2;
        return onboarding.proxyConfigured() ? 4 : 3;
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
        int next = hostSetupWelcomeStep(host.onboardingState(rootFolder));
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

}
