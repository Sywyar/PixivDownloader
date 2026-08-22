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

class DesktopUiEventDocumentTest extends DesktopUiDocumentTestSupport {
    @Test
    @DisplayName("业务输入立即发布并由重新加载的宿主值覆盖")
    void businessInputIsControlledByPublishedDocument() throws Exception {
        AppDesktopUiModel model = model();
        awaitButtonEnabled(model, "config.reload");
        DesktopUiNode.TextInput root = configTextInput(
                model.snapshot().document(),
                "download.root-folder"
        );
        long initialRevision = model.snapshot().revision();

        dispatch(
                model,
                DesktopUiNode.EventType.CHANGE,
                root.id(),
                DesktopUiNode.Value.text("changed-root")
        );

        assertThat(model.snapshot().revision()).isGreaterThan(initialRevision);
        assertThat(configTextInput(
                model.snapshot().document(),
                "download.root-folder"
        ).value()).isEqualTo("changed-root");
        long editedRevision = model.snapshot().revision();

        dispatch(
                model,
                DesktopUiNode.EventType.ACTIVATE,
                "config.reload",
                DesktopUiNode.Value.empty()
        );

        assertThat(model.snapshot().revision()).isGreaterThan(editedRevision);
        assertThat(configTextInput(
                model.snapshot().document(),
                "download.root-folder"
        ).value()).isEqualTo(root.value());
    }

    @Test
    @DisplayName("连续输入沿用交互修订号且拒绝伪造代际")
    void continuousInputUsesInteractionRevisionInsteadOfDocumentRevision() {
        AppDesktopUiModel model = model();
        DesktopUiSnapshot observed = model.snapshot();
        DesktopUiNode.TextInput root = configTextInput(
                observed.document(),
                "download.root-folder"
        );
        long interactionRevision = observed.interactionRevisions().get(root.id());

        model.dispatch(new DesktopUiNode.Event(
                observed.revision(),
                interactionRevision,
                DesktopUiNode.EventType.CHANGE,
                root.id(),
                DesktopUiNode.Value.text("first-root")
        ));
        DesktopUiSnapshot afterFirst = model.snapshot();
        assertThat(afterFirst.revision()).isGreaterThan(observed.revision());
        assertThat(afterFirst.interactionRevisions().get(root.id())).isEqualTo(interactionRevision);

        model.dispatch(new DesktopUiNode.Event(
                observed.revision(),
                interactionRevision,
                DesktopUiNode.EventType.CHANGE,
                root.id(),
                DesktopUiNode.Value.text("second-root")
        ));
        assertThat(configTextInput(
                model.snapshot().document(),
                "download.root-folder"
        ).value()).isEqualTo("second-root");

        model.dispatch(new DesktopUiNode.Event(
                model.snapshot().revision(),
                interactionRevision + 1L,
                DesktopUiNode.EventType.CHANGE,
                root.id(),
                DesktopUiNode.Value.text("forged-root")
        ));
        assertThat(configTextInput(
                model.snapshot().document(),
                "download.root-folder"
        ).value()).isEqualTo("second-root");
    }

    @Test
    @DisplayName("插件代际变化提升其值控件的交互修订号")
    void pluginGenerationChangesInteractionRevision() {
        PixivFeaturePlugin plugin = richConfigPlugin();
        AtomicReference<List<DesktopUiPluginSource>> sources = new AtomicReference<>(List.of(new DesktopUiPluginSource(
                plugin.id(),
                false,
                plugin,
                plugin.getClass().getClassLoader(),
                "schema-test-package",
                1L
        )));
        Path config = tempDir.resolve("generation-config.yaml");
        AppDesktopUiModel model = track(new AppDesktopUiModel(
                6999,
                tempDir.resolve("downloads").toString(),
                config,
                new AppDesktopUiHost(6999, new TestDesktopConfigFile(config)),
                sources::get,
                rendererContract(DesktopUiExperienceProfile.CLASSIC)
        ));
        DesktopUiSnapshot observed = model.snapshot();
        DesktopUiNode.Choice mode = nodes(observed.document()).stream().filter(DesktopUiNode.Choice.class::isInstance).map(
                DesktopUiNode.Choice.class::cast).filter(choice -> choice.bindingId().endsWith(
                "schema-test.mode")).findFirst().orElseThrow();

        sources.set(List.of(new DesktopUiPluginSource(
                plugin.id(),
                false,
                plugin,
                plugin.getClass().getClassLoader(),
                "schema-test-package",
                2L
        )));
        dispatch(
                model,
                DesktopUiNode.EventType.ACTIVATE,
                "debug.unlock.shortcut",
                DesktopUiNode.Value.empty()
        );

        assertThat(model.snapshot().interactionRevisions().get(mode.id())).isGreaterThan(observed.interactionRevisions().get(
                mode.id()));
    }

