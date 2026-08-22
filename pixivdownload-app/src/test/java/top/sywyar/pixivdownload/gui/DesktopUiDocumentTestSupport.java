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

abstract class DesktopUiDocumentTestSupport {

    protected static final DesktopUiHost.OnboardingSnapshot COMPLETE_ONBOARDING = new DesktopUiHost.OnboardingSnapshot(
            true,
            true,
            4,
            true,
            true
    );

    @TempDir
    Path tempDir;
    protected final List<AppDesktopUiModel> openModels = new ArrayList<>();

    @BeforeEach
    void isolateRuntimeState() {
        System.setProperty(
                RuntimeFiles.STATE_DIR_PROPERTY,
                tempDir.resolve("state").toString()
        );
    }

    @AfterEach
    void clearRuntimeConfigOverride() throws Exception {
        for (AppDesktopUiModel model : openModels) model.close();
        System.clearProperty(RuntimeFiles.CONFIG_DIR_PROPERTY);
        System.clearProperty(RuntimeFiles.STATE_DIR_PROPERTY);
    }

    protected AppDesktopUiModel model() {
        return model(List.of());
    }

    protected AppDesktopUiModel model(DesktopUiExperienceProfile experienceProfile) {
        return model(List.of(), experienceProfile);
    }

    protected AppDesktopUiModel model(List<DesktopUiPluginSource> sources) {
        return model(sources, DesktopUiExperienceProfile.CLASSIC);
    }

    protected AppDesktopUiModel model(
            List<DesktopUiPluginSource> sources,
            DesktopUiExperienceProfile experienceProfile
    ) {
        Path config = tempDir.resolve("config.yaml");
        return track(new AppDesktopUiModel(
                6999,
                tempDir.resolve("downloads").toString(),
                config,
                new AppDesktopUiHost(6999, new TestDesktopConfigFile(config)),
                () -> sources,
                rendererContract(experienceProfile)
        ));
    }

    protected AppDesktopUiModel modelWithOnboarding(
            List<DesktopUiPluginSource> sources,
            DesktopUiExperienceProfile experienceProfile,
            DesktopUiHost.OnboardingSnapshot onboarding
    ) {
        Path config = tempDir.resolve("config.yaml");
        AppDesktopUiHost delegate = new AppDesktopUiHost(
                6999,
                new TestDesktopConfigFile(config)
        );
        DesktopUiHost host = (DesktopUiHost) Proxy.newProxyInstance(
                DesktopUiHost.class.getClassLoader(),
                new Class<?>[]{DesktopUiHost.class},
                (proxy, method, arguments) -> {
                    if ("onboardingState".equals(method.getName())) return onboarding;
                    if ("backendSnapshot".equals(method.getName())) {
                        return new DesktopUiHost.BackendSnapshot(
                                DesktopUiHost.BackendState.RUNNING,
                                null
                        );
                    }
                    if ("subscribeBackend".equals(method.getName())) return (AutoCloseable) () -> {
                    };
                    if (method.getName().startsWith("markOnboarding") || "saveOnboardingProgress".equals(
                            method.getName())) return true;
                    try {
                        return method.invoke(delegate, arguments);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                }
        );
        return track(new AppDesktopUiModel(
                6999,
                tempDir.resolve("downloads").toString(),
                config,
                host,
                () -> sources,
                rendererContract(experienceProfile)
        ));
    }

    protected static Map<String, Object> token(String value) {
        return Map.of(
                "key",
                "",
                "fallback",
                value,
                "arguments",
                List.of()
        );
    }

    protected static DesktopUiPluginSource quickStartSource(
            String id,
            String href,
            AccessPolicy navigationPolicy,
            AccessPolicy routePolicy,
            boolean ownsTarget
    ) {
        return quickStartSource(
                id,
                href,
                navigationPolicy,
                routePolicy,
                ownsTarget,
                List.of()
        );
    }

    protected static DesktopUiPluginSource quickStartSource(
            String id,
            String href,
            AccessPolicy navigationPolicy,
            AccessPolicy routePolicy,
            boolean ownsTarget,
            List<WebRouteContribution> additionalRoutes
    ) {
        PixivFeaturePlugin plugin = new PixivFeaturePlugin() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String displayName() {
                return id;
            }

            @Override
            public String description() {
                return id;
            }

            @Override
            public PluginKind kind() {
                return PluginKind.FEATURE;
            }

            @Override
            public List<WebRouteContribution> routes() {
                List<WebRouteContribution> routes = new ArrayList<>(additionalRoutes);
                if (ownsTarget) {
                    routes.add(new WebRouteContribution(
                            href,
                            routePolicy,
                            Set.of(HttpMethod.GET),
                            false
                    ));
                }
                return List.copyOf(routes);
            }

            @Override
            public List<NavigationContribution> navigation() {
                return List.of(new NavigationContribution(
                        id,
                        NavigationPlacements.DESKTOP_QUICK_START,
                        id,
                        "label",
                        href,
                        "open",
                        navigationPolicy,
                        10
                ));
            }
        };
        return new DesktopUiPluginSource(
                id,
                false,
                plugin,
                plugin.getClass().getClassLoader()
        );
    }

