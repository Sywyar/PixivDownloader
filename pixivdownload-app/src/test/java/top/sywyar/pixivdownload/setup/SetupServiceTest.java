package top.sywyar.pixivdownload.setup;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.ApplicationArguments;
import org.springframework.mock.web.MockHttpServletRequest;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("SetupService 单元测试")
class SetupServiceTest {

    @TempDir
    Path tempDir;

    private SetupService setupService;
    private Path configDir;
    private Path stateDir;

    @BeforeEach
    void setUp() {
        configDir = tempDir.resolve("config");
        stateDir = tempDir.resolve("state");
        System.setProperty(RuntimeFiles.CONFIG_DIR_PROPERTY, configDir.toString());
        System.setProperty(RuntimeFiles.STATE_DIR_PROPERTY, stateDir.toString());
        setupService = createSetupService();
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(RuntimeFiles.CONFIG_DIR_PROPERTY);
        System.clearProperty(RuntimeFiles.STATE_DIR_PROPERTY);
    }

    private SetupService createSetupService() {
        return createSetupServiceWithArgs();
    }

    private SetupService createSetupServiceWithArgs(String... args) {
        DownloadConfig config = new DownloadConfig();
        config.setRootFolder(tempDir.resolve("pixiv-download").toString());
        ApplicationArguments arguments = mock(ApplicationArguments.class);
        when(arguments.getSourceArgs()).thenReturn(args);
        return new SetupService(config, new ObjectMapper(), arguments, TestI18nBeans.appMessages());
    }

    // ========== 初始状态 ==========

    @Test
    @DisplayName("初始状态应为未完成配置")
    void shouldBeNotSetupInitially() {
        assertThat(setupService.isSetupComplete()).isFalse();
        assertThat(setupService.getMode()).isNull();
    }

    // ========== introMode ==========

    @Nested
    @DisplayName("introMode - --intro 启动参数")
    class IntroModeTests {

        @Test
        @DisplayName("不含 --intro 参数时 introMode 应为 false")
        void shouldBeNotIntroModeWithoutArg() {
            SetupService service = createSetupServiceWithArgs();
            assertThat(service.isIntroMode()).isFalse();
        }

        @Test
        @DisplayName("含 --intro 参数时 introMode 应为 true")
        void shouldBeIntroModeWithArg() {
            SetupService service = createSetupServiceWithArgs("--intro");
            assertThat(service.isIntroMode()).isTrue();
        }

        @Test
        @DisplayName("--intro 与其他参数共存时 introMode 仍为 true")
        void shouldBeIntroModeWithMixedArgs() {
            SetupService service = createSetupServiceWithArgs("--server.port=8080", "--intro");
            assertThat(service.isIntroMode()).isTrue();
        }

        @Test
        @DisplayName("仅含 --no-gui 时 introMode 应为 false")
        void shouldNotBeIntroModeWithNoGuiArg() {
            SetupService service = createSetupServiceWithArgs("--no-gui");
            assertThat(service.isIntroMode()).isFalse();
        }
    }

    // ========== init ==========

    @Nested
    @DisplayName("init - 初始化配置")
    class InitTests {

        @Test
        @DisplayName("初始化后状态应为已完成")
        void shouldCompleteSetup() throws IOException {
            setupService.init("admin", "password1234", "solo");

            assertThat(setupService.isSetupComplete()).isTrue();
            assertThat(setupService.getMode()).isEqualTo("solo");
        }

        @Test
        @DisplayName("初始化后配置应持久化并可重新加载")
        void shouldPersistConfig() throws IOException {
            setupService.init("admin", "password1234", "multi");
            assertThat(stateDir.resolve(RuntimeFiles.SETUP_CONFIG_JSON)).exists();

            // 创建新的 SetupService 实例，模拟重启
            SetupService reloaded = createSetupService();

            assertThat(reloaded.isSetupComplete()).isTrue();
            assertThat(reloaded.getMode()).isEqualTo("multi");
        }
    }

