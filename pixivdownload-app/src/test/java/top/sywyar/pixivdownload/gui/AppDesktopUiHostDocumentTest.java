package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.config.credential.PluginCredentialStore;
import top.sywyar.pixivdownload.gui.config.TestDesktopConfigFile;
import top.sywyar.pixivdownload.i18n.MessageBundles;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiCapability;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiContext;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiExperienceProfile;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiIcon;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiPageContribution;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiProvider;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiSession;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiSnapshot;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigCondition;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigEffect;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldLayoutContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldType;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigGroupContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigGroups;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigPresetContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigSectionContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigSectionLayout;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigSectionNoticeContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiThemeAppearance;
import top.sywyar.pixivdownload.plugin.api.gui.GuiThemeContribution;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiDocument;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.HttpMethod;
import top.sywyar.pixivdownload.plugin.api.web.NavigationContribution;
import top.sywyar.pixivdownload.plugin.api.web.NavigationPlacements;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppDesktopUiHostDocumentTest extends DesktopUiDocumentTestSupport {
    @Test
    @DisplayName("CLASSIC 档位保留全部既有桌面根页面")
    void hostModelProvidesCompleteDesktopDocument() {
        DesktopUiDocument document = model(DesktopUiExperienceProfile.CLASSIC).snapshot().document();

        assertThat(document.pages()).extracting(DesktopUiDocument.Page::id).containsExactly(
                "welcome",
                "status",
                "config",
                "plugins",
                "tools",
                "security",
                "about"
        );
        assertThat(document.requiredNodeKinds()).contains(
                DesktopUiNode.Kind.CONTAINER,
                DesktopUiNode.Kind.GROUP,
                DesktopUiNode.Kind.TABS,
                DesktopUiNode.Kind.SCROLL,
                DesktopUiNode.Kind.TEXT,
                DesktopUiNode.Kind.TEXT_INPUT,
                DesktopUiNode.Kind.TOGGLE,
                DesktopUiNode.Kind.CHOICE,
                DesktopUiNode.Kind.NUMBER_INPUT,
                DesktopUiNode.Kind.TABLE,
                DesktopUiNode.Kind.BUTTON
        );
        assertThat(nodes(document)).extracting(DesktopUiNode::id).contains(
                "config.market.repositories");
        DesktopUiDocument.Tray tray = document.tray().orElseThrow();
        assertThat(tray.items()).extracting(DesktopUiDocument.TrayItem::role).containsExactly(
                DesktopUiDocument.TrayItemRole.ACTIVATE_WINDOW,
                DesktopUiDocument.TrayItemRole.SEPARATOR,
                DesktopUiDocument.TrayItemRole.DISPATCH,
                DesktopUiDocument.TrayItemRole.DISPATCH,
                DesktopUiDocument.TrayItemRole.SEPARATOR,
                DesktopUiDocument.TrayItemRole.DISPATCH
        );
        assertThat(tray.items()).extracting(DesktopUiDocument.TrayItem::actionId).contains(
                "tray.batch.open",
                "tray.download-folder.open",
                "tray.exit"
        );
    }

    @Test
    @DisplayName("CONTROL_CENTER 档位通过独立入口生成完整文档")
    void controlCenterProfileHasACompleteDocumentEntry() {
        DesktopUiDocument controlCenter = modelWithOnboarding(
                List.of(),
                DesktopUiExperienceProfile.CONTROL_CENTER,
                COMPLETE_ONBOARDING
        ).snapshot().document();

        assertThat(controlCenter.pages()).extracting(DesktopUiDocument.Page::id).containsExactly(
                "home",
                "automation",
                "plugins",
                "tools",
                "security",
                "settings",
                "about"
        );
        assertThat(controlCenter.pages()).extracting(DesktopUiDocument.Page::icon).containsExactly(
                DesktopUiIcon.HOME,
                DesktopUiIcon.AUTOMATION,
                DesktopUiIcon.PLUGIN,
                DesktopUiIcon.TOOLS,
                DesktopUiIcon.SECURITY,
                DesktopUiIcon.SETTINGS,
                DesktopUiIcon.ABOUT
        );
        assertThat(nodes(controlCenter)).extracting(DesktopUiNode::id).contains(
                "home.greeting",
                "home.hero",
                "home.system",
                "home.metrics",
                "home.quick-start",
                "home.running",
                "home.storage",
                "plugins.layout",
                "plugins.grid",
                "tools.layout",
                "tools.quick.grid",
                "tools.maintenance.grid",
                "settings.layout",
                "settings.categories",
                "settings.content",
                "settings.summary",
                "about.name",
                "about.links",
                "about.docs",
                "about.contributors",
                "about.maintainers",
                "about.maintainer.83223374",
                "about.maintainer.83223374.content",
                "about.maintainer.83223374.avatar",
                "about.maintainer.83223374.name",
                "about.maintainer.65430754",
                "about.maintainer.65430754.content",
                "about.maintainer.65430754.avatar",
                "about.maintainer.65430754.name",
                "about.update.check"
        );
        List<DesktopUiNode.Image> maintainerImages = nodes(controlCenter).stream().filter(
                DesktopUiNode.Image.class::isInstance).map(DesktopUiNode.Image.class::cast).filter(
                image -> image.id().startsWith("about.maintainer.")).toList();
        assertThat(maintainerImages).extracting(image -> image.image().bytes().length).allMatch(size -> size > 0);
        assertThat(maintainerImages).allSatisfy(image -> {
            assertThat(image.preferredWidth()).isEqualTo(72);
            assertThat(image.preferredHeight()).isEqualTo(72);
            assertThat(image.scaleMode()).isEqualTo(DesktopUiNode.ScaleMode.FILL);
            assertThat(image.shape()).isEqualTo(DesktopUiNode.ImageShape.CIRCLE);
        });
        assertThat(nodes(controlCenter).stream().filter(DesktopUiNode.Surface.class::isInstance).map(
                DesktopUiNode.Surface.class::cast).filter(surface -> surface.id().startsWith(
                "about.maintainer."))).allSatisfy(surface -> {
            assertThat(surface.fillWidth()).isFalse();
            assertThat(surface.fillHeight()).isFalse();
            assertThat(surface.actionId()).isEqualTo(surface.id() + ".open");
        });
        assertThat(nodes(controlCenter).stream().filter(DesktopUiNode.Text.class::isInstance).map(
                DesktopUiNode.Text.class::cast).filter(text -> text.id().startsWith(
                "about.maintainer.") && text.id().endsWith(".name"))).extracting(text -> text.text().fallback()).containsExactly(
                        "Sywyar",
                        "gdrfgdrf"
                );
        assertThat(controlCenter.requiredCapabilities()).contains(
                DesktopUiCapability.SURFACE_ACTIVATION,
                DesktopUiCapability.IMAGE_CIRCULAR_CLIP
        );
        assertThat(controlCenter.pages().get(0).floatingAction()).isPresent();
    }

    @Test
    @DisplayName("维护者卡片激活后打开对应 GitHub 主页")
    void maintainerCardActivationOpensProfile() throws Exception {
        Path config = tempDir.resolve("maintainer-card.yaml");
        AppDesktopUiHost delegate = new AppDesktopUiHost(
                6999,
                new TestDesktopConfigFile(config)
        );
        AtomicReference<String> openedUri = new AtomicReference<>();
        DesktopUiHost host = (DesktopUiHost) Proxy.newProxyInstance(
                DesktopUiHost.class.getClassLoader(),
                new Class<?>[]{DesktopUiHost.class},
                (proxy, method, arguments) -> {
                    if ("onboardingState".equals(method.getName())) return COMPLETE_ONBOARDING;
                    if ("backendSnapshot".equals(method.getName())) {
                        return new DesktopUiHost.BackendSnapshot(
                                DesktopUiHost.BackendState.RUNNING,
                                null
                        );
                    }
                    if ("subscribeBackend".equals(method.getName())) return (AutoCloseable) () -> {
                    };
                    if ("openExternalUri".equals(method.getName())) {
                        openedUri.set(arguments[0].toString());
                        return null;
                    }
                    try {
                        return method.invoke(delegate, arguments);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                }
        );
        AppDesktopUiModel model = track(new AppDesktopUiModel(
                6999,
                tempDir.resolve("downloads").toString(),
                config,
                host,
                List::of,
                rendererContract(DesktopUiExperienceProfile.CONTROL_CENTER)
        ));

        awaitButtonEnabled(model, "about.update.check");
        dispatch(
                model,
                DesktopUiNode.EventType.ACTIVATE,
                "about.maintainer.83223374",
                DesktopUiNode.Value.empty()
        );

        await(() -> "https://github.com/Sywyar".equals(openedUri.get()));
    }

    @Test
    @DisplayName("控制中心插件、工具与设置页采用原型分区且 CLASSIC 结构不变")
    void controlCenterReplicatesPrototypePagePartitionsWithoutChangingClassic() {
        DesktopUiDocument controlCenter = modelWithOnboarding(
                List.of(),
                DesktopUiExperienceProfile.CONTROL_CENTER,
                COMPLETE_ONBOARDING
        ).snapshot().document();
        DesktopUiDocument classic = model(DesktopUiExperienceProfile.CLASSIC).snapshot().document();

        assertPageContent(controlCenter, "plugins", DesktopUiNode.Scroll.class);
        assertPageContent(controlCenter, "tools", DesktopUiNode.Scroll.class);
        assertPageContent(controlCenter, "settings", DesktopUiNode.Scroll.class);
        assertThat(nodes(controlCenter).stream().filter(node -> "plugins.grid".equals(node.id())).findFirst().orElseThrow()).isInstanceOf(
                DesktopUiNode.AdaptiveGrid.class);
        assertThat(nodes(controlCenter).stream().filter(node -> "settings.categories".equals(node.id())).findFirst().orElseThrow()).isInstanceOf(
                DesktopUiNode.Tree.class);
        DesktopUiNode.AdaptiveGrid settings = (DesktopUiNode.AdaptiveGrid) nodes(controlCenter).stream().filter(
                node -> "settings.layout".equals(node.id())).findFirst().orElseThrow();
        assertThat(settings.children()).extracting(DesktopUiNode::id).containsExactly(
                "settings.sidebar",
                "settings.content"
        );
        assertThat(((DesktopUiNode.Container) settings.children().get(0)).children()).extracting(
                DesktopUiNode::id).containsExactly(
                        "settings.categories.surface",
                        "settings.summary"
                );
        assertThat(nodes(controlCenter).stream().filter(node -> "config.save".equals(node.id()))).hasSize(
                1);
        assertThat(nodes(classic)).extracting(DesktopUiNode::id).doesNotContain(
                "plugins.layout",
                "tools.layout",
                "settings.layout",
                "settings.categories"
        );
        assertPageContent(classic, "config", DesktopUiNode.Dock.class);
    }

    @Test
    @DisplayName("控制中心引导只保留宿主设置步骤且 CLASSIC 继续显示下载入口")
    void controlCenterOnboardingKeepsOnlyHostSetupFacts() {
        DesktopUiHost.OnboardingSnapshot readyForCompletion = new DesktopUiHost.OnboardingSnapshot(
                false,
                true,
                4,
                false,
                true
        );

        DesktopUiDocument controlCenter = modelWithOnboarding(
                List.of(),
                DesktopUiExperienceProfile.CONTROL_CENTER,
                readyForCompletion
        ).snapshot().document();
        DesktopUiDocument classic = modelWithOnboarding(
                List.of(),
                DesktopUiExperienceProfile.CLASSIC,
                readyForCompletion
        ).snapshot().document();

        assertThat(nodes(controlCenter)).extracting(DesktopUiNode::id).contains(
                "welcome.done.finish").doesNotContain(
                        "welcome.start.open",
                        "welcome.ffmpeg.title",
                        "welcome.scripts.title"
                );
        assertThat(nodes(controlCenter)).extracting(DesktopUiNode::id).noneMatch(id -> id.startsWith(
                "welcome.plugin."));
        assertThat(textNode(controlCenter, "welcome.done.body").text().key()).isEqualTo(
                "desktop.ui.onboarding.done.body");
        assertThat(nodes(classic)).extracting(DesktopUiNode::id).contains("welcome.start.open");
    }

    @Test
    @DisplayName("控制中心首页读取真实快照并用最近现存目录计算存储")
    void controlCenterHomeUsesMaterializedFactsAndFileStore() throws Exception {
        Path config = tempDir.resolve("control-center.yaml");
        AppDesktopUiHost delegate = new AppDesktopUiHost(
                6999,
                new TestDesktopConfigFile(config)
        );
        DesktopUiHost host = (DesktopUiHost) Proxy.newProxyInstance(
                DesktopUiHost.class.getClassLoader(),
                new Class<?>[]{DesktopUiHost.class},
                (proxy, method, arguments) -> {
                    if ("onboardingState".equals(method.getName())) return COMPLETE_ONBOARDING;
                    if ("controlCenterSnapshot".equals(method.getName())) {
                        return response(Map.of(
                                "cards",
                                List.of(Map.of(
                                        "owner",
                                        Map.of("pluginId", "fixture"),
                                        "card",
                                        Map.of(
                                                "cardId",
                                                "completed",
                                                "order",
                                                10,
                                                "title",
                                                token("Completed"),
                                                "primaryValue",
                                                token("12"),
                                                "supportingText",
                                                token("Observed"),
                                                "tone",
                                                "SUCCESS",
                                                "icon",
                                                "DOWNLOAD",
                                                "availability",
                                                "AVAILABLE"
                                        )
                                )),
                                "runningTasks",
                                List.of(Map.of(
                                        "owner",
                                        Map.of("pluginId", "fixture"),
                                        "task",
                                        Map.of(
                                                "taskId",
                                                "running",
                                                "order",
                                                10,
                                                "title",
                                                token("Running task"),
                                                "supportingText",
                                                token("Downloading"),
                                                "status",
                                                "RUNNING",
                                                "progress",
                                                0.5,
                                                "availability",
                                                "AVAILABLE"
                                        )
                                )),
                                "automations",
                                List.of()
                        ));
                    }
                    try {
                        return method.invoke(delegate, arguments);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                }
        );
        AppDesktopUiModel model = track(new AppDesktopUiModel(
                6999,
                tempDir.resolve("missing").resolve("downloads").toString(),
                config,
                host,
                List::of,
                rendererContract(DesktopUiExperienceProfile.CONTROL_CENTER)
        ));

        await(() -> nodes(model.snapshot().document()).stream().anyMatch(node -> "home.metrics.fixture.completed".equals(
                node.id())));
        assertThat(nodes(model.snapshot().document())).extracting(DesktopUiNode::id).contains(
                "home.metrics.fixture.completed",
                "home.hero.fixture.running",
                "home.running.fixture.running",
                "home.storage"
        );
        assertThat(nodes(model.snapshot().document()).stream().filter(DesktopUiNode.Text.class::isInstance).map(
                DesktopUiNode.Text.class::cast).filter(node -> "home.storage.primary".equals(node.id())).findFirst().orElseThrow().text().arguments()).doesNotContain(
                "--");
    }

    @Test
    @DisplayName("控制中心自动化与插件页面只读展示宿主快照")
    void controlCenterAutomationAndPluginsAreReadOnlyFacts() throws Exception {
        Path config = tempDir.resolve("control-center-read-only.yaml");
        AppDesktopUiHost delegate = new AppDesktopUiHost(
                6999,
                new TestDesktopConfigFile(config)
        );
        DesktopUiHost host = (DesktopUiHost) Proxy.newProxyInstance(
                DesktopUiHost.class.getClassLoader(),
                new Class<?>[]{DesktopUiHost.class},
                (proxy, method, arguments) -> {
                    if ("controlCenterSnapshot".equals(method.getName())) {
                        return response(Map.of(
                                "cards",
                                List.of(),
                                "runningTasks",
                                List.of(),
                                "automations",
                                List.of(Map.of(
                                        "owner",
                                        Map.of("pluginId", "fixture"),
                                        "snapshot",
                                        Map.of(
                                                "availability",
                                                "AVAILABLE",
                                                "observedAt",
                                                "2026-08-21T00:00:00Z",
                                                "tasks",
                                                List.of(Map.of(
                                                        "taskId",
                                                        "nightly",
                                                        "order",
                                                        10,
                                                        "title",
                                                        token("Nightly task"),
                                                        "triggerSummary",
                                                        token("Cron at midnight"),
                                                        "status",
                                                        "SUSPENDED",
                                                        "lastResult",
                                                        "ERROR",
                                                        "nextRuns",
                                                        List.of(
                                                                "2026-08-21T02:00:00Z",
                                                                "2026-08-21T01:00:00Z"
                                                        ),
                                                        "observedAt",
                                                        "2026-08-21T00:00:00Z"
                                                ))
                                        )
                                ))
                        ));
                    }
                    if ("guiGet".equals(method.getName()) && "plugins/status".equals(arguments[0])) {
                        return response(Map.of(
                                "recoveryMode",
                                false,
                                "observedAt",
                                "2026-08-21T00:00:00Z",
                                "plugins",
                                List.of(Map.of(
                                        "id",
                                        "fixture",
                                        "name",
                                        "Fixture",
                                        "source",
                                        "external",
                                        "status",
                                        "FAILED",
                                        "runtimePhase",
                                        "STOPPED",
                                        "managed",
                                        true,
                                        "required",
                                        false,
                                        "version",
                                        "1.0.0",
                                        "verification",
                                        Map.of(
                                                "status",
                                                "INVALID_SIGNATURE",
                                                "diagnosticCode",
                                                "SIGNATURE_MISMATCH",
                                                "lastVerifiedAt",
                                                "2026-08-20T23:59:00Z"
                                        )
                                ))
                        ));
                    }
                    try {
                        return method.invoke(delegate, arguments);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                }
        );
        AppDesktopUiModel model = track(new AppDesktopUiModel(
                6999,
                tempDir.resolve("downloads").toString(),
                config,
                host,
                List::of,
                rendererContract(DesktopUiExperienceProfile.CONTROL_CENTER)
        ));

        await(() -> nodes(model.snapshot().document()).stream().anyMatch(node -> "automation.task.fixture.nightly".equals(
                node.id())));
        await(() -> nodes(model.snapshot().document()).stream().anyMatch(node -> "plugins.card.fixture".equals(
                node.id())));

        DesktopUiNode automationPage = model.snapshot().document().pages().stream().filter(page -> "automation".equals(
                page.id())).findFirst().orElseThrow().content();
        List<DesktopUiNode> automationNodes = new ArrayList<>();
        collectNodes(automationPage, automationNodes);
        assertThat(automationNodes).extracting(DesktopUiNode::id).contains(
                "automation.source.fixture",
                "automation.task.fixture.nightly"
        ).anyMatch(id -> id.startsWith("automation.timeline.fixture.nightly."));
        assertThat(automationNodes).noneMatch(DesktopUiNode.Button.class::isInstance).noneMatch(
                DesktopUiNode.Link.class::isInstance);

        DesktopUiNode pluginsPage = model.snapshot().document().pages().stream().filter(page -> "plugins".equals(
                page.id())).findFirst().orElseThrow().content();
        List<DesktopUiNode> pluginNodes = new ArrayList<>();
        collectNodes(pluginsPage, pluginNodes);
        assertThat(pluginNodes).noneMatch(DesktopUiNode.Button.class::isInstance).noneMatch(
                DesktopUiNode.Link.class::isInstance);
        assertThat(pluginNodes.stream().filter(DesktopUiNode.Text.class::isInstance).map(
                DesktopUiNode.Text.class::cast).map(DesktopUiNode.Text::text).flatMap(token -> java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(token.fallback()),
                        token.arguments().stream()
                ))).anyMatch(value -> value.contains("SIGNATURE_MISMATCH"));
    }

    @Test
    @DisplayName("快速开始只接纳同 owner 精确 GET 路由且不放宽访问策略")
    void quickStartRequiresAnExactOwnerRouteWithNoBroaderAccess() {
        DesktopUiPluginSource validZ = quickStartSource(
                "z-valid",
                "/z-valid.html",
                AccessPolicy.ADMIN,
                AccessPolicy.ADMIN,
                true
        );
        DesktopUiPluginSource validA = quickStartSource(
                "a-valid",
                "/a-valid.html",
                AccessPolicy.ADMIN,
                AccessPolicy.ADMIN,
                true
        );
        DesktopUiPluginSource crossOwner = quickStartSource(
                "cross-owner",
                "/shared.html",
                AccessPolicy.ADMIN,
                AccessPolicy.ADMIN,
                false
        );
        DesktopUiPluginSource routeOwner = quickStartSource(
                "route-owner",
                "/unused.html",
                AccessPolicy.ADMIN,
                AccessPolicy.ADMIN,
                false,
                List.of(WebRouteContribution.admin("/shared.html"))
        );
        DesktopUiPluginSource broader = quickStartSource(
                "broader",
                "/broader.html",
                AccessPolicy.PUBLIC,
                AccessPolicy.ADMIN,
                true
        );
        DesktopUiPluginSource wildcard = quickStartSource(
                "wildcard",
                "/wildcard/page.html",
                AccessPolicy.ADMIN,
                AccessPolicy.ADMIN,
                false,
                List.of(WebRouteContribution.admin("/wildcard/**"))
        );
        DesktopUiPluginSource postOnly = quickStartSource(
                "post-only",
                "/post-only.html",
                AccessPolicy.ADMIN,
                AccessPolicy.ADMIN,
                false,
                List.of(new WebRouteContribution(
                        "/post-only.html",
                        AccessPolicy.ADMIN,
                        Set.of(HttpMethod.POST),
                        false
                ))
        );

        DesktopUiDocument document = modelWithOnboarding(
                List.of(
                        validZ,
                        crossOwner,
                        routeOwner,
                        broader,
                        wildcard,
                        postOnly,
                        validA
                ),
                DesktopUiExperienceProfile.CONTROL_CENTER,
                COMPLETE_ONBOARDING
        ).snapshot().document();

        assertThat(nodes(document).stream().filter(DesktopUiNode.Button.class::isInstance).map(
                DesktopUiNode.Button.class::cast).filter(button -> button.id().startsWith(
                "home.quick-start."))).extracting(DesktopUiNode.Button::label).extracting(
                DesktopUiNode.TextToken::fallback).containsExactly("a-valid", "z-valid");
        DesktopUiNode.Container quickStart = (DesktopUiNode.Container) nodes(document).stream().filter(
                node -> "home.quick-start.grid".equals(node.id())).findFirst().orElseThrow();
        assertThat(quickStart.layout()).isEqualTo(DesktopUiNode.ContainerLayout.GRID);
        assertThat(quickStart.columns()).isEqualTo(2);
        DesktopUiDocument.Page home = document.pages().stream().filter(page -> "home".equals(page.id())).findFirst().orElseThrow();
        assertThat(home.floatingAction()).isPresent();
        assertThat(nodes(document).stream().filter(DesktopUiNode.Surface.class::isInstance).map(
                DesktopUiNode.Surface.class::cast).filter(surface -> surface.id().startsWith(
                "home.quick-start."))).isEmpty();
        assertThat(DesktopUiEventProtocol.index(document).keySet()).contains(
                "home.quick-start.a-valid.a-valid.button",
                "home.quick-start.z-valid.z-valid.button"
        );
    }

    @Test
    @DisplayName("宿主按文档顺序交付三类多选值")
    void selectionBindingsReceiveEverySelectedIdInDocumentOrder() {
        DesktopUiNode.TextToken label = DesktopUiNode.TextToken.raw("Label");
        DesktopUiNode.Choice choice = new DesktopUiNode.Choice(
                "choice",
                "choice.value",
                label,
                null,
                DesktopUiNode.ChoiceStyle.CHECK_BOXES,
                DesktopUiNode.SelectionMode.MULTIPLE,
                List.of(
                        new DesktopUiNode.Option("first", label, true),
                        new DesktopUiNode.Option("second", label, true),
                        new DesktopUiNode.Option("third", label, true)
                ),
                List.of(),
                true
        );
        DesktopUiNode.Table table = new DesktopUiNode.Table(
                "table",
                "table.value",
                List.of(new DesktopUiNode.TableColumn("value", label, 0)),
                List.of(
                        new DesktopUiNode.TableRow("first", List.of("First")),
                        new DesktopUiNode.TableRow("second", List.of("Second")),
                        new DesktopUiNode.TableRow("third", List.of("Third"))
                ),
                DesktopUiNode.SelectionMode.MULTIPLE,
                List.of(),
                true
        );
        DesktopUiNode.Tree tree = new DesktopUiNode.Tree(
                "tree",
                "tree.value",
                List.of(
                        new DesktopUiNode.TreeItem(
                                "first",
                                label,
                                List.of(new DesktopUiNode.TreeItem("second", label, List.of()))
                        ),
                        new DesktopUiNode.TreeItem("third", label, List.of())
                ),
                DesktopUiNode.SelectionMode.MULTIPLE,
                List.of(),
                true
        );
        AtomicReference<List<String>> selected = new AtomicReference<>();

        for (DesktopUiNode node : List.of(choice, table, tree)) {
            AppDesktopUiModel.acceptSelection(
                    selected::set,
                    node,
                    DesktopUiNode.Value.selections(List.of("third", "first", "second"))
            );
            assertThat(selected).hasValue(List.of("first", "second", "third"));
            assertThatThrownBy(() -> selected.get().add("forged")).isInstanceOf(
                    UnsupportedOperationException.class);
        }
        assertThatThrownBy(() -> DesktopUiNode.Value.selections(List.of(
                "first",
                "first"
        ))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(
                "duplicate selection value");
    }

    @Test
    @DisplayName("插件托盘入口进入宿主完整 Schema")
    void pluginTrayNavigationJoinsTheHostDocument() {
        PixivFeaturePlugin plugin = new PixivFeaturePlugin() {
            @Override
            public String id() {
                return "tray-fixture";
            }

            @Override
            public String displayName() {
                return "plugin.name";
            }

            @Override
            public String description() {
                return "plugin.description";
            }

            @Override
            public PluginKind kind() {
                return PluginKind.FEATURE;
            }

            @Override
            public List<NavigationContribution> navigation() {
                return List.of(new NavigationContribution(
                        "fixture-entry",
                        NavigationPlacements.GUI_TRAY_ACTIONS,
                        "fixture",
                        "navigation.label",
                        "/fixture.html",
                        "link",
                        AccessPolicy.PUBLIC,
                        10
                ));
            }
        };
        DesktopUiPluginSource source = new DesktopUiPluginSource(
                plugin.id(),
                false,
                plugin,
                plugin.getClass().getClassLoader()
        );

        DesktopUiDocument.TrayItem item = model(List.of(source)).snapshot().document().tray().orElseThrow().items().stream().filter(
                candidate -> candidate.id().startsWith("tray.web.")).findFirst().orElseThrow();

        assertThat(item.label().namespace()).isEqualTo("fixture");
        assertThat(item.label().key()).isEqualTo("navigation.label");
        assertThat(item.actionId()).isEqualTo(item.id() + ".open");
    }

    @Test
    @DisplayName("基准页面结构保留固定头尾、滚动内容与对齐表单")
    void baselinePageStructureUsesStableLayoutSemantics() {
        DesktopUiDocument document = model().snapshot().document();

        DesktopUiNode.Dock welcome = assertPageContent(
                document,
                "welcome",
                DesktopUiNode.Dock.class
        );
        assertThat(welcome.top()).isInstanceOf(DesktopUiNode.Container.class);
        assertThat(welcome.center()).isInstanceOf(DesktopUiNode.Scroll.class);
        assertThat(welcome.bottom()).isInstanceOf(DesktopUiNode.Container.class);

        DesktopUiNode.Dock status = assertPageContent(
                document,
                "status",
                DesktopUiNode.Dock.class
        );
        assertThat(status.center()).isInstanceOf(DesktopUiNode.Scroll.class);
        assertThat(status.bottom()).isInstanceOf(DesktopUiNode.Container.class);

        DesktopUiNode.Dock config = assertPageContent(
                document,
                "config",
                DesktopUiNode.Dock.class
        );
        assertThat(config.center()).isInstanceOf(DesktopUiNode.Tabs.class);
        assertThat(config.bottom()).isInstanceOf(DesktopUiNode.Surface.class);
        assertThat(nodes(document)).anyMatch(DesktopUiNode.Form.class::isInstance);
        assertThat(nodes(document)).extracting(DesktopUiNode::id).contains(
                "config.autostart.input",
                "config.open",
                "config.reset",
                "config.category.plugins.scopes",
                "config.category.plugins.settings.empty"
        );
    }

    @Test
    @DisplayName("工具提示与关于页保留基准排版语义")
    void toolsAndAboutKeepBaselineLayoutSemantics() {
        DesktopUiDocument document = model().snapshot().document();
        List<DesktopUiNode> nodes = nodes(document);
        DesktopUiNode.Text limitHint = nodes.stream().filter(DesktopUiNode.Text.class::isInstance).map(
                DesktopUiNode.Text.class::cast).filter(text -> "tools.backfill.limit-hint".equals(
                text.id())).findFirst().orElseThrow();
        DesktopUiNode.Text license = nodes.stream().filter(DesktopUiNode.Text.class::isInstance).map(
                DesktopUiNode.Text.class::cast).filter(text -> "about.license.badge".equals(text.id())).findFirst().orElseThrow();
        DesktopUiNode.Text technology = nodes.stream().filter(DesktopUiNode.Text.class::isInstance).map(
                DesktopUiNode.Text.class::cast).filter(text -> "about.tech".equals(text.id())).findFirst().orElseThrow();
        DesktopUiNode.Dock proxyControls = nodes.stream().filter(DesktopUiNode.Dock.class::isInstance).map(
                DesktopUiNode.Dock.class::cast).filter(dock -> "tools.backfill.proxy.controls".equals(
                dock.id())).findFirst().orElseThrow();
        DesktopUiNode.Dock about = assertPageContent(
                document,
                "about",
                DesktopUiNode.Dock.class
        );

        assertThat(limitHint.wrap()).isFalse();
        assertThat(proxyControls.center()).isInstanceOf(DesktopUiNode.TextInput.class);
        assertThat(proxyControls.end()).isInstanceOf(DesktopUiNode.Container.class);
        assertThat(about.top()).isInstanceOf(DesktopUiNode.Container.class);
        assertThat(about.center()).isInstanceOf(DesktopUiNode.Scroll.class);
        assertThat(license.textAlignment()).isEqualTo(DesktopUiNode.TextAlignment.CENTER);
        assertThat(technology.text().key()).isEqualTo("gui.about.tech");
        assertThat(technology.text().arguments()).hasSize(1);
    }

    @Test
    @DisplayName("控制中心显示持久化工具历史且 CLASSIC 页面结构不变")
    void controlCenterShowsToolHistoryAndRecordsClassifierClose() throws Exception {
        DesktopToolHistory history = new DesktopToolHistory(RuntimeFiles.guiStateDirectory());
        history.record(
                DesktopToolHistory.ToolId.JSON_TO_SQLITE_MIGRATION,
                DesktopToolHistory.Outcome.SUCCEEDED,
                1L,
                9,
                7,
                null,
                Path.of("log", "html", "json-to-sqlite-migration_2026-08-21_120000.html")
        );

        AppDesktopUiModel controlCenter = model(DesktopUiExperienceProfile.CONTROL_CENTER);
        assertThat(nodes(controlCenter.snapshot().document())).extracting(DesktopUiNode::id).contains(
                "tools.layout",
                "tools.quick.grid",
                "tools.maintenance.grid"
        );
        DesktopUiNode.Container layout = nodes(controlCenter.snapshot().document()).stream().filter(
                DesktopUiNode.Container.class::isInstance).map(DesktopUiNode.Container.class::cast).filter(
                node -> "tools.layout".equals(node.id())).findFirst().orElseThrow();
        assertThat(layout.children()).extracting(DesktopUiNode::id).containsExactly(
                "tools.quick.title",
                "tools.quick.row",
                "tools.maintenance.title",
                "tools.maintenance.row"
        );
        DesktopUiNode.AdaptiveGrid quickRow = nodes(controlCenter.snapshot().document()).stream().filter(
                DesktopUiNode.AdaptiveGrid.class::isInstance).map(DesktopUiNode.AdaptiveGrid.class::cast).filter(
                node -> "tools.quick.row".equals(node.id())).findFirst().orElseThrow();
        assertThat(quickRow.children()).extracting(DesktopUiNode::id).containsExactly(
                "tools.quick.grid",
                "tools.overview"
        );
        DesktopUiNode.AdaptiveGrid maintenanceRow = nodes(controlCenter.snapshot().document()).stream().filter(
                DesktopUiNode.AdaptiveGrid.class::isInstance).map(DesktopUiNode.AdaptiveGrid.class::cast).filter(
                node -> "tools.maintenance.row".equals(node.id())).findFirst().orElseThrow();
        assertThat(maintenanceRow.children()).extracting(DesktopUiNode::id).containsExactly(
                "tools.maintenance.grid",
                "tools.history"
        );
        DesktopUiNode.Table table = nodes(controlCenter.snapshot().document()).stream().filter(
                DesktopUiNode.Table.class::isInstance).map(DesktopUiNode.Table.class::cast).filter(
                node -> "tools.history.table".equals(node.id())).findFirst().orElseThrow();
        assertThat(table.rows()).singleElement().satisfies(row -> assertThat(row.cells()).contains(
                "9",
                "7",
                "json-to-sqlite-migration_2026-08-21_120000.html"
        ));
        assertThat(nodes(model(DesktopUiExperienceProfile.CLASSIC).snapshot().document())).extracting(
                DesktopUiNode::id).doesNotContain("tools.history", "tools.history.table");

        awaitButtonEnabled(controlCenter, "tools.image-classifier.open");
        dispatch(
                controlCenter,
                DesktopUiNode.EventType.ACTIVATE,
                "tools.image-classifier.open",
                DesktopUiNode.Value.empty()
        );
        awaitButtonEnabled(controlCenter, "classifier.dialog.close");
        dispatch(
                controlCenter,
                DesktopUiNode.EventType.ACTIVATE,
                "classifier.dialog.close",
                DesktopUiNode.Value.empty()
        );

        assertThat(new DesktopToolHistory(RuntimeFiles.guiStateDirectory()).entries()).first().satisfies(
                entry -> {
                    assertThat(entry.toolId()).isEqualTo(DesktopToolHistory.ToolId.IMAGE_CLASSIFIER);
                    assertThat(entry.outcome()).isEqualTo(DesktopToolHistory.Outcome.CLOSED);
                });
    }

    @Test
    @DisplayName("控制中心安全页在可恢复错误后保留密码并允许显式清空")
    void controlCenterSecurityKeepsRecoverableInputUntilExplicitClear() throws Exception {
        Path config = tempDir.resolve("security.yaml");
        AppDesktopUiHost delegate = new AppDesktopUiHost(
                6999,
                new TestDesktopConfigFile(config)
        );
        AtomicInteger requests = new AtomicInteger();
        DesktopUiHost host = (DesktopUiHost) Proxy.newProxyInstance(
                DesktopUiHost.class.getClassLoader(),
                new Class<?>[]{DesktopUiHost.class},
                (proxy, method, arguments) -> {
                    if ("guiPostJson".equals(method.getName()) && "change-password".equals(arguments[0])) {
                        requests.incrementAndGet();
                        return new DesktopUiHost.GuiResponse(
                                true,
                                401,
                                DesktopUiHost.GuiValue.of(Map.of("error", "invalid-current")),
                                "",
                                false
                        );
                    }
                    try {
                        return method.invoke(delegate, arguments);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                }
        );
        AppDesktopUiModel model = track(new AppDesktopUiModel(
                6999,
                tempDir.resolve("downloads").toString(),
                config,
                host,
                List::of,
                rendererContract(DesktopUiExperienceProfile.CONTROL_CENTER)
        ));
        awaitButtonEnabled(model, "security.submit");
        long formRevision = textInput(
                model.snapshot().document(),
                "security.current.input"
        ).stateRevision();

        dispatch(
                model,
                DesktopUiNode.EventType.CHANGE,
                "security.current.input",
                DesktopUiNode.Value.text("wrong-password")
        );
        dispatch(
                model,
                DesktopUiNode.EventType.CHANGE,
                "security.new.input",
                DesktopUiNode.Value.text("new-password-123")
        );
        dispatch(
                model,
                DesktopUiNode.EventType.CHANGE,
                "security.confirm.input",
                DesktopUiNode.Value.text("new-password-123")
        );
        dispatch(
                model,
                DesktopUiNode.EventType.ACTIVATE,
                "security.submit",
                DesktopUiNode.Value.empty()
        );

        await(() -> requests.get() == 1 && "gui.security.error.invalid-current".equals(textNode(
                model.snapshot().document(),
                "security.notice"
        ).text().key()));
        assertThat(textInput(
                model.snapshot().document(),
                "security.current.input"
        ).stateRevision()).isEqualTo(formRevision);
        awaitButtonEnabled(model, "security.submit");
        dispatch(
                model,
                DesktopUiNode.EventType.ACTIVATE,
                "security.submit",
                DesktopUiNode.Value.empty()
        );
        await(() -> requests.get() == 2);

        awaitButtonEnabled(model, "security.clear");
        dispatch(
                model,
                DesktopUiNode.EventType.ACTIVATE,
                "security.clear",
                DesktopUiNode.Value.empty()
        );

        assertThat(textInput(
                model.snapshot().document(),
                "security.current.input"
        ).stateRevision()).isEqualTo(formRevision + 1);
        assertThat(textNode(
                model.snapshot().document(),
                "security.notice"
        ).text().key()).isEqualTo(
                "gui.security.status.idle");
        dispatch(
                model,
                DesktopUiNode.EventType.ACTIVATE,
                "security.submit",
                DesktopUiNode.Value.empty()
        );
        assertThat(textNode(
                model.snapshot().document(),
                "security.notice"
        ).text().key()).isEqualTo(
                "gui.security.validation.current-required");
        assertThat(requests).hasValue(2);
    }

}
