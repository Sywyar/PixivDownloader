package top.sywyar.pixivdownload.setup;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
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
import top.sywyar.pixivdownload.plugin.security.*;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.OperationVerificationCapabilityAdapter;
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
class OperationVerificationGateTest {
    @TempDir Path temporary;
    final ObjectMapper json = JsonMapper.builderWithJackson2Defaults().build();
    final ExternalCapabilityInvocationRegistry registry = new ExternalCapabilityInvocationRegistry();
    final List<AnnotationConfigApplicationContext> contexts = new ArrayList<>();
    SetupService setup;
    LoginRateLimitService limits;
    MockMvc mvc;
    Clock clock = Clock.systemUTC();
    final Map<String, jakarta.servlet.http.Cookie> cookies = new HashMap<>();

    final java.util.concurrent.ExecutorService hookExecutor = java.util.concurrent.Executors.newFixedThreadPool(8);

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
    void close() throws java.io.IOException {
        hookExecutor.shutdownNow();
        contexts.forEach(AnnotationConfigApplicationContext::close);
        System.clearProperty(RuntimeFiles.CONFIG_DIR_PROPERTY);
        System.clearProperty(RuntimeFiles.STATE_DIR_PROPERTY);
        top.sywyar.pixivdownload.testsupport.TestDirectoryCleanup.delete(temporary);
    }