    // ========== checkLogin ==========

    @Nested
    @DisplayName("checkLogin - 登录验证")
    class CheckLoginTests {

        @BeforeEach
        void initSetup() throws IOException {
            setupService.init("admin", "password1234", "solo");
        }

        @Test
        @DisplayName("正确的用户名密码应通过验证")
        void shouldAcceptCorrectCredentials() {
            assertThat(setupService.checkLogin("admin", "password1234")).isTrue();
        }

        @Test
        @DisplayName("错误的密码应拒绝")
        void shouldRejectWrongPassword() {
            assertThat(setupService.checkLogin("admin", "wrongpassword")).isFalse();
        }

        @Test
        @DisplayName("错误的用户名应拒绝")
        void shouldRejectWrongUsername() {
            assertThat(setupService.checkLogin("wronguser", "password1234")).isFalse();
        }

        @Test
        @DisplayName("null 用户名应拒绝")
        void shouldRejectNullUsername() {
            assertThat(setupService.checkLogin(null, "password1234")).isFalse();
        }

        @Test
        @DisplayName("null 密码应拒绝（BCrypt 抛出异常也视为拒绝）")
        void shouldRejectNullPassword() {
            // BCrypt.matches throws IllegalArgumentException for null password
            // This is acceptable behavior - null passwords are always rejected
            try {
                boolean result = setupService.checkLogin("admin", null);
                assertThat(result).isFalse();
            } catch (IllegalArgumentException e) {
                // Expected: BCrypt rejects null rawPassword
                assertThat(e.getMessage()).contains("rawPassword");
            }
        }
    }

    // ========== Session 管理 ==========

    @Nested
    @DisplayName("Session 管理")
    class SessionTests {

        @BeforeEach
        void initSetup() throws IOException {
            setupService.init("admin", "password1234", "solo");
        }

        @Test
        @DisplayName("创建的短期 session 应有效")
        void shouldCreateValidShortSession() throws IOException {
            String token = setupService.createSession(false);

            assertThat(token).isNotNull().isNotBlank();
            assertThat(setupService.isValidSession(token)).isTrue();
        }

        @Test
        @DisplayName("创建的长期 session 应有效")
        void shouldCreateValidLongSession() throws IOException {
            String token = setupService.createSession(true);

            assertThat(token).isNotNull().isNotBlank();
            assertThat(setupService.isValidSession(token)).isTrue();
        }

        @Test
        @DisplayName("长期 session 应只以摘要持久化并在重启后保留")
        void shouldPersistLongSessionDigest() throws IOException {
            String token = setupService.createSession(true);
            Path configPath = stateDir.resolve(RuntimeFiles.SETUP_CONFIG_JSON);
            String persisted = Files.readString(configPath, StandardCharsets.UTF_8);

            SetupService reloaded = createSetupService();
            assertThat(persisted).doesNotContain(token);
            assertThat(new ObjectMapper().readValue(configPath.toFile(), SetupConfig.class)
                    .getSessions().keySet()).allMatch(key -> key.matches("[0-9a-f]{64}"));
            assertThat(reloaded.isValidSession(token)).isTrue();
        }

        @Test
        @DisplayName("旧版 session 原文应迁移为摘要且不进入备份")
        void shouldMigrateRawPersistentSessionWithoutBackup() throws IOException {
            String token = "legacy-session-token";
            Path configPath = stateDir.resolve(RuntimeFiles.SETUP_CONFIG_JSON);
            ObjectMapper mapper = new ObjectMapper();
            SetupConfig config = mapper.readValue(configPath.toFile(), SetupConfig.class);
            config.setSessions(Map.of(token, System.currentTimeMillis() + 60_000));
            mapper.writeValue(configPath.toFile(), config);

            SetupService reloaded = createSetupService();
            String persisted = Files.readString(configPath, StandardCharsets.UTF_8);

            assertThat(reloaded.isValidSession(token)).isTrue();
            assertThat(persisted).doesNotContain(token);
            assertThat(SetupConfigFile.backupPath(configPath)).doesNotExist();
        }