    protected static AppDesktopUiModel.RendererContract rendererContract(
            DesktopUiExperienceProfile experienceProfile
    ) {
        return new AppDesktopUiModel.RendererContract(
                "test",
                experienceProfile,
                Set.of(DesktopUiNode.Kind.values()),
                Set.of(DesktopUiCapability.values())
        );
    }

    protected static DesktopUiPluginSource source(ThemeProviderPlugin plugin) {
        return new DesktopUiPluginSource(
                plugin.id(),
                false,
                plugin,
                plugin.getClass().getClassLoader()
        );
    }

    protected static DesktopUiNode.Choice choice(
            DesktopUiDocument document,
            String id
    ) {
        return nodes(document).stream().filter(DesktopUiNode.Choice.class::isInstance).map(
                DesktopUiNode.Choice.class::cast).filter(choice -> id.equals(choice.id())).findFirst().orElseThrow();
    }

    protected static DesktopUiNode.TextInput textInput(
            DesktopUiDocument document,
            String id
    ) {
        return nodes(document).stream().filter(DesktopUiNode.TextInput.class::isInstance).map(
                DesktopUiNode.TextInput.class::cast).filter(input -> id.equals(input.id())).findFirst().orElseThrow();
    }

    protected static DesktopUiNode.Text textNode(
            DesktopUiDocument document,
            String id
    ) {
        return nodes(document).stream().filter(DesktopUiNode.Text.class::isInstance).map(
                DesktopUiNode.Text.class::cast).filter(text -> id.equals(text.id())).findFirst().orElseThrow();
    }

    protected static int settingsUnsavedCount(DesktopUiDocument document) {
        return Integer.parseInt(textNode(
                document,
                "settings.unsaved-count"
        ).text().arguments().get(
                0));
    }

    protected record ThemeProviderPlugin(
            String id,
            List<GuiThemeContribution> themes
    ) implements
            PixivFeaturePlugin,
            DesktopUiProvider {
        @Override
        public String displayName() {
            return id;
        }

        @Override
        public String description() {
            return id;
        }

        @Override
        public PluginKind kind() {
            return PluginKind.FEATURE;
        }

        @Override
        public List<GuiThemeContribution> guiThemes() {
            return themes;
        }

        @Override
        public Set<DesktopUiNode.Kind> supportedNodeKinds() {
            return Set.of(DesktopUiNode.Kind.TEXT);
        }

        @Override
        public Set<DesktopUiCapability> supportedCapabilities() {
            return Set.of();
        }

        @Override
        public DesktopUiSession launch(DesktopUiContext context) {
            throw new UnsupportedOperationException();
        }
    }

    protected AppDesktopUiModel track(AppDesktopUiModel model) {
        openModels.add(model);
        return model;
    }