    private void configure(String policies) throws java.io.IOException {
        var messages = TestI18nBeans.appMessages();
        var store = new top.sywyar.pixivdownload.plugin.web.page.WebPublicationStore(temporary.resolve("publications"), temporary.resolve("config/enrolled"));
        var pages = new top.sywyar.pixivdownload.plugin.registry.web.PageContributionRegistry(List.of(new LoginPageContribution().loginEditablePage()), registry);
        var factory = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        var routes = new RouteAccessRegistry(new top.sywyar.pixivdownload.plugin.registry.PluginRegistry(List.of()));
        routes.register("core", List.of(top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution.publicRoute("/api/auth/**")));
        var endpoints = new OperationEndpoints(factory.getBeanProvider(org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class), routes, pages);
        endpoints.publications(store);
        var admissions = new OperationAdmissions();
        var contracts = new top.sywyar.pixivdownload.plugin.web.page.HookPublications(registry, endpoints, pages, store, admissions);
        var interception = new HttpOperationHooks(endpoints, store, registry, admissions, new HookChallenges(clock), contracts,
                List.of(new OperationRequirement("core:login", policies.isBlank() ? List.of() : Arrays.asList(policies.split(",")))), hookExecutor);
        var controller = new SetupController(setup, new MultiModeConfig(), mock(ProxySetupService.class), messages,
                mock(top.sywyar.pixivdownload.setup.guest.GuestInviteService.class));
        var locale = mock(AppLocaleResolver.class);
        when(locale.resolveLocale(any())).thenReturn(Locale.US);
        var csrf = new CsrfProtectionFilter(locale, messages, routes); factory.addBean("csrf", csrf);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(messages))
                .addInterceptors(interception)
                .addFilters(new HookRequestFilter(factory.getBeanProvider(org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class),
                        factory.getBeanProvider(CsrfProtectionFilter.class)), csrf, new LoginAdmissionFilter(limits, locale, messages))
                .alwaysDo(result -> {
                    interception.requestDestroyed(new jakarta.servlet.ServletRequestEvent(result.getRequest().getServletContext(), result.getRequest()));
                    for (var cookie : result.getResponse().getCookies()) cookies.put(cookie.getName(), cookie);
                }).build();
        factory.addBean("mapping", mvc.getDispatcherServlet().getWebApplicationContext().getBean(org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class));
    }

    private ExternalCapabilityPublication install(String owner, String policy,
            Function<OperationVerificationRequest, OperationVerificationResult> action) {
        var context = new AnnotationConfigApplicationContext();
        context.registerBean(OperationVerification.class, () -> new OperationVerification() {
            public String policyId() { return policy; }
            public String generation() { return "test-v1"; }
            public Policy policy() { return new Policy("p1", "", INTERACTION, java.util.List.of()); }
            public OperationVerificationResult verify(OperationVerificationRequest request) { return action.apply(request); }
        });
        context.refresh();
        contexts.add(context);
        var preparation = registry.allocatePreparation(owner, owner, 1);
        registry.installPreparation(preparation);
        new OperationVerificationCapabilityAdapter(registry).prepare(preparation, context);
        return registry.publish(preparation);
    }

    private static OperationVerificationResult approve() {
        return new OperationVerificationResult(OperationVerificationResult.Status.APPROVED, "");
    }

    private Map<String, Object> request() {
        var value = new LinkedHashMap<String, Object>();
        value.put("username", "admin");
        value.put("password", "password1234");
        value.put("rememberMe", true);
        return value;
    }

    private org.springframework.test.web.servlet.ResultActions login(Map<String, Object> value) throws Exception {
        return login(value, "");
    }
    private org.springframework.test.web.servlet.ResultActions login(Map<String, Object> value, String ticket) throws Exception {
        return mvc.perform(post("/api/auth/login").header("Origin", "http://localhost")
                .header("X-Pixiv-Hook-Ticket", ticket).with(request -> { if (!cookies.isEmpty()) request.setCookies(cookies.values().toArray(jakarta.servlet.http.Cookie[]::new)); return request; })
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(value)));
    }

    @Test
    @DisplayName("必要策略缺席、歧义、拒绝和异常都不生成会话或 Cookie，关闭配置保留密码流程")
    void rejectsBeforeSideEffects() throws Exception {
        Path state = temporary.resolve("state/setup_config.json");
        byte[] original = Files.readAllBytes(state);
        login(request()).andExpect(status().isServiceUnavailable()).andExpect(cookie().doesNotExist("pixiv_session"));
        var first = install("provider-a", "neutral", attempt -> approve());
        var second = install("provider-b", "neutral", attempt -> approve());
        login(request()).andExpect(status().isServiceUnavailable());
        registry.withdraw(first);
        registry.withdraw(second);
        install("rejector", "neutral", attempt -> new OperationVerificationResult(OperationVerificationResult.Status.REJECTED, ""));
        login(request()).andExpect(status().isForbidden());
        assertThat(Files.readAllBytes(state)).isEqualTo(original);
        verify(setup, never()).createSession(anyBoolean());
        verify(setup, never()).checkLogin(anyString(), anyString());
        configure("");
        login(request()).andExpect(status().isOk()).andExpect(cookie().exists("pixiv_session"));
        verify(setup).createSession(true);
    }

    @Test
    @DisplayName("策略失败不核对密码，限流和 CSRF 在策略之前拒绝")
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
        login(wrong).andExpect(status().isServiceUnavailable());
        mvc.perform(post("/api/auth/login").header("Origin", "https://foreign.example")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request())))
                .andExpect(status().isForbidden());
        when(limits.isAllowed(anyString())).thenReturn(false);
        login(request()).andExpect(status().isTooManyRequests());
        assertThat(calls).hasValue(2);
        verify(setup, never()).checkLogin(anyString(), anyString());
        verify(setup, never()).createSession(anyBoolean());
    }

    @Test
    @DisplayName("挑战绑定请求与精确 publication，响应只消费一次，同 id 替换不接受旧挑战")
    void challengeReplacementAndReplay() throws Exception {
        Function<OperationVerificationRequest, OperationVerificationResult> challenge = attempt -> attempt.response()
                && attempt.proof().equals("neutral-answer") ? approve()
                : new OperationVerificationResult(OperationVerificationResult.Status.CHALLENGE, "neutral-question");
        var first = install("provider", "neutral", challenge);
        String old = challengeToken();
        var drain = registry.withdraw(first).orElseThrow();
        registry.retireDrained(drain);
        registry.acknowledgeRetired(drain);
        registry.forgetRetirementAcknowledgement(drain);
        install("provider", "neutral", challenge);
        var answer = request();
        prepare(old, Map.of("neutral", "neutral-answer")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("HOOK_STALE"));
        verify(setup, never()).createSession(anyBoolean());
        String current = challengeToken(); prepare(current, Map.of("neutral", "neutral-answer")).andExpect(status().isOk());
        login(answer, current).andExpect(status().isOk()).andExpect(cookie().exists("pixiv_session"));
        login(answer, current).andExpect(status().isConflict()).andExpect(cookie().doesNotExist("pixiv_session"));
        verify(setup, times(1)).createSession(true);
    }

    private String challengeToken() throws Exception {
        var response = prepare("", Map.of()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("HOOK_CHALLENGE"))
                .andExpect(cookie().doesNotExist("pixiv_session")).andReturn().getResponse();
        return json.readTree(response.getContentAsByteArray()).get("token").asString();
    }
    private org.springframework.test.web.servlet.ResultActions prepare(String token, Map<String, String> proofs) throws Exception {
        return mvc.perform(post(HookRequestFilter.PREPARE_PATH).header("Origin", "http://localhost")
                .with(request -> { if (!cookies.isEmpty()) request.setCookies(cookies.values().toArray(jakarta.servlet.http.Cookie[]::new)); return request; }).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("method", "POST", "url", "/api/auth/login", "headers", Map.of("content-type", "application/json"), "token", token, "proofs", proofs))));
    }

    @Test
    @DisplayName("准备不核对密码，完整证明只允许一次密码尝试，错误密码也消费证明")
    void preparesBeforeCredentialsAndConsumesFailedAttempt() throws Exception {
        install("provider", "neutral", attempt -> attempt.response() && attempt.proof().equals("answer")
                ? approve() : new OperationVerificationResult(OperationVerificationResult.Status.CHALLENGE, "question"));
        var answer = request();
        String token = challengeToken();
        verify(setup, never()).checkLogin(anyString(), anyString());
        verify(setup, never()).createSession(anyBoolean());
        prepare(token, Map.of("neutral", "answer")).andExpect(status().isOk());
        answer.put("password", "wrong");
        login(answer, token).andExpect(status().isUnauthorized()).andExpect(cookie().doesNotExist("pixiv_session"));
        verify(setup, times(1)).checkLogin("admin", "wrong");
        answer.put("password", "password1234");
        login(answer, token).andExpect(status().isConflict());
        verify(setup, never()).checkLogin("admin", "password1234");
        token = challengeToken(); prepare(token, Map.of("neutral", "answer")).andExpect(status().isOk());
        login(answer, token).andExpect(status().isOk()).andExpect(cookie().exists("pixiv_session"));
    }

    @Test
    @DisplayName("无需验证的准备也不创建会话，准备入口仍执行输入、CSRF 和限流检查")
    void preparationDoesNotExecuteLogin() throws Exception {
        configure("");
        prepare("", Map.of()).andExpect(status().isOk()).andExpect(jsonPath("$.code").value("HOOK_APPROVED"))
                .andExpect(cookie().doesNotExist("pixiv_session"));
        verify(setup, never()).checkLogin(anyString(), anyString());
        verify(setup, never()).createSession(anyBoolean());
        mvc.perform(post(HookRequestFilter.PREPARE_PATH).header("Origin", "http://localhost")
                .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"POST\",\"url\":\"//foreign.example\"}")).andExpect(status().isBadRequest());
        mvc.perform(post(HookRequestFilter.PREPARE_PATH).header("Origin", "https://foreign.example")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        when(limits.isAllowed(anyString())).thenReturn(false);
        prepare("", Map.of()).andExpect(status().isTooManyRequests());
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
        install("b", "second", attempt -> new OperationVerificationResult(OperationVerificationResult.Status.CHALLENGE, "question"));
        String token = challengeToken();
        current.set(now.plusSeconds(301));
        login(request(), token).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("HOOK_STALE"));
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
