package top.sywyar.pixivdownload.gui.panel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("配置页保存后的后端重启引导")
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