    protected static PixivFeaturePlugin richConfigPlugin() {
        return new PixivFeaturePlugin() {
            @Override
            public String id() {
                return "schema-test";
            }

            @Override
            public String displayName() {
                return "plugin.name";
            }

            @Override
            public String description() {
                return "plugin.summary";
            }

            @Override
            public String displayNamespace() {
                return "schema-test";
            }

            @Override
            public PluginKind kind() {
                return PluginKind.FEATURE;
            }

            @Override
            public List<WebRouteContribution> routes() {
                return List.of(new WebRouteContribution(
                        "/api/gui/schema-test-action",
                        AccessPolicy.GUI,
                        Set.of(HttpMethod.POST),
                        false
                ));
            }

            @Override
            public List<GuiConfigContribution> guiConfigContributions() {
                GuiConfigFieldContribution field = new GuiConfigFieldContribution(
                        "schema-test.enabled",
                        "schema-test",
                        "field.label",
                        "field.help",
                        null,
                        GuiConfigFieldType.BOOL,
                        "false",
                        0,
                        false,
                        GuiConfigEffect.HOT_RELOAD,
                        List.of(),
                        List.of(GuiConfigCondition.equalsTo("schema-test.mode", "fast/mode")),
                        List.of(),
                        null,
                        null
                );
                GuiConfigFieldContribution mode = new GuiConfigFieldContribution(
                        "schema-test.mode",
                        "schema-test",
                        "field.label",
                        "field.help",
                        null,
                        GuiConfigFieldType.ENUM,
                        "safe mode",
                        1,
                        false,
                        GuiConfigEffect.HOT_RELOAD,
                        List.of("safe mode", "fast/mode"),
                        List.of(),
                        List.of(),
                        null,
                        null
                );
                GuiConfigSectionContribution section = new GuiConfigSectionContribution(
                        "schema-test.section",
                        "schema-test",
                        "section.title",
                        "section.help",
                        null,
                        GuiConfigSectionLayout.CARD_SWITCHER,
                        0,
                        List.of(
                                new GuiConfigFieldLayoutContribution(
                                        field.key(),
                                        "main",
                                        "card.label",
                                        0
                                ),
                                new GuiConfigFieldLayoutContribution(
                                        mode.key(),
                                        "main",
                                        "card.label",
                                        1
                                )
                        ),
                        List.of(
                                new GuiConfigActionContribution(
                                        "schema-test.global-action",
                                        "action.global.label",
                                        "action.help",
                                        null,
                                        null,
                                        "schema-test-action",
                                        1_000,
                                        0,
                                        List.of(),
                                        "",
                                        List.of(),
                                        null
                                ),
                                new GuiConfigActionContribution(
                                        "schema-test.action",
                                        "action.label",
                                        "action.help",
                                        null,
                                        "main",
                                        "schema-test-action",
                                        1_000,
                                        1,
                                        List.of(),
                                        "",
                                        List.of(),
                                        null
                                )
                        ),
                        List.of(
                                new GuiConfigPresetContribution(
                                        "schema-test.global-preset",
                                        "preset.global.label",
                                        "preset.help",
                                        null,
                                        null,
                                        0,
                                        field.key(),
                                        "false",
                                        Map.of(field.key(), "false"),
                                        List.of()
                                ),
                                new GuiConfigPresetContribution(
                                        "schema-test.preset",
                                        "preset.label",
                                        "preset.help",
                                        null,
                                        "main",
                                        1,
                                        field.key(),
                                        "true",
                                        Map.of(field.key(), "true")
                                )
                        )
                );
                return List.of(new GuiConfigContribution(
                        List.of(new GuiConfigGroupContribution(
                                "schema-test",
                                "group.label",
                                null,
                                2_000,
                                true
                        )),
                        List.of(field, mode),
                        List.of(section)
                ));
            }
        };
    }

