package top.sywyar.pixivdownload.plugin.management;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures;
import top.sywyar.pixivdownload.gui.config.ConfigFileEditor;
import top.sywyar.pixivdownload.i18n.AppLocaleResolver;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopDatabaseSettings;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.PluginBootstrapSession;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("市场数据库设置复用配置与草稿校验")
class DatabaseSettingsControllerTest {
    @TempDir Path directory;
    private String previousData;
    private ConfigFileEditor editor;
    private DatabaseSettingsController controller;
    private PluginStatusService plugins;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach void prepare() throws Exception {
        previousData = System.getProperty(RuntimeFiles.DATA_DIR_PROPERTY);
        System.setProperty(RuntimeFiles.DATA_DIR_PROPERTY, directory.resolve("data").toString());
        editor = new ConfigFileEditor(directory.resolve("config.yaml"));
        editor.writeAll(Map.of("database.provider", "fixture-server", "database.engine", "postgresql",
                "database.jdbc-url", "jdbc:postgresql://127.0.0.1:1/fixture", "database.username", "fixture",
                "database.password", "retained secret"));
        controller = controller(editor);
    }

    @AfterEach void restore() {
        if (previousData == null) System.clearProperty(RuntimeFiles.DATA_DIR_PROPERTY);
        else System.setProperty(RuntimeFiles.DATA_DIR_PROPERTY, previousData);
    }

    private DatabaseSettingsController controller(ConfigFileEditor file) {
        return controller(file.asConfigFile());
    }

    private DatabaseSettingsController controller(top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost.ConfigFile file) {
        var session = mock(PluginBootstrapSession.class);
        when(session.databaseProviders()).thenReturn(DatabaseProviderTestFixtures.providers());
        plugins = mock(PluginStatusService.class);
        when(plugins.recoveryGateSnapshot()).thenReturn(top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginRecoveryGateSnapshot.safe(
                top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginTransactionRecoveryReport.success()));
        when(plugins.report()).thenReturn(top.sywyar.pixivdownload.plugin.runtime.status.PluginStatusReport.empty());
        return new DatabaseSettingsController(file, session,
                DatabaseProviderTestFixtures.sqlite(directory.resolve("active.db")),
                new String[]{"--spring.config.location=" + directory.resolve("config.yaml").toUri()},
                new AppMessages(new org.springframework.context.support.StaticMessageSource()), mock(AppLocaleResolver.class), plugins);
    }

    @Test
    @DisplayName("桌面抢先保存 SQLite 后，市场部分草稿按最新配置校验，不混入旧 PostgreSQL 目标")
    void validatesPartialDraftInsideSharedSaveLock() throws Exception {
        var file = spy(editor.asConfigFile());
        doAnswer(invocation -> {
            // 在市场取得保存锁前，另一个配置入口完成目标切换。
            editor.writeAll(Map.of("database.provider", "fixture-file", "database.engine", "sqlite",
                    "database.jdbc-url", "", "database.username", "", "database.password", ""));
            return invocation.callRealMethod();
        }).when(file).withWriteLock(any());
        var response = controller(file).save(request(Map.of("jdbcUrl", "jdbc:postgresql://127.0.0.1:1/next")));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(editor.read("database.provider")).isEqualTo("fixture-file");
        assertThat(editor.read("database.engine")).isEqualTo("sqlite");
        assertThat(editor.read("database.jdbc-url")).isEmpty();
        assertThat(editor.read("database.username")).isEmpty();
        assertThat(editor.read("database.password")).isEmpty();
        assertThat(directory.resolve("data/pixiv_download.db")).doesNotExist();
    }

