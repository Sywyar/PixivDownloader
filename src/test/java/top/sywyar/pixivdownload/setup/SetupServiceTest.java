package top.sywyar.pixivdownload.setup;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.download.config.DownloadConfig;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

@DisplayName("SetupService 单元测试")
class SetupServiceTest {

    @TempDir
    Path tempDir;

    private SetupService setupService;

    @BeforeEach
    void setUp() {
        setupService = createSetupService();
    }

    private SetupService createSetupService() {
        DownloadConfig config = new DownloadConfig();
        config.setRootFolder(tempDir.toString());
        return new SetupService(config, new ObjectMapper());
    }

    // ========== 初始状态 ==========

    @Test
    @DisplayName("初始状态应为未完成配置")
    void shouldBeNotSetupInitially() {
        assertThat(setupService.isSetupComplete()).isFalse();
        assertThat(setupService.getMode()).isNull();
    }

    // ========== init ==========

    @Nested
    @DisplayName("init - 初始化配置")
    class InitTests {

        @Test
        @DisplayName("初始化后状态应为已完成")
        void shouldCompleteSetup() throws IOException {
            setupService.init("admin", "password123", "solo");

            assertThat(setupService.isSetupComplete()).isTrue();
            assertThat(setupService.getMode()).isEqualTo("solo");
        }

        @Test
        @DisplayName("初始化后配置应持久化并可重新加载")
        void shouldPersistConfig() throws IOException {
            setupService.init("admin", "password123", "multi");

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
            setupService.init("admin", "password123", "solo");
        }

        @Test
        @DisplayName("正确的用户名密码应通过验证")
        void shouldAcceptCorrectCredentials() {
            assertThat(setupService.checkLogin("admin", "password123")).isTrue();
        }

        @Test
        @DisplayName("错误的密码应拒绝")
        void shouldRejectWrongPassword() {
            assertThat(setupService.checkLogin("admin", "wrongpassword")).isFalse();
        }

        @Test
        @DisplayName("错误的用户名应拒绝")
        void shouldRejectWrongUsername() {
            assertThat(setupService.checkLogin("wronguser", "password123")).isFalse();
        }

        @Test
        @DisplayName("null 用户名应拒绝")
        void shouldRejectNullUsername() {
            assertThat(setupService.checkLogin(null, "password123")).isFalse();
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
            setupService.init("admin", "password123", "solo");
        }

        @Test
        @DisplayName("创建的短期 session 应有效")
        void shouldCreateValidShortSession() {
            String token = setupService.createSession(false);

            assertThat(token).isNotNull().isNotBlank();
            assertThat(setupService.isValidSession(token)).isTrue();
        }

        @Test
        @DisplayName("创建的长期 session 应有效")
        void shouldCreateValidLongSession() {
            String token = setupService.createSession(true);

            assertThat(token).isNotNull().isNotBlank();
            assertThat(setupService.isValidSession(token)).isTrue();
        }

        @Test
        @DisplayName("长期 session 应在重启后保留")
        void shouldPersistLongSession() {
            String token = setupService.createSession(true);

            SetupService reloaded = createSetupService();
            assertThat(reloaded.isValidSession(token)).isTrue();
        }

        @Test
        @DisplayName("短期 session 不应在重启后保留")
        void shouldNotPersistShortSession() {
            String token = setupService.createSession(false);

            SetupService reloaded = createSetupService();
            assertThat(reloaded.isValidSession(token)).isFalse();
        }

        @Test
        @DisplayName("移除 session 后应失效")
        void shouldInvalidateRemovedSession() {
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
        void shouldManageMultipleSessions() {
            String token1 = setupService.createSession(false);
            String token2 = setupService.createSession(false);

            assertThat(setupService.isValidSession(token1)).isTrue();
            assertThat(setupService.isValidSession(token2)).isTrue();

            setupService.removeSession(token1);
            assertThat(setupService.isValidSession(token1)).isFalse();
            assertThat(setupService.isValidSession(token2)).isTrue();
        }
    }
}
