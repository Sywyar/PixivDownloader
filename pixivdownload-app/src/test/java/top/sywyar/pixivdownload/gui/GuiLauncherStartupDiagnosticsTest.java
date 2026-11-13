package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.slf4j.Logger;
import top.sywyar.pixivdownload.gui.config.ConfigFileEditor;
import top.sywyar.pixivdownload.i18n.MessageBundles;

import java.util.Locale;
import java.io.IOException;
import java.net.BindException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("桌面启动失败诊断")
class GuiLauncherStartupDiagnosticsTest {
    @ParameterizedTest
    @CsvSource({"download.root-folder, unknown", "download.mode-templates.user, unknown",
            "download.mode-templates.series, unknown", "download.mode-templates.user, YAML"})
    @DisplayName("真实配置 Bean 初始化失败后，启动提示保留目录设置位置与未知变量")
    void diagnosesDirectoryConfigurationFailure(String key, String variable) {
        var config = new top.sywyar.pixivdownload.core.appconfig.DownloadConfig();
        String invalid = "private-directory/{" + variable + "}/{artwork_id}";
        if (key.equals("download.root-folder")) config.setRootFolder(invalid);
        else config.setModeTemplates(java.util.Map.of(key.substring("download.mode-templates.".length()), invalid));
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.registerBean("downloadConfig", top.sywyar.pixivdownload.core.appconfig.DownloadConfig.class, () -> config);
            var failure = org.junit.jupiter.api.Assertions.assertThrows(
                    org.springframework.beans.factory.BeanCreationException.class, context::refresh);
            String actual = ReflectionTestUtils.invokeMethod(GuiLauncher.class, "diagnoseStartupError", failure);
            assertThat(actual).contains(key, "{" + variable + "}")
                    .doesNotContain("private-directory", "BeanCreationException", "unknown-variable");
        }
    }

    @Test
    @DisplayName("中文启动弹窗显示目录错误详情，日志保持英文且不重复记录弹窗正文")
    void directoryDialogIsLocalizedAndLogRemainsEnglish() {
        var failure = org.junit.jupiter.api.Assertions.assertThrows(
                top.sywyar.pixivdownload.core.work.directory.DirectoryTemplateException.class,
                () -> top.sywyar.pixivdownload.core.work.directory.WorkDirectoryTemplate.parse(
                        "private-directory/{unknown}/{artwork_id}", "download.mode-templates.user"));
        var wrapped = new IllegalStateException("Unable to start web server", failure);
        Locale originalLocale = Locale.getDefault();
        Object originalLog = ReflectionTestUtils.getField(GuiLauncher.class, "log");
        Logger logger = mock(Logger.class);
        try (var dialogs = org.mockito.Mockito.mockStatic(DesktopUiDialogs.class)) {
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE);
            ReflectionTestUtils.setField(GuiLauncher.class, "log", logger);
            ReflectionTestUtils.invokeMethod(GuiLauncher.class, "showBackendStartupFailure", wrapped);
            var displayed = org.mockito.ArgumentCaptor.forClass(String.class);
            dialogs.verify(() -> DesktopUiDialogs.show(org.mockito.ArgumentMatchers.isNull(),
                    org.mockito.ArgumentMatchers.anyString(), displayed.capture(), org.mockito.ArgumentMatchers.isNull()));
            String detail = MessageBundles.get("directory-template.unknown-variable", "{unknown}");
            assertThat(displayed.getValue()).contains(MessageBundles.get("gui.launcher.diagnostic.directory-template",
                    "download.mode-templates.user", detail)).doesNotContain("private-directory");
            var logged = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(logger).error(logged.capture(), org.mockito.ArgumentMatchers.same(wrapped));
            assertThat(logged.getValue()).isEqualTo(MessageBundles.getForLog("gui.launcher.log.startup.failed.diagnostic",
                    MessageBundles.getForLog("gui.launcher.diagnostic.directory-template", "download.mode-templates.user",
                            MessageBundles.getForLog("directory-template.unknown-variable", "{unknown}"))));
            assertThat(logged.getValue()).doesNotContain(detail, "private-directory");
        } finally {
            Locale.setDefault(originalLocale);
            ReflectionTestUtils.setField(GuiLauncher.class, "log", originalLog);
        }
    }


    @ParameterizedTest
    @CsvSource({
            "ADDRESS ALREADY IN USE, port-in-use.without-port",
            "FAILED TO BIND, port-in-use.without-port",
            "KEYSTORE PASSWORD WAS INCORRECT, ssl-password-invalid",
            "DERINPUTSTREAM, ssl-invalid-format",
            "PERMISSION DENIED, permission-denied"
    })
    @DisplayName("异常关键词匹配不受土耳其语大小写规则影响")
    void diagnosticMatchingIsIndependentOfDefaultLocale(String detail, String key) {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            String expected = key.startsWith("port-")
                    ? MessageBundles.get("gui.launcher.diagnostic." + key,
                            MessageBundles.get("gui.launcher.diagnostic.port.placeholder"), detail)
                    : MessageBundles.get("gui.launcher.diagnostic." + key, detail);
            String actual = ReflectionTestUtils.invokeMethod(GuiLauncher.class,
                    "diagnoseStartupError", new IllegalStateException(detail));
            assertThat(actual).isEqualTo(expected);
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    @DisplayName("沿包装异常识别占用端口并保留原始消息")
    void diagnosesNestedBindFailure() {
        String detail = "Port 7345 was already in use";
        String actual = ReflectionTestUtils.invokeMethod(GuiLauncher.class,
                "diagnoseStartupError", new RuntimeException(new BindException(detail)));
        assertThat(actual).isEqualTo(MessageBundles.get(
                "gui.launcher.diagnostic.port-in-use.with-port", "7345", "7345", detail));
    }

    @Test
    @DisplayName("循环异常链只访问每个异常一次并保留通用错误回退")
    void cyclicCausesTerminateWithoutInventingDiagnosis() {
        var first = new GuardedFailure();
        var second = new GuardedFailure();
        first.initCause(second);
        second.initCause(first);
        String actual = ReflectionTestUtils.invokeMethod(GuiLauncher.class, "diagnoseStartupError", first);
        assertThat(actual).isNull();
        assertThat(first.reads).isEqualTo(1);
        assertThat(second.reads).isEqualTo(1);
    }

    @Test
    @DisplayName("提供者偏好读取失败时返回缺省选择并输出本地化诊断")
    void failedPreferenceReadUsesLocalizedLog(@TempDir Path directory) throws Exception {
        Path config = Files.createFile(directory.resolve("config.yaml"));
        IOException failure = new IOException("fixture read failure");
        Object originalLog = ReflectionTestUtils.getField(GuiLauncher.class, "log");
        Logger logger = mock(Logger.class);
        try (var editors = mockConstruction(ConfigFileEditor.class,
                (editor, context) -> when(editor.read("app.gui-provider")).thenThrow(failure))) {
            ReflectionTestUtils.setField(GuiLauncher.class, "log", logger);
            String actual = ReflectionTestUtils.invokeMethod(GuiLauncher.class,
                    "readConfigScalar", config, "app.gui-provider");
            assertThat(actual).isEmpty();
            verify(logger).warn(MessageBundles.getForLog(
                    "gui.launcher.log.config.read-failed", config + ": " + failure));
        } finally {
            ReflectionTestUtils.setField(GuiLauncher.class, "log", originalLog);
        }
    }

    private static final class GuardedFailure extends RuntimeException {
        private int reads;

        @Override
        public synchronized Throwable getCause() {
            // 若循环终止回归失效，让测试立即失败，避免留下永不退出的测试线程。
            assertThat(++reads).isEqualTo(1);
            return super.getCause();
        }
    }
}