    @Test
    @DisplayName("密码保留替换和清除均不回显，保存不改变活动目标")
    void preservesAndClearsPasswordWithoutEcho() throws Exception {
        var saved = controller.save(request(Map.of("username", "changed")));
        assertThat(saved.getStatusCode().value()).isEqualTo(200);
        assertThat(editor.read("database.password")).isEqualTo("retained secret");
        var view = (DatabaseSettingsController.View) controller.read(request(Map.of())).getBody();
        assertThat(view.settings().passwordStored()).isTrue();
        assertThat(view.state().activeTarget()).contains("active.db");
        assertThat(view.state().nextTarget()).contains("postgresql:");
        assertThat(view.state().restartRequired()).isTrue();
        assertThat(json.writeValueAsString(view)).doesNotContain("retained secret");
        assertThat(controller.save(request(Map.of("passwordAction", "REPLACE", "password", " new secret ")))
                .getStatusCode().value()).isEqualTo(200);
        assertThat(editor.read("database.password")).isEqualTo(" new secret ");
        assertThat(controller.save(request(Map.of("passwordAction", "CLEAR"))).getStatusCode().value()).isEqualTo(200);
        assertThat(editor.read("database.password")).isEmpty();
        assertThat(directory.resolve("active.db")).doesNotExist();
    }

    @Test
    @DisplayName("草稿检查不建空 SQLite 或保存文件，错误 PostgreSQL 仅返回安全结果")
    void draftChecksDoNotPersist() throws Exception {
        byte[] before = Files.readAllBytes(directory.resolve("config.yaml"));
        Path missing = directory.resolve("missing/source.db");
        var checked = controller.check(request(Map.of("provider", "fixture-file", "jdbcUrl", "jdbc:sqlite:" + missing)));
        assertThat(((DesktopDatabaseSettings.CheckResult) checked.getBody()).outcome())
                .isEqualTo(DesktopDatabaseSettings.Outcome.NOT_CREATED);
        assertThat(missing.getParent()).doesNotExist();
        var failed = controller.check(request(Map.of("provider", "fixture-server")));
        assertThat(((DesktopDatabaseSettings.CheckResult) failed.getBody()).outcome())
                .isEqualTo(DesktopDatabaseSettings.Outcome.FAILED);
        assertThat(json.writeValueAsString(failed.getBody())).doesNotContain("retained secret");
        assertThat(Files.readAllBytes(directory.resolve("config.yaml"))).isEqualTo(before);
    }

    @Test
    @DisplayName("非法输入和未知插件拒绝保存，未指定替换时掩码不能覆盖密码")
    void rejectsInvalidDraftsWithoutConfigChanges() throws Exception {
        byte[] before = Files.readAllBytes(directory.resolve("config.yaml"));
        for (Map<String, String> value : java.util.List.of(
                Map.of("provider", "missing"), Map.of("password", "********"),
                Map.of("passwordAction", "REPLACE", "password", ""), Map.of("unrelated", "value"),
                Map.of("jdbcUrl", "jdbc:postgresql://127.0.0.1/db?password=secret"))) {
            assertThat(controller.save(request(value)).getStatusCode().value()).isEqualTo(400);
        }
        for (String body : java.util.List.of("null", "{} {}", "{\"provider\":\"one\",\"provider\":\"two\"}")) {
            var request = request(Map.of());
            request.setContent(body.getBytes(StandardCharsets.UTF_8));
            assertThat(controller.save(request).getStatusCode().value()).isEqualTo(400);
        }
        var oversized = request(Map.of());
        oversized.setContent(new byte[DatabaseSettingsController.MAX_DRAFT_BYTES + 1]);
        assertThat(controller.save(oversized).getStatusCode().value()).isEqualTo(413);
        assertThat(Files.readAllBytes(directory.resolve("config.yaml"))).isEqualTo(before);
    }

    @Test
    @DisplayName("未完成私有迁移时拒绝切换并保留原数据与选择")
    void refusesUnverifiedPrivateStorage() throws Exception {
        Path old = Files.createDirectories(directory.resolve("data/fixture-owner")).resolve("plugin.db");
        Files.writeString(old, "retained");
        byte[] before = Files.readAllBytes(directory.resolve("config.yaml"));
        assertThat(controller.save(request(Map.of("provider", "fixture-server"))).getStatusCode().value()).isEqualTo(400);
        assertThat(Files.readAllBytes(directory.resolve("config.yaml"))).isEqualTo(before);
        assertThat(old).hasContent("retained");
    }

    @Test
    @DisplayName("非法配置中夹带的 URL 密码不会从读取接口泄露")
    void redactsInvalidConnectionString() throws Exception {
        editor.write("database.jdbc-url", "jdbc:postgresql://user:never-echo@server/db");
        var response = controller.read(request(Map.of()));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(json.writeValueAsString(response.getBody())).doesNotContain("never-echo", "retained secret");
        assertThat(((DatabaseSettingsController.View) response.getBody()).settings().connectionRedacted()).isTrue();
    }