        @Test
        @DisplayName("短期 session 不应在重启后保留")
        void shouldNotPersistShortSession() throws IOException {
            String token = setupService.createSession(false);

            SetupService reloaded = createSetupService();
            assertThat(reloaded.isValidSession(token)).isFalse();
        }

        @Test
        @DisplayName("移除 session 后应失效")
        void shouldInvalidateRemovedSession() throws IOException {
            String token = setupService.createSession(false);
            assertThat(setupService.isValidSession(token)).isTrue();

            setupService.removeSession(token);
            assertThat(setupService.isValidSession(token)).isFalse();
        }

        @Test
        @DisplayName("null/空 token 应返回无效")
        void shouldReturnInvalidForNullOrBlankToken() {
            assertThat(setupService.isValidSession(null)).isFalse();
            assertThat(setupService.isValidSession("")).isFalse();
            assertThat(setupService.isValidSession("   ")).isFalse();
        }

        @Test
        @DisplayName("不存在的 token 应返回无效")
        void shouldReturnInvalidForNonExistentToken() {
            assertThat(setupService.isValidSession("non-existent-token")).isFalse();
        }

        @Test
        @DisplayName("removeSession(null) 应无异常")
        void shouldHandleRemoveNullSession() {
            assertThatCode(() -> setupService.removeSession(null))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("多个 session 应独立管理")
        void shouldManageMultipleSessions() throws IOException {
            String token1 = setupService.createSession(false);
            String token2 = setupService.createSession(false);

            assertThat(setupService.isValidSession(token1)).isTrue();
            assertThat(setupService.isValidSession(token2)).isTrue();

            setupService.removeSession(token1);
            assertThat(setupService.isValidSession(token1)).isFalse();
            assertThat(setupService.isValidSession(token2)).isTrue();
        }

        @Test
        @DisplayName("长期 session 持久化失败时不发布到内存")
        void shouldRollbackRememberedSessionWhenSaveFails() throws IOException {
            Path backup = blockStateDirectory();
            try {
                assertThatThrownBy(() -> setupService.createSession(true))
                        .isInstanceOf(IOException.class);
            } finally {
                restoreStateDirectory(backup);
            }

            setupService.updateDisplayName("after-failure");
            SetupConfig persisted = new ObjectMapper().readValue(
                    stateDir.resolve(RuntimeFiles.SETUP_CONFIG_JSON).toFile(), SetupConfig.class);
            assertThat(persisted.getSessions()).isEmpty();
        }

        @Test
        @DisplayName("长期 session 注销持久化失败时保持原登录态")
        void shouldRollbackLogoutWhenSaveFails() throws IOException {
            String token = setupService.createSession(true);
            Path backup = blockStateDirectory();
            try {
                assertThatThrownBy(() -> setupService.removeSession(token))
                        .isInstanceOf(IOException.class);
                assertThat(setupService.isValidSession(token)).isTrue();
            } finally {
                restoreStateDirectory(backup);
            }
        }

        @Test
        @DisplayName("长期 session 超过上限时淘汰最早到期项")
        void shouldCapPersistentSessions() throws IOException {
            String first = null;
            String latest = null;
            for (int i = 0; i <= SetupService.MAX_PERSISTENT_SESSIONS; i++) {
                latest = setupService.createSession(true);
                if (i == 0) {
                    first = latest;
                }
            }

            assertThat(setupService.isValidSession(first)).isFalse();
            assertThat(setupService.isValidSession(latest)).isTrue();
            SetupConfig persisted = new ObjectMapper().readValue(
                    stateDir.resolve(RuntimeFiles.SETUP_CONFIG_JSON).toFile(), SetupConfig.class);
            assertThat(persisted.getSessions()).hasSize(SetupService.MAX_PERSISTENT_SESSIONS);
        }
    }

