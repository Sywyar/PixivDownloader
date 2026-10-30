package top.sywyar.pixivdownload.setup;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.sywyar.pixivdownload.GlobalExceptionHandler;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.appconfig.MultiModeConfig;
import top.sywyar.pixivdownload.i18n.AppLocaleResolver;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.plugin.api.security.*;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.LoginVerificationCapabilityAdapter;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.*;
import top.sywyar.pixivdownload.plugin.registry.route.RouteAccessRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("真实登录入口在会话副作用之前执行精确代际验证")
class LoginVerificationGateTest {
    @TempDir Path temporary;
    final ObjectMapper json = new ObjectMapper();
    final ExternalCapabilityInvocationRegistry registry = new ExternalCapabilityInvocationRegistry();
    final List<AnnotationConfigApplicationContext> contexts = new ArrayList<>();
    SetupService setup;
    LoginRateLimitService limits;
    MockMvc mvc;
    Clock clock = Clock.systemUTC();

    @BeforeEach
    void initialize() throws Exception {
        System.setProperty(RuntimeFiles.CONFIG_DIR_PROPERTY, temporary.resolve("config").toString());
        System.setProperty(RuntimeFiles.STATE_DIR_PROPERTY, temporary.resolve("state").toString());
        var config = new DownloadConfig();
        config.setRootFolder(temporary.resolve("downloads").toString());
        setup = spy(new SetupService(config, json, new DefaultApplicationArguments(), TestI18nBeans.appMessages()));
        setup.init("admin", "password1234", "solo");
        limits = mock(LoginRateLimitService.class);
        when(limits.isAllowed(anyString())).thenReturn(true);
        configure("neutral");
    }

    @AfterEach
    void close() {
        contexts.forEach(AnnotationConfigApplicationContext::close);
        System.clearProperty(RuntimeFiles.CONFIG_DIR_PROPERTY);
        System.clearProperty(RuntimeFiles.STATE_DIR_PROPERTY);
    }