    @Test
    @DisplayName("原子保存失败返回失败且原配置字节不变")
    void reportsWriteFailureWithoutFalseSuccess() throws Exception {
        byte[] before = Files.readAllBytes(directory.resolve("config.yaml"));
        var failing = new ConfigFileEditor(directory.resolve("config.yaml")) {
            @Override public void writeAll(Map<String, String> values) throws java.io.IOException {
                throw new java.io.IOException("fixture secret must not be echoed");
            }
        };
        var response = controller(failing).save(request(Map.of("username", "changed")));
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(json.writeValueAsString(response.getBody())).doesNotContain("fixture secret");
        assertThat(Files.readAllBytes(directory.resolve("config.yaml"))).isEqualTo(before);
    }

    @Test
    @DisplayName("修复启用入口只绑定进程选中且确实禁用的插件，不误启用下次目标")
    void enableActionUsesSelectedOwner() throws Exception {
        var descriptor = mock(top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor.class);
        for (var status : top.sywyar.pixivdownload.plugin.runtime.status.PluginStatus.values()) {
            when(plugins.report()).thenReturn(new top.sywyar.pixivdownload.plugin.runtime.status.PluginStatusReport(java.util.List.of(
                    new top.sywyar.pixivdownload.plugin.runtime.status.PluginDiagnostic("fixture-file", status, descriptor, false, java.util.List.of()),
                    new top.sywyar.pixivdownload.plugin.runtime.status.PluginDiagnostic("fixture-server",
                            top.sywyar.pixivdownload.plugin.runtime.status.PluginStatus.DISABLED, descriptor, false, java.util.List.of()))));
            var view = (DatabaseSettingsController.View) controller.read(request(Map.of())).getBody();
            assertThat(view.selectedProvider().owner()).isEqualTo("fixture-file");
            assertThat(view.selectedProvider().enableAllowed()).isEqualTo(status == top.sywyar.pixivdownload.plugin.runtime.status.PluginStatus.DISABLED);
        }
    }

    @Test
    @DisplayName("迁移确认仅接受服务端计划令牌，非法或过期输入不写配置")
    void migrationConfirmationRejectsUntrustedPlans() throws Exception {
        byte[] before = Files.readAllBytes(directory.resolve("config.yaml"));
        for (Map<String, String> body : java.util.List.of(
                Map.of("token", "invalid"),
                Map.of("token", java.util.UUID.randomUUID().toString()),
                Map.of("token", java.util.UUID.randomUUID().toString(), "sourceConfig", "arbitrary.yaml"))) {
            var response = controller.migrate(request(body));
            assertThat(response.getStatusCode().value()).isEqualTo(409);
            assertThat(json.writeValueAsString(response.getBody())).doesNotContain("retained secret", "arbitrary.yaml");
        }
        var oversized = request(Map.of());
        oversized.setContent(new byte[DatabaseSettingsController.MAX_DRAFT_BYTES + 1]);
        assertThat(controller.migrate(oversized).getStatusCode().value()).isEqualTo(413);
        assertThat(Files.readAllBytes(directory.resolve("config.yaml"))).isEqualTo(before);
        assertThat(directory.resolve("data/database-migrations")).doesNotExist();
    }

    @Test
    @DisplayName("迁移预检沿用严格草稿解析并保留原配置")
    void migrationPreviewRejectsInvalidDrafts() throws Exception {
        byte[] before = Files.readAllBytes(directory.resolve("config.yaml"));
        assertThat(controller.preview(request(Map.of("unrelated", "value"))).getStatusCode().value()).isEqualTo(400);
        var oversized = request(Map.of());
        oversized.setContent(new byte[DatabaseSettingsController.MAX_DRAFT_BYTES + 1]);
        assertThat(controller.preview(oversized).getStatusCode().value()).isEqualTo(413);
        assertThat(Files.readAllBytes(directory.resolve("config.yaml"))).isEqualTo(before);
    }

    private MockHttpServletRequest request(Map<String, String> values) throws Exception {
        var request = new MockHttpServletRequest();
        request.setContentType("application/json");
        request.setContent(json.writeValueAsBytes(values));
        return request;
    }
}