    protected static PixivFeaturePlugin mergeableCardPlugin(
            String id,
            String cardId,
            String noticeKey
    ) {
        return new PixivFeaturePlugin() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String displayName() {
                return "plugin.name";
            }

            @Override
            public String description() {
                return "plugin.summary";
            }

            @Override
            public String displayNamespace() {
                return id;
            }

            @Override
            public PluginKind kind() {
                return PluginKind.FEATURE;
            }

            @Override
            public List<GuiConfigContribution> guiConfigContributions() {
                String fieldKey = id + ".value";
                GuiConfigFieldContribution field = new GuiConfigFieldContribution(
                        fieldKey,
                        GuiConfigGroups.PLUGINS,
                        "field.label",
                        GuiConfigFieldType.STRING,
                        "",
                        0
                );
                GuiConfigSectionContribution section = new GuiConfigSectionContribution(
                        "merged.cards",
                        GuiConfigGroups.PLUGINS,
                        "",
                        "",
                        id,
                        "card.selector",
                        "",
                        "",
                        "",
                        List.of(new GuiConfigSectionNoticeContribution(
                                id + ".notice",
                                noticeKey,
                                0
                        )),
                        GuiConfigSectionLayout.CARD_SWITCHER,
                        0,
                        List.of(new GuiConfigFieldLayoutContribution(
                                fieldKey,
                                cardId,
                                "card.label",
                                0
                        )),
                        List.of(),
                        List.of(),
                        true,
                        true
                );
                return List.of(new GuiConfigContribution(
                        List.of(),
                        List.of(field),
                        List.of(section)
                ));
            }
        };
    }

    protected static PixivFeaturePlugin pluginWithMigratedFields() {
        return new PixivFeaturePlugin() {
            @Override
            public String id() {
                return "fixture";
            }

            @Override
            public String displayName() {
                return "plugin.name";
            }

            @Override
            public String description() {
                return "plugin.summary";
            }

            @Override
            public PluginKind kind() {
                return PluginKind.FEATURE;
            }

            @Override
            public List<GuiConfigContribution> guiConfigContributions() {
                return List.of(new GuiConfigContribution(
                        List.of(),
                        List.of(
                                new GuiConfigFieldContribution(
                                        "fixture.value",
                                        GuiConfigGroups.PLUGINS,
                                        "fixture.value.label",
                                        GuiConfigFieldType.STRING,
                                        "default",
                                        0
                                ),
                                new GuiConfigFieldContribution(
                                        "fixture.secret",
                                        GuiConfigGroups.PLUGINS,
                                        "fixture.secret.label",
                                        "",
                                        GuiConfigFieldType.PASSWORD,
                                        "",
                                        1,
                                        true,
                                        GuiConfigEffect.BACKEND_RESTART
                                )
                        )
                ));
            }
        };
    }

    protected static void collectIds(DesktopUiNode node, List<String> ids) {
        ids.add(node.id());
        node.childNodes().forEach(child -> collectIds(child, ids));
    }

    protected static List<DesktopUiNode> nodes(DesktopUiDocument document) {
        List<DesktopUiNode> nodes = new ArrayList<>();
        document.pages().forEach(page -> {
            collectNodes(page.content(), nodes);
            page.floatingAction().ifPresent(node -> collectNodes(node, nodes));
        });
        document.dialogs().forEach(dialog -> collectNodes(dialog.content(), nodes));
        return nodes;
    }

    protected static Set<String> bindingIds(DesktopUiDocument document) {
        return nodes(document).stream().map(node -> {
            if (node instanceof DesktopUiNode.TextInput input) return input.bindingId();
            if (node instanceof DesktopUiNode.Toggle toggle) return toggle.bindingId();
            if (node instanceof DesktopUiNode.Choice choice) return choice.bindingId();
            if (node instanceof DesktopUiNode.NumberInput input) return input.bindingId();
            return "";
        }).filter(id -> !id.isBlank()).collect(java.util.stream.Collectors.toSet());
    }

    protected static DesktopUiNode.TextInput configTextInput(
            DesktopUiDocument document,
            String key
    ) {
        return nodes(document).stream().filter(DesktopUiNode.TextInput.class::isInstance).map(
                DesktopUiNode.TextInput.class::cast).filter(input -> input.bindingId().endsWith(key)).findFirst().orElseThrow();
    }

    protected static DesktopUiHost.GuiResponse response(Map<String, Object> body) {
        return new DesktopUiHost.GuiResponse(
                true,
                200,
                DesktopUiHost.GuiValue.of(body),
                "",
                false
        );
    }

    protected static void dispatch(
            AppDesktopUiModel model,
            DesktopUiNode.EventType type,
            String nodeId,
            DesktopUiNode.Value value
    ) {
        synchronized (model) {
            DesktopUiSnapshot snapshot = model.snapshot();
            long interactionRevision = type == DesktopUiNode.EventType.ACTIVATE ? -1L : snapshot.interactionRevisions().get(
                    nodeId);
            model.dispatch(new DesktopUiNode.Event(
                    snapshot.revision(),
                    interactionRevision,
                    type,
                    nodeId,
                    value
            ));
        }
    }

    protected static String read(DesktopUiHost.ConfigFile config, String key) {
        try {
            return config.read(key);
        } catch (Exception ignored) {
            return null;
        }
    }

    protected static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10L);
        assertThat(condition.getAsBoolean()).isTrue();
    }

    protected static void awaitButtonEnabled(
            AppDesktopUiModel model,
            String id
    ) throws
            InterruptedException {
        await(() -> nodes(model.snapshot().document()).stream().filter(DesktopUiNode.Button.class::isInstance).map(
                DesktopUiNode.Button.class::cast).anyMatch(button -> id.equals(button.id()) && button.enabled()));
    }

    protected static void awaitChoiceEnabled(
            AppDesktopUiModel model,
            String id
    ) throws
            InterruptedException {
        await(() -> nodes(model.snapshot().document()).stream().filter(DesktopUiNode.Choice.class::isInstance).map(
                DesktopUiNode.Choice.class::cast).anyMatch(choice -> id.equals(choice.id()) && choice.enabled()));
    }

    protected static <T extends DesktopUiNode> T assertPageContent(
            DesktopUiDocument document,
            String pageId,
            Class<T> type
    ) {
        DesktopUiNode content = document.pages().stream().filter(page -> page.id().equals(pageId)).findFirst().orElseThrow().content();
        assertThat(content).isInstanceOf(DesktopUiNode.Surface.class);
        DesktopUiNode nested = ((DesktopUiNode.Surface) content).content();
        assertThat(nested).isInstanceOf(type);
        return type.cast(nested);
    }

    protected static void collectNodes(
            DesktopUiNode node,
            List<DesktopUiNode> nodes
    ) {
        nodes.add(node);
        node.childNodes().forEach(child -> collectNodes(child, nodes));
    }

    protected static void collectTokens(
            DesktopUiDocument document,
            List<DesktopUiNode.TextToken> tokens
    ) {
        document.pages().forEach(page -> {
            tokens.add(page.title());
            collectTokens(page.content(), tokens);
            page.floatingAction().ifPresent(node -> collectTokens(node, tokens));
        });
        document.dialogs().forEach(dialog -> {
            tokens.add(dialog.title());
            collectTokens(dialog.content(), tokens);
        });
    }

    protected static void collectTokens(
            DesktopUiNode node,
            List<DesktopUiNode.TextToken> tokens
    ) {
        if (node instanceof DesktopUiNode.Group group) tokens.add(group.title());
        else if (node instanceof DesktopUiNode.Form form) {
            add(tokens, form.labelSuffix());
            form.rows().forEach(row -> {
                tokens.add(row.label());
                add(tokens, row.help());
            });
        } else if (node instanceof DesktopUiNode.Tabs tabs)
            tabs.tabs().forEach(tab -> tokens.add(tab.title()));
        else if (node instanceof DesktopUiNode.Text text) tokens.add(text.text());
        else if (node instanceof DesktopUiNode.Image image) tokens.add(image.altText());
        else if (node instanceof DesktopUiNode.Progress progress) add(
                tokens,
                progress.text()
        );
        else if (node instanceof DesktopUiNode.TextInput input) {
            tokens.add(input.label());
            add(tokens, input.help());
        } else if (node instanceof DesktopUiNode.Toggle toggle) {
            tokens.add(toggle.label());
            add(tokens, toggle.help());
        } else if (node instanceof DesktopUiNode.Choice choice) {
            tokens.add(choice.label());
            add(tokens, choice.help());
            choice.options().forEach(option -> tokens.add(option.label()));
        } else if (node instanceof DesktopUiNode.NumberInput input) {
            tokens.add(input.label());
            add(tokens, input.help());
        } else if (node instanceof DesktopUiNode.Table table) {
            table.columns().forEach(column -> tokens.add(column.label()));
        } else if (node instanceof DesktopUiNode.Tree tree) {
            tree.items().forEach(item -> collectTokens(item, tokens));
        } else if (node instanceof DesktopUiNode.Button button) {
            tokens.add(button.label());
            add(tokens, button.help());
        } else if (node instanceof DesktopUiNode.Link link) {
            tokens.add(link.label());
            add(tokens, link.help());
        }
        node.childNodes().forEach(child -> collectTokens(child, tokens));
    }

    protected static void collectTokens(
            DesktopUiNode.TreeItem item,
            List<DesktopUiNode.TextToken> tokens
    ) {
        tokens.add(item.label());
        item.children().forEach(child -> collectTokens(child, tokens));
    }

    protected static void add(
            List<DesktopUiNode.TextToken> tokens,
            DesktopUiNode.TextToken token
    ) {
        if (token != null) tokens.add(token);
    }
}
