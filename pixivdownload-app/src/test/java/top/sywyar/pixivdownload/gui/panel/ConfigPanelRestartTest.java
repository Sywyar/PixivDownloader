package top.sywyar.pixivdownload.gui.panel;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.gui.DesktopUiTestHost;
import top.sywyar.pixivdownload.gui.config.ConfigFieldSnapshot;
import top.sywyar.pixivdownload.gui.config.ConfigFieldSpec;
import top.sywyar.pixivdownload.gui.config.FieldType;
import top.sywyar.pixivdownload.gui.i18n.GuiMessages;

import javax.swing.JButton;
import java.awt.Component;
import java.awt.Container;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("配置页保存与后端重启引导")
class ConfigPanelRestartTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("保存需重启配置并确认后会自动请求重启后端")
    void restartsBackendAfterRestartRequiredSettingIsSavedAndConfirmed() throws Exception {
        AtomicInteger confirmations = new AtomicInteger();
        AtomicInteger restarts = new AtomicInteger();
        Path configPath = tempDir.resolve("config.yaml");
        Files.writeString(configPath, "fixture.restart: before\n", StandardCharsets.UTF_8);

        ConfigPanel panel = panel(configPath,
                () -> confirmations.incrementAndGet() == 1,
                () -> restarts.incrementAndGet() == 1);
        panel.setFieldValue("fixture.restart", "after");

        findButton(panel, GuiMessages.get("gui.button.save")).doClick();

        assertThat(confirmations).hasValue(1);
        assertThat(restarts).hasValue(1);
        assertThat(Files.readString(configPath, StandardCharsets.UTF_8))
                .contains("fixture.restart: after");
    }

    @Test
    @DisplayName("保存需重启配置但选择稍后时不会请求重启后端")
    void leavesBackendRunningWhenRestartIsDeferred() throws Exception {
        AtomicInteger confirmations = new AtomicInteger();
        AtomicInteger restarts = new AtomicInteger();
        Path configPath = tempDir.resolve("config.yaml");
        Files.writeString(configPath, "fixture.restart: before\n", StandardCharsets.UTF_8);

        ConfigPanel panel = panel(configPath,
                () -> {
                    confirmations.incrementAndGet();
                    return false;
                },
                () -> {
                    restarts.incrementAndGet();
                    return true;
                });
        panel.setFieldValue("fixture.restart", "after");

        findButton(panel, GuiMessages.get("gui.button.save")).doClick();

        assertThat(confirmations).hasValue(1);
        assertThat(restarts).hasValue(0);
        assertThat(Files.readString(configPath, StandardCharsets.UTF_8))
                .contains("fixture.restart: after");
    }

    @Test
    @DisplayName("后端重启请求异常时配置仍已保存且不会让界面动作抛错")
    void keepsSavedConfigurationWhenBackendRestartRequestFails() throws Exception {
        Path configPath = tempDir.resolve("config.yaml");
        Files.writeString(configPath, "fixture.restart: before\n", StandardCharsets.UTF_8);
        ConfigPanel panel = panel(configPath, () -> true, () -> {
            throw new IllegalStateException("restart failed");
        });
        panel.setFieldValue("fixture.restart", "after");

        assertThatCode(() -> findButton(panel, GuiMessages.get("gui.button.save")).doClick())
                .doesNotThrowAnyException();

        assertThat(Files.readString(configPath, StandardCharsets.UTF_8))
                .contains("fixture.restart: after");
    }

    @Test
    @DisplayName("插件端口仅在可见后校验且重名错误按作用域报告")
    void blankPluginPortOnlyBlocksSaveWhileVisibleAndReportsScope() throws Exception {
        String previousConfigDir = System.getProperty(RuntimeFiles.CONFIG_DIR_PROPERTY);
        Path configDir = tempDir.resolve("config");
        GuiMessages.setLocale(Locale.US);
        System.setProperty(RuntimeFiles.CONFIG_DIR_PROPERTY, configDir.toString());
        try {
            Files.createDirectories(configDir);
            Path configPath = configDir.resolve(RuntimeFiles.CONFIG_YAML);
            Files.writeString(configPath, "fixture.restart: before\n", StandardCharsets.UTF_8);
            String group = GuiMessages.get("gui.config.group.server");
            ConfigFieldSpec changed = ConfigFieldSpec.builder(
                            "fixture.restart", "Restart fixture", FieldType.STRING, group)
                    .defaultValue("before")
                    .build();
            ConfigFieldSpec corePort = ConfigFieldSpec.builder(
                            "fixture.core-port", "Proxy port", FieldType.STRING, group)
                    .defaultValue("7890")
                    .validator(value -> value.isBlank() ? "Enter a valid port number" : null)
                    .build();
            ConfigFieldSpec mode = ConfigFieldSpec.builder(
                            "fixture.proxy.mode", "Proxy mode", FieldType.ENUM, group)
                    .ownerPluginId("fixture")
                    .defaultValue("inherit")
                    .enumValues("inherit", "custom")
                    .build();
            ConfigFieldSpec port = ConfigFieldSpec.builder(
                            "fixture.proxy.port", "Proxy port", FieldType.PORT, group)
                    .ownerPluginId("fixture")
                    .defaultValue("")
                    .visibleWhen(snapshot -> snapshot.equals("fixture.proxy.mode", "custom"))
                    .build();
            DesktopUiTestHost.install(configPath);
            ConfigPanel panel = new ConfigPanel(configPath, 6999, path -> path,
                    new ConfigFieldSnapshot(List.of(group), List.of(changed, corePort, mode, port), List.of()),
                    null, null, () -> false, () -> false);
            panel.setFieldValue("fixture.restart", "after");

            findButton(panel, GuiMessages.get("gui.button.save")).doClick();

            assertThat(Files.readString(configPath, StandardCharsets.UTF_8))
                    .contains("fixture.restart: after");
            assertThat(Files.readString(
                    RuntimeFiles.resolvePluginConfigPath("fixture", "properties"), StandardCharsets.UTF_8))
                    .contains("fixture.proxy.port=");

            panel.setFieldValue("fixture.restart", "blocked");
            panel.setFieldValue("fixture.core-port", "");
            panel.setFieldValue("fixture.proxy.mode", "custom");
            panel.updateEnabledStates();
            Logger logger = (Logger) LoggerFactory.getLogger(ConfigPanel.class);
            ListAppender<ILoggingEvent> capture = new ListAppender<>();
            capture.start();
            logger.addAppender(capture);
            try {
                findButton(panel, GuiMessages.get("gui.button.save")).doClick();
            } finally {
                logger.detachAppender(capture);
                capture.stop();
            }

            assertThat(Files.readString(configPath, StandardCharsets.UTF_8))
                    .contains("fixture.restart: after")
                    .doesNotContain("fixture.restart: blocked");
            assertThat(capture.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .anySatisfy(message -> assertThat(message)
                            .contains(group + " / Proxy port：")
                            .contains("; " + GuiMessages.get("gui.config.scope.plugins")
                                    + " [fixture] / " + group + " / Proxy port："));
        } finally {
            GuiMessages.clearLocaleOverride();
            if (previousConfigDir == null) {
                System.clearProperty(RuntimeFiles.CONFIG_DIR_PROPERTY);
            } else {
                System.setProperty(RuntimeFiles.CONFIG_DIR_PROPERTY, previousConfigDir);
            }
        }
    }

    private ConfigPanel panel(Path configPath,
                              java.util.function.BooleanSupplier confirmation,
                              java.util.function.BooleanSupplier restarter) {
        String group = GuiMessages.get("gui.config.group.server");
        ConfigFieldSpec field = ConfigFieldSpec.builder(
                        "fixture.restart", "Restart fixture", FieldType.STRING, group)
                .defaultValue("before")
                .build();
        ConfigFieldSnapshot snapshot = new ConfigFieldSnapshot(
                List.of(group), List.of(field), List.of());
        DesktopUiTestHost.install(configPath);
        return new ConfigPanel(configPath, 6999, path -> path, snapshot,
                null, null, confirmation, restarter);
    }

    private static JButton findButton(Container root, String text) {
        for (Component component : root.getComponents()) {
            if (component instanceof JButton button && text.equals(button.getText())) {
                return button;
            }
            if (component instanceof Container child) {
                JButton found = findButtonOrNull(child, text);
                if (found != null) {
                    return found;
                }
            }
        }
        throw new AssertionError("button not found: " + text);
    }

    private static JButton findButtonOrNull(Container root, String text) {
        for (Component component : root.getComponents()) {
            if (component instanceof JButton button && text.equals(button.getText())) {
                return button;
            }
            if (component instanceof Container child) {
                JButton found = findButtonOrNull(child, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