    @Test
    @DisplayName("过期事件和已关闭对话框事件不会执行")
    void staleAndClosedDialogEventsAreIgnored() throws Exception {
        AppDesktopUiModel model = model();
        awaitButtonEnabled(model, "config.reset");
        long pageRevision = model.snapshot().revision();
        model.dispatch(new DesktopUiNode.Event(
                pageRevision,
                -1L,
                DesktopUiNode.EventType.ACTIVATE,
                "config.reset",
                DesktopUiNode.Value.empty()
        ));
        long dialogRevision = model.snapshot().revision();
        DesktopUiNode.Event confirm = new DesktopUiNode.Event(
                dialogRevision,
                -1L,
                DesktopUiNode.EventType.ACTIVATE,
                "config.reset.confirm",
                DesktopUiNode.Value.empty()
        );

        model.dispatch(new DesktopUiNode.Event(
                pageRevision,
                -1L,
                DesktopUiNode.EventType.ACTIVATE,
                "config.reset.confirm",
                DesktopUiNode.Value.empty()
        ));
        assertThat(model.snapshot().document().dialogs()).extracting(DesktopUiDocument.Dialog::id).containsExactly(
                "config.reset.dialog");

        model.dispatch(confirm);
        assertThat(model.snapshot().document().dialogs()).isEmpty();
        long closedRevision = model.snapshot().revision();
        model.dispatch(confirm);
        assertThat(model.snapshot().document().dialogs()).isEmpty();
        assertThat(model.snapshot().revision()).isEqualTo(closedRevision);
    }

    @Test
    @DisplayName("禁用节点的重复事件不会执行业务动作")
    void disabledNodeEventsAreRejectedBeforeDispatch() {
        AppDesktopUiModel model = model();
        DesktopUiNode.Button disabled = nodes(model.snapshot().document()).stream().filter(
                DesktopUiNode.Button.class::isInstance).map(DesktopUiNode.Button.class::cast).filter(
                button -> !button.enabled()).findFirst().orElseThrow();
        DesktopUiDocument before = model.snapshot().document();
        long revision = model.snapshot().revision();
        DesktopUiNode.Event click = new DesktopUiNode.Event(
                revision,
                -1L,
                DesktopUiNode.EventType.ACTIVATE,
                disabled.id(),
                DesktopUiNode.Value.empty()
        );

        model.dispatch(click);
        assertThat(model.snapshot().document()).isEqualTo(before);
        assertThat(model.snapshot().revision()).isEqualTo(revision);

        model.dispatch(click);
        assertThat(model.snapshot().document()).isEqualTo(before);
        assertThat(model.snapshot().revision()).isEqualTo(revision);
    }