    private void configure(String policies) {
        var messages = TestI18nBeans.appMessages();
        var gate = new LoginVerificationGate(registry, messages, policies, clock);
        var controller = new SetupController(setup, limits, new MultiModeConfig(), mock(ProxySetupService.class), messages, gate);
        var locale = mock(AppLocaleResolver.class);
        when(locale.resolveLocale(any())).thenReturn(Locale.US);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(messages))
                .addFilters(new CsrfProtectionFilter(locale, messages, mock(RouteAccessRegistry.class))).build();
    }

    private ExternalCapabilityPublication install(String owner, String policy,
            Function<LoginVerificationRequest, LoginVerificationResult> action) {
        var context = new AnnotationConfigApplicationContext();
        context.registerBean(LoginVerification.class, () -> new LoginVerification() {
            public String policyId() { return policy; }
            public LoginVerificationResult verify(LoginVerificationRequest request) { return action.apply(request); }
        });
        context.refresh();
        contexts.add(context);
        var preparation = registry.allocatePreparation(owner, owner, 1);
        registry.installPreparation(preparation);
        new LoginVerificationCapabilityAdapter(registry).prepare(preparation, context);
        return registry.publish(preparation);
    }

    private static LoginVerificationResult approve() {
        return new LoginVerificationResult(LoginVerificationResult.Status.APPROVED, "");
    }

    private Map<String, Object> request() {
        var value = new LinkedHashMap<String, Object>();
        value.put("username", "admin");
        value.put("password", "password1234");
        value.put("rememberMe", true);
        return value;
    }

    private org.springframework.test.web.servlet.ResultActions login(Map<String, Object> value) throws Exception {
        return mvc.perform(post("/api/auth/login").header("Origin", "http://localhost")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(value)));
    }

    @Test
    @DisplayName("必要策略缺席、歧义、拒绝和异常都不生成会话或 Cookie，关闭配置保留密码流程")
    void rejectsBeforeSideEffects() throws Exception {
        Path state = temporary.resolve("state/setup_config.json");
        byte[] original = Files.readAllBytes(state);
        login(request()).andExpect(status().isServiceUnavailable()).andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
        var first = install("provider-a", "neutral", attempt -> approve());
        var second = install("provider-b", "neutral", attempt -> approve());
        login(request()).andExpect(status().isServiceUnavailable());
        registry.withdraw(first);
        registry.withdraw(second);
        install("rejector", "neutral", attempt -> new LoginVerificationResult(LoginVerificationResult.Status.REJECTED, ""));
        login(request()).andExpect(status().isForbidden());
        assertThat(Files.readAllBytes(state)).isEqualTo(original);
        verify(setup, never()).createSession(anyBoolean());
        configure("");
        login(request()).andExpect(status().isOk()).andExpect(cookie().exists("pixiv_session"));
        verify(setup).createSession(true);
    }

    @Test
    @DisplayName("策略异常仅返回稳定错误，密码错误、限流和 CSRF 在策略之前拒绝")
    void failureDoesNotExposeProofOrBypassExistingGuards() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        install("broken", "neutral", attempt -> {
            calls.incrementAndGet();
            throw new IllegalStateException("private-proof");
        });
        var result = login(request()).andExpect(status().isServiceUnavailable()).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("private-proof");
        var wrong = request();
        wrong.put("password", "wrong");
        login(wrong).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").header("Origin", "https://foreign.example")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request())))
                .andExpect(status().isForbidden());
        when(limits.isAllowed(anyString())).thenReturn(false);
        login(request()).andExpect(status().isTooManyRequests());
        assertThat(calls).hasValue(1);
        verify(setup, never()).createSession(anyBoolean());
    }

    @Test
    @DisplayName("挑战绑定请求与精确 publication，响应只消费一次，同 id 替换不接受旧挑战")
    void challengeReplacementAndReplay() throws Exception {
        Function<LoginVerificationRequest, LoginVerificationResult> challenge = attempt -> attempt.response()
                && attempt.proof().equals("neutral-answer") ? approve()
                : new LoginVerificationResult(LoginVerificationResult.Status.CHALLENGE, "neutral-question");
        var first = install("provider", "neutral", challenge);
        String old = challengeToken();
        var drain = registry.withdraw(first).orElseThrow();
        registry.retireDrained(drain);
        registry.acknowledgeRetired(drain);
        registry.forgetRetirementAcknowledgement(drain);
        install("provider", "neutral", challenge);
        var answer = request();
        answer.put("verificationToken", old);
        answer.put("verificationProofs", Map.of("neutral", "neutral-answer"));
        login(answer).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("setup.login.verification.stale"));
        verify(setup, never()).createSession(anyBoolean());
        answer.put("verificationToken", challengeToken());
        login(answer).andExpect(status().isOk()).andExpect(cookie().exists("pixiv_session"));
        login(answer).andExpect(status().isConflict()).andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
        verify(setup, times(1)).createSession(true);
    }

    private String challengeToken() throws Exception {
        var response = login(request()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("setup.login.verification.required"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE)).andReturn().getResponse();
        return json.readTree(response.getContentAsByteArray()).get("verificationToken").asText();
    }

    @Test
    @DisplayName("所有配置策略都必须通过，challenge 不能在超时后重用")
    void allRequiredPoliciesAndExpiry() throws Exception {
        var now = Instant.now();
        var current = new java.util.concurrent.atomic.AtomicReference<>(now);
        clock = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return current.get(); }
        };
        configure("neutral,second");
        install("a", "neutral", attempt -> approve());
        login(request()).andExpect(status().isServiceUnavailable());
        install("b", "second", attempt -> new LoginVerificationResult(LoginVerificationResult.Status.CHALLENGE, "question"));
        var answer = request();
        answer.put("verificationToken", challengeToken());
        current.set(now.plusSeconds(301));
        login(answer).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("setup.login.verification.stale"));
        verify(setup, never()).createSession(anyBoolean());
    }

    @Test
    @DisplayName("撤回拒绝新登录并等待已接纳验证及真实会话写入结束")
    void withdrawalDrainsWholeLoginOperation() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var published = install("running", "neutral", attempt -> {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
            } catch (InterruptedException interrupted) { throw new IllegalStateException(interrupted); }
            return approve();
        });
        var executor = Executors.newSingleThreadExecutor();
        try {
            var request = request();
            var work = executor.submit(() -> login(request).andReturn());
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var drain = registry.withdraw(published).orElseThrow();
            assertThat(drain.isDrained()).isFalse();
            login(request()).andExpect(status().isServiceUnavailable());
            verify(setup, never()).createSession(anyBoolean());
            release.countDown();
            assertThat(work.get(5, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
            verify(setup).createSession(true);
            assertThat(drain.isDrained()).isTrue();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }
}