    @Test
    @DisplayName("密码和称呼持久化失败时回滚内存状态")
    void shouldRollbackAccountMutationsWhenSaveFails() throws IOException {
        setupService.init("admin", "password1234", "solo");
        setupService.updateDisplayName("Before");
        String token = setupService.createSession(true);
        Path backup = blockStateDirectory();
        try {
            assertThatThrownBy(() -> setupService.changePassword("password1234", "new-password-1234"))
                    .isInstanceOf(IOException.class);
            assertThat(setupService.checkLogin("admin", "password1234")).isTrue();
            assertThat(setupService.checkLogin("admin", "new-password-1234")).isFalse();
            assertThat(setupService.isValidSession(token)).isTrue();

            assertThatThrownBy(() -> setupService.updateDisplayName("After"))
                    .isInstanceOf(IOException.class);
            assertThat(setupService.getDisplayName()).isEqualTo("Before");
        } finally {
            restoreStateDirectory(backup);
        }
    }

    @Test
    @DisplayName("损坏的安装状态应保持原文件并阻止重新初始化")
    void shouldFailClosedOnCorruptedConfig() throws IOException {
        Path configPath = stateDir.resolve(RuntimeFiles.SETUP_CONFIG_JSON);
        Files.createDirectories(configPath.getParent());
        Files.writeString(configPath, "{broken", StandardCharsets.UTF_8);

        SetupService corrupted = createSetupService();

        assertThat(corrupted.isConfigurationCorrupted()).isTrue();
        assertThatThrownBy(() -> corrupted.init("admin", "password1234", "solo"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(Files.readString(configPath, StandardCharsets.UTF_8)).isEqualTo("{broken");
    }

    @Test
    @DisplayName("后续写入应生成可解析的安装状态备份")
    void shouldCreateParseableBackupBeforeReplacement() throws IOException {
        setupService.init("admin", "password1234", "solo");
        Path configPath = stateDir.resolve(RuntimeFiles.SETUP_CONFIG_JSON);

        setupService.updateDisplayName("Alice");

        ObjectMapper mapper = new ObjectMapper();
        SetupConfig backup = mapper.readValue(SetupConfigFile.backupPath(configPath).toFile(), SetupConfig.class);
        SetupConfig current = mapper.readValue(configPath.toFile(), SetupConfig.class);
        assertThat(backup.getDisplayName()).isNull();
        assertThat(current.getDisplayName()).isEqualTo("Alice");
    }

    @Nested
    @DisplayName("isAdminLoggedIn - 请求登录态判断")
    class IsAdminLoggedInTests {

        @BeforeEach
        void initSetup() throws IOException {
            setupService.init("admin", "password1234", "multi");
        }

        @Test
        @DisplayName("有效 session cookie 应识别为已登录")
        void shouldRecognizeValidSessionCookie() throws IOException {
            String token = setupService.createSession(false);
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setCookies(new Cookie("pixiv_session", token));

            assertThat(setupService.isAdminLoggedIn(request)).isTrue();
        }

        @Test
        @DisplayName("无效 session 应返回未登录")
        void shouldRejectInvalidSession() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setCookies(new Cookie("pixiv_session", "invalid-token"));

            assertThat(setupService.isAdminLoggedIn(request)).isFalse();
        }
    }

    private Path blockStateDirectory() throws IOException {
        Path backup = tempDir.resolve("state-backup");
        Files.move(stateDir, backup);
        Files.writeString(stateDir, "blocked", StandardCharsets.UTF_8);
        return backup;
    }

    private void restoreStateDirectory(Path backup) throws IOException {
        Files.deleteIfExists(stateDir);
        Files.move(backup, stateDir);
    }
}
