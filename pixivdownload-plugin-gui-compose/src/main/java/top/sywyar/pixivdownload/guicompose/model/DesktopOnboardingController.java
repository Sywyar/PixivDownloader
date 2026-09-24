package top.sywyar.pixivdownload.guicompose.model;

import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiPluginSnapshot;
import top.sywyar.pixivdownload.plugin.api.gui.GuiOnboardingStepContribution;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiDocument;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.InputKind;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.TextStyle;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.TextToken;

import java.util.ArrayList;
import java.util.Comparator;
import java.net.URI;
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
    private static final int STEP_HUB = 3;

    private final ComposeDesktopUiModel owner;
    private final DesktopUiHost host;
    private final String rootFolder;
    private final Map<String, String> formValues;

    private volatile String welcomeNotice = "";
    private volatile TextStyle welcomeNoticeStyle = TextStyle.ERROR;
    private volatile long welcomeFormRevision;
    private volatile int proxyValidationAttempt;
    private volatile int welcomeStep;
    private volatile boolean weakPasswordConfirmationPending;
    private volatile boolean ffmpegReady;
    private volatile boolean submitting;
    private final Set<String> openedCards = java.util.concurrent.ConcurrentHashMap.newKeySet();

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
        try {
            host.applicationConfig().readAll(List.of("proxy.enabled", "proxy.host", "proxy.port"))
                    .forEach((key, value) -> formValues.put("welcome." + key, value));
        } catch (java.io.IOException ignored) {
            // 配置暂不可读时保留宿主默认值，只有显式保存才写入。
        }
        this.welcomeStep = initialWelcomeStep();
    }

    void credentialsChanged() {
        weakPasswordConfirmationPending = false;
        welcomeNotice = "";
    }

    void proxyChanged() {
        welcomeNotice = "";
    }

    DesktopUiNode controlCenterPage(
            Map<String, Runnable> nextActions
    ) {
        return switch (welcomeStep) {
            case STEP_SERVICE -> welcomeServiceStep();
            case STEP_CONFIG -> welcomeConfigStep(nextActions);
            default -> welcomeHub(nextActions);
        };
    }

    private DesktopUiNode welcomeServiceStep() {
        boolean failed = owner.backendSnapshot().state() == DesktopUiHost.BackendState.FAILED;
        List<DesktopUiNode> content = new ArrayList<>();
        content.add(new DesktopUiNode.Text(
                "welcome.service.message",
                token(
                        "gui-compose",
                        failed ? "gui.compose.onboarding.failed" : "gui.compose.onboarding.preparing",
                        ""
                ),
                failed ? TextStyle.ERROR : TextStyle.WAITING,
                true,
                false,
                DesktopUiNode.TextAlignment.CENTER
        ));
        if (!failed) {
            content.add(new DesktopUiNode.Text(
                    "welcome.service.status",
                    token("gui-compose", "gui.compose.onboarding.service-preparing", ""),
                    TextStyle.SECONDARY,
                    true,
                    false,
                    DesktopUiNode.TextAlignment.CENTER
            ));
            content.add(new DesktopUiNode.Progress(
                    "welcome.service.progress",
                    0,
                    true,
                    null,
                    DesktopUiNode.ProgressStyle.COMPACT_LINEAR
            ));
        }
        return new DesktopUiNode.Container(
                "welcome.service",
                DesktopUiNode.ContainerLayout.FLOW,
                1,
                12,
                DesktopUiNode.Alignment.CENTER,
                List.of(new DesktopUiNode.Container(
                        "welcome.service.content",
                        DesktopUiNode.ContainerLayout.COLUMN,
                        1,
                        16,
                        DesktopUiNode.Alignment.CENTER,
                        content
                ))
        );
    }

    private DesktopUiNode welcomeConfigStep(Map<String, Runnable> nextActions) {
        if (host.onboardingState(rootFolder).setupComplete()) {
            return welcomeStep(
                    "welcome.config",
                    "desktop.ui.onboarding.account.title",
                    "desktop.ui.onboarding.account.body",
                    List.of(text("welcome.config.done", "gui.welcome.config.done", TextStyle.SUCCESS)),
                    nextWelcomeButton("welcome.config.next", STEP_HUB, nextActions)
            );
        }
        boolean enabled = !owner.busy()
                && owner.backendSnapshot().state() == DesktopUiHost.BackendState.RUNNING;
        nextActions.put("welcome.config.submit", this::submitSetup);
        return new DesktopUiNode.AccountSetup(
                "welcome.config",
                input(
                        "welcome.username.input",
                        "welcome.username",
                        "gui.welcome.config.username",
                        null,
                        InputKind.TEXT,
                        form("welcome.username", ""),
                        enabled
                ),
                new DesktopUiNode.TextInput(
                        "welcome.password.input",
                        "welcome.password",
                        key("gui.welcome.config.password"),
                        null,
                        InputKind.PASSWORD,
                        "",
                        18,
                        1,
                        enabled,
                        welcomeFormRevision
                ),
                new DesktopUiNode.Button(
                        "welcome.config.submit",
                        "welcome.config.submit",
                        token("gui-compose", "gui.compose.onboarding.account.finish", ""),
                        null,
                        DesktopUiNode.ButtonStyle.PRIMARY,
                        enabled
                ),
                host.minimumPasswordLength(),
                host.recommendedPasswordLength(),
                owner.busy(),
                weakPasswordConfirmationPending,
                welcomeNotice.isBlank() || owner.busy() || weakPasswordConfirmationPending ? null
                        : raw("welcome.config.notice", welcomeNotice, welcomeNoticeStyle)
        );
    }

    private DesktopUiNode welcomeHub(Map<String, Runnable> nextActions) {
        List<DesktopUiNode.OnboardingCard> cards = new ArrayList<>();
        cards.add(new DesktopUiNode.OnboardingCard(
                "network",
                DesktopUiNode.OnboardingTopic.NETWORK,
                hubText("network.title"),
                hubText("network.summary"),
                hubText("network.body"),
                hubOpenButton(
                        "network",
                        hubText("network.open"),
                        nextActions,
                        URI.create("https://sywyar.github.io/PixivDownloader/#/" +
                                (java.util.Locale.getDefault().getLanguage().equals("zh")
                                        ? "zh-cn/configuration?id=出站代理" : "en/configuration?id=outbound-proxy"))
                ),
                proxySettings(),
                openedCards.contains("network")
        ));
        var download = DesktopControlCenterView.quickStartEntries(owner.currentSources()).stream()
                .filter(entry -> "download".equals(entry.navigation().icon()))
                .findFirst();
        cards.add(new DesktopUiNode.OnboardingCard(
                "download",
                DesktopUiNode.OnboardingTopic.DOWNLOAD,
                hubText("download.title"),
                hubText("download.summary"),
                hubText(download.isPresent() ? "download.body" : "unavailable"),
                hubOpenButton(
                        "download",
                        hubText("download.open"),
                        nextActions,
                        download.map(entry -> owner.webUri(entry.navigation().href())).orElse(null)
                ),
                null,
                openedCards.contains("download")
        ));
        GuiOnboardingStepContribution step = guideStep();
        if (step != null) {
            cards.add(new DesktopUiNode.OnboardingCard(
                    "guide." + step.stepId(),
                    DesktopUiNode.OnboardingTopic.GUIDE,
                    token(step.i18nNamespace(), step.titleKey(), ""),
                    token(step.i18nNamespace(), step.bodyKey(), ""),
                    token(
                            step.i18nNamespace(),
                            step.bulletKeys().isEmpty() ? step.bodyKey() : step.bulletKeys().get(0),
                            ""
                    ),
                    hubOpenButton(
                            "guide." + step.stepId(),
                            token(step.i18nNamespace(), step.actionLabelKey(), ""),
                            nextActions,
                            owner.webUri(step.actionHref())
                    ),
                    null,
                    openedCards.contains("guide." + step.stepId())
            ));
        }
        cards.add(new DesktopUiNode.OnboardingCard(
                "animation",
                DesktopUiNode.OnboardingTopic.ANIMATION,
                hubText("animation.title"),
                hubText("animation.summary"),
                hubText(ffmpegReady ? "animation.ready" : "animation.body"),
                hubOpenButton(
                        "animation",
                        hubText("animation.open"),
                        nextActions,
                        URI.create("https://sywyar.github.io/PixivDownloader/#/" +
                                (java.util.Locale.getDefault().getLanguage().equals("zh")
                                        ? "zh-cn/installation?id=安装-ffmpeg（可选）"
                                        : "en/installation?id=installing-ffmpeg-optional"))
                ),
                null,
                openedCards.contains("animation")
        ));
        nextActions.put("welcome.hub.next", this::saveWelcomeProxy);
        return new DesktopUiNode.OnboardingHub(
                "welcome.hub",
                cards,
                new DesktopUiNode.Button(
                        "welcome.hub.next",
                        "welcome.hub.next",
                        hubText(submitting ? "saving" : "finish"),
                        null,
                        DesktopUiNode.ButtonStyle.PRIMARY,
                        !owner.busy()
                ),
                welcomeNotice.isBlank() ? null : raw("welcome.hub.notice", welcomeNotice, welcomeNoticeStyle),
                submitting
        );
    }

    private DesktopUiNode.Button hubOpenButton(
            String cardId,
            TextToken label,
            Map<String, Runnable> nextActions,
            URI target
    ) {
        String id = "welcome.hub." + cardId + ".open";
        if (target != null) nextActions.put(
                id,
                () -> owner.runBusy(() -> {
                    try {
                        host.openExternalUri(target);
                        openedCards.add(cardId);
                        welcomeNotice = "";
                    } catch (Exception failure) {
                        setWelcomeNotice(host.message("desktop.ui.action.failed"), TextStyle.ERROR);
                    }
                })
        );
        return new DesktopUiNode.Button(
                id,
                id,
                label,
                null,
                DesktopUiNode.ButtonStyle.PRIMARY,
                target != null && !owner.busy(),
                top.sywyar.pixivdownload.plugin.api.gui.DesktopUiIcon.OPEN
        );
    }

    private DesktopUiNode.OnboardingProxySettings proxySettings() {
        boolean enabled = boolForm("welcome.proxy.enabled", true);
        return new DesktopUiNode.OnboardingProxySettings(
                new DesktopUiNode.Toggle(
                        "welcome.proxy.enabled.input",
                        "welcome.proxy.enabled",
                        key("gui.welcome.proxy.enabled"),
                        null,
                        DesktopUiNode.ToggleStyle.SWITCH,
                        enabled,
                        !owner.busy()
                ),
                input(
                        "welcome.proxy.host.input",
                        "welcome.proxy.host",
                        "gui.welcome.proxy.host",
                        null,
                        InputKind.TEXT,
                        form("welcome.proxy.host", host.defaultProxyHost()),
                        !owner.busy() && enabled
                ),
                input(
                        "welcome.proxy.port.input",
                        "welcome.proxy.port",
                        "gui.welcome.proxy.port",
                        null,
                        InputKind.NUMBER,
                        form("welcome.proxy.port", Integer.toString(host.defaultProxyPort())),
                        !owner.busy() && enabled
                ),
                proxyValidationAttempt
        );
    }

    private static TextToken hubText(String key) {
        return token("gui-compose", "gui.compose.onboarding.hub." + key, "");
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
        welcomeStep = normalizeStep(target);
        welcomeNotice = "";
        welcomeNoticeStyle = TextStyle.ERROR;
        host.saveOnboardingProgress(welcomeStep);
        owner.rebuild();
    }

    private int initialWelcomeStep() {
        DesktopUiHost.OnboardingSnapshot onboarding = host.onboardingState(rootFolder);
        int required = hostSetupWelcomeStep(onboarding);
        if (required < STEP_HUB) return required;
        return normalizeStep(Math.max(STEP_HUB, onboarding.progress()));
    }

    private int hostSetupWelcomeStep(DesktopUiHost.OnboardingSnapshot onboarding) {
        if (owner.backendSnapshot().state() != DesktopUiHost.BackendState.RUNNING) return STEP_SERVICE;
        if (!onboarding.setupComplete()) return STEP_CONFIG;
        return STEP_HUB;
    }

    private static int normalizeStep(int step) {
        return Math.max(STEP_SERVICE, Math.min(STEP_HUB, step));
    }

    private void submitSetup() {
        if (owner.busy() || owner.backendSnapshot().state() != DesktopUiHost.BackendState.RUNNING) return;
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
            DesktopUiHost.GuiResponse response;
            try {
                response = host.guiPostJson(
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
            } catch (RuntimeException failure) {
                setWelcomeNotice(
                        host.message("gui.welcome.config.failed", host.message("desktop.ui.action.failed")),
                        TextStyle.ERROR
                );
                return;
            }
            if (response.is2xx()) {
                formValues.remove("welcome.password");
                welcomeFormRevision++;
                goWelcomeStep(STEP_HUB);
            } else {
                setWelcomeNotice(
                        host.message("gui.welcome.config.failed", responseDetail(response)),
                        TextStyle.ERROR
                );
            }
        });
    }

    private void saveWelcomeProxy() {
        if (owner.busy()) return;
        welcomeNotice = "";
        String hostValue = form("welcome.proxy.host", "").trim();
        int port = intForm("welcome.proxy.port", 0);
        boolean enabled = boolForm("welcome.proxy.enabled", true);
        if (proxySettings().invalid()) {
            proxyValidationAttempt++;
            owner.rebuild();
            return;
        }
        if (!enabled && hostValue.isBlank()) hostValue = host.defaultProxyHost();
        if (!enabled && (port < 1 || port > 65_535)) port = host.defaultProxyPort();
        String savedHost = hostValue;
        int savedPort = port;
        submitting = true;
        owner.runBusy(() -> {
            try {
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
                } catch (Exception failure) {
                    setWelcomeNotice(
                            host.message("gui.welcome.proxy.failed", safeMessage(failure)),
                            TextStyle.ERROR
                    );
                    return;
                }
                boolean reloaded;
                try {
                    DesktopUiHost.GuiResponse response = host.guiPostJson(
                            "config/reload",
                            Map.of(
                                    "changedKeys",
                                    List.of("proxy.enabled", "proxy.host", "proxy.port")
                            ),
                            5_000
                    );
                    reloaded = response.is2xx();
                } catch (Exception failure) {
                    reloaded = false;
                }
                finishOnboarding();
                if (!reloaded) {
                    owner.showDialog(
                            "welcome.proxy.reload-failed",
                            "gui.dialog.warning.title",
                            hubText("network.reload-failed"),
                            DesktopUiDocument.DialogStyle.WARNING
                    );
                }
            } finally {
                submitting = false;
            }
        });
    }

    private void finishOnboarding() {
        if (!host.onboardingState(rootFolder).setupComplete()) {
            setWelcomeNotice(host.message("gui.welcome.config.waiting"), TextStyle.ERROR);
            welcomeStep = STEP_CONFIG;
            return;
        }
        host.markOnboardingSeen();
        host.markOnboardingFinished();
    }

    void refreshState() {
        DesktopUiHost.OnboardingSnapshot onboarding = host.onboardingState(rootFolder);
        if (onboarding.complete()) return;
        if (welcomeStep == STEP_SERVICE) {
            int next = hostSetupWelcomeStep(onboarding);
            if (next != welcomeStep) {
                welcomeStep = next;
                host.saveOnboardingProgress(next);
            }
        }
        // 检测可能启动系统命令，复用后台刷新，避免阻塞表单输入。
        if (welcomeStep == STEP_HUB) ffmpegReady = host.locateFfmpeg().isPresent();
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
                && safeHref(step.actionHref())
                && validId(step.waitingKey())
                && validId(step.completionKey());
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
