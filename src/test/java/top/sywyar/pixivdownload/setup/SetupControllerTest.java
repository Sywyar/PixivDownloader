package top.sywyar.pixivdownload.setup;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.sywyar.pixivdownload.GlobalExceptionHandler;

import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SetupController 单元测试")
class SetupControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private SetupService setupService;

    @BeforeEach
    void setUp() {
        SetupController controller = new SetupController(setupService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // ========== GET /api/setup/status ==========

    @Nested
    @DisplayName("GET /api/setup/status")
    class SetupStatusTests {

        @Test
        @DisplayName("未完成配置时应返回 setupComplete=false")
        void shouldReturnNotComplete() throws Exception {
            when(setupService.isSetupComplete()).thenReturn(false);
            when(setupService.getMode()).thenReturn(null);

            mockMvc.perform(get("/api/setup/status"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.setupComplete").value(false))
                    .andExpect(jsonPath("$.mode").value(""));
        }

        @Test
        @DisplayName("已完成配置时应返回正确状态")
        void shouldReturnComplete() throws Exception {
            when(setupService.isSetupComplete()).thenReturn(true);
            when(setupService.getMode()).thenReturn("solo");

            mockMvc.perform(get("/api/setup/status"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.setupComplete").value(true))
                    .andExpect(jsonPath("$.mode").value("solo"));
        }
    }

    // ========== POST /api/setup/init ==========

    @Nested
    @DisplayName("POST /api/setup/init")
    class SetupInitTests {

        @Test
        @DisplayName("正确参数应初始化成功")
        void shouldInitSuccessfully() throws Exception {
            when(setupService.isSetupComplete()).thenReturn(false);

            mockMvc.perform(post("/api/setup/init")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "username", "admin",
                                    "password", "password123",
                                    "mode", "solo"
                            ))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(true))
                    .andExpect(jsonPath("$.mode").value("solo"));

            verify(setupService).init("admin", "password123", "solo");
        }

        @Test
        @DisplayName("已完成配置时应返回 403")
        void shouldReturn403WhenAlreadySetup() throws Exception {
            when(setupService.isSetupComplete()).thenReturn(true);

            mockMvc.perform(post("/api/setup/init")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "username", "admin",
                                    "password", "password123",
                                    "mode", "solo"
                            ))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("已完成配置，不可重复初始化"));
        }

        @Test
        @DisplayName("空用户名应返回 400")
        void shouldReturn400ForBlankUsername() throws Exception {
            mockMvc.perform(post("/api/setup/init")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "username", "",
                                    "password", "password123",
                                    "mode", "solo"
                            ))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value(containsString("用户名不能为空")));
        }

        @Test
        @DisplayName("密码长度不足应返回 400")
        void shouldReturn400ForShortPassword() throws Exception {
            mockMvc.perform(post("/api/setup/init")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "username", "admin",
                                    "password", "12345",
                                    "mode", "solo"
                            ))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value(containsString("密码长度至少 6 位")));
        }

        @Test
        @DisplayName("无效的模式应返回 400")
        void shouldReturn400ForInvalidMode() throws Exception {
            mockMvc.perform(post("/api/setup/init")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "username", "admin",
                                    "password", "password123",
                                    "mode", "invalid"
                            ))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value(containsString("无效的使用模式")));
        }
    }

    // ========== POST /api/auth/login ==========

    @Nested
    @DisplayName("POST /api/auth/login")
    class LoginTests {

        @Test
        @DisplayName("正确凭据应登录成功并设置 Cookie")
        void shouldLoginSuccessfully() throws Exception {
            when(setupService.checkLogin("admin", "password123")).thenReturn(true);
            when(setupService.createSession(false)).thenReturn("test-token");

            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "username", "admin",
                                    "password", "password123"
                            ))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(true))
                    .andExpect(header().exists("Set-Cookie"));
        }

        @Test
        @DisplayName("错误凭据应返回 401")
        void shouldReturn401ForWrongCredentials() throws Exception {
            when(setupService.checkLogin("admin", "wrong")).thenReturn(false);

            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "username", "admin",
                                    "password", "wrong"
                            ))))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("用户名或密码错误"));
        }

        @Test
        @DisplayName("勾选记住我应设置长期 Cookie")
        void shouldSetLongCookieForRememberMe() throws Exception {
            when(setupService.checkLogin("admin", "password123")).thenReturn(true);
            when(setupService.createSession(true)).thenReturn("long-token");

            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "username", "admin",
                                    "password", "password123",
                                    "rememberMe", true
                            ))))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Set-Cookie", containsString("Max-Age=")));
        }
    }

    // ========== POST /api/auth/logout ==========

    @Test
    @DisplayName("POST /api/auth/logout 应清除 session 和 Cookie")
    void shouldLogoutAndClearCookie() throws Exception {
        mockMvc.perform(post("/api/auth/logout")
                        .cookie(new Cookie("pixiv_session", "test-token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));

        verify(setupService).removeSession("test-token");
    }

    // ========== GET /api/auth/check ==========

    @Nested
    @DisplayName("GET /api/auth/check")
    class AuthCheckTests {

        @Test
        @DisplayName("有效 session 应返回 valid=true")
        void shouldReturnAuthenticatedForValidSession() throws Exception {
            when(setupService.isValidSession("valid-token")).thenReturn(true);

            mockMvc.perform(get("/api/auth/check")
                            .cookie(new Cookie("pixiv_session", "valid-token")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.valid").value(true));
        }

        @Test
        @DisplayName("无效 session 应返回 valid=false")
        void shouldReturnUnauthenticatedForInvalidSession() throws Exception {
            when(setupService.isValidSession(any())).thenReturn(false);

            mockMvc.perform(get("/api/auth/check"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.valid").value(false));
        }
    }
}