    @Test
    @DisplayName("当前文档拒绝伪造选项和值类型")
    void currentDocumentRejectsForgedOptionsAndValueKinds() {
        AppDesktopUiModel model = model();
        DesktopUiNode.Choice language = nodes(model.snapshot().document()).stream().filter(
                DesktopUiNode.Choice.class::isInstance).map(DesktopUiNode.Choice.class::cast).filter(
                choice -> "interface.language.input".equals(choice.id())).findFirst().orElseThrow();
        List<String> selected = language.selectedIds();
        long optionRevision = model.snapshot().revision();

        model.dispatch(new DesktopUiNode.Event(
                optionRevision,
                model.snapshot().interactionRevisions().get(language.id()),
                DesktopUiNode.EventType.SELECTION,
                language.id(),
                DesktopUiNode.Value.selection("forged-option")
        ));
        DesktopUiNode.Choice afterForgedOption = nodes(model.snapshot().document()).stream().filter(
                DesktopUiNode.Choice.class::isInstance).map(DesktopUiNode.Choice.class::cast).filter(
                choice -> language.id().equals(choice.id())).findFirst().orElseThrow();
        assertThat(afterForgedOption.selectedIds()).isEqualTo(selected);

        DesktopUiNode.TextInput root = configTextInput(
                model.snapshot().document(),
                "download.root-folder"
        );
        long typeRevision = model.snapshot().revision();
        model.dispatch(new DesktopUiNode.Event(
                typeRevision,
                model.snapshot().interactionRevisions().get(root.id()),
                DesktopUiNode.EventType.SELECTION,
                root.id(),
                DesktopUiNode.Value.selection("forged-value")
        ));
        assertThat(configTextInput(
                model.snapshot().document(),
                "download.root-folder"
        ).value()).isEqualTo(root.value());
        assertThat(model.snapshot().revision()).isEqualTo(typeRevision);
    }

    @Test
    @DisplayName("后端进入运行态后自动刷新插件状态")
    void backendConnectionRefreshesPluginStatusWithoutManualAction() throws Exception {
        Path config = tempDir.resolve("backend-connect.yaml");
        AppDesktopUiHost delegate = new AppDesktopUiHost(
                6999,
                new TestDesktopConfigFile(config)
        );
        AtomicBoolean connected = new AtomicBoolean();
        AtomicReference<Consumer<DesktopUiHost.BackendSnapshot>> subscriber = new AtomicReference<>();
        DesktopUiHost host = (DesktopUiHost) Proxy.newProxyInstance(
                DesktopUiHost.class.getClassLoader(),
                new Class<?>[]{DesktopUiHost.class},
                (proxy, method, arguments) -> {
                    if ("backendSnapshot".equals(method.getName())) {
                        return new DesktopUiHost.BackendSnapshot(
                                DesktopUiHost.BackendState.STOPPED,
                                null
                        );
                    }
                    if ("subscribeBackend".equals(method.getName())) {
                        @SuppressWarnings("unchecked") Consumer<DesktopUiHost.BackendSnapshot> listener = (Consumer<DesktopUiHost.BackendSnapshot>) arguments[0];
                        subscriber.set(listener);
                        return (AutoCloseable) () -> {
                        };
                    }
                    if ("guiGet".equals(method.getName())) {
                        if (!connected.get())
                            return new DesktopUiHost.GuiResponse(
                                    false,
                                    0,
                                    null,
                                    "",
                                    false
                            );
                        return switch ((String) arguments[0]) {
                            case "status" -> response(Map.of(
                                    "port",
                                    "6999",
                                    "mode",
                                    "solo",
                                    "startTime",
                                    "now",
                                    "httpsEnabled",
                                    false
                            ));
                            case "onboarding" -> response(Map.of(
                                    "batchVisited",
                                    false,
                                    "completedSteps",
                                    List.of()
                            ));
                            case "plugins/status" -> response(Map.of(
                                    "recoveryMode",
                                    false,
                                    "plugins",
                                    List.of(Map.of(
                                            "id",
                                            "connected-plugin",
                                            "name",
                                            "Connected plugin",
                                            "source",
                                            "external",
                                            "status",
                                            "STARTED",
                                            "runtimePhase",
                                            "STARTED",
                                            "managed",
                                            true,
                                            "required",
                                            false,
                                            "version",
                                            "1.0.0"
                                    ))
                            ));
                            default -> new DesktopUiHost.GuiResponse(
                                    false,
                                    0,
                                    null,
                                    "",
                                    false
                            );
                        };
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
                rendererContract(DesktopUiExperienceProfile.CLASSIC)
        ));
        await(() -> subscriber.get() != null);

        connected.set(true);
        subscriber.get().accept(new DesktopUiHost.BackendSnapshot(
                DesktopUiHost.BackendState.RUNNING,
                null
        ));

        await(() -> nodes(model.snapshot().document()).stream().anyMatch(node -> "plugins.card.connected-plugin".equals(
                node.id())));
    }

}
