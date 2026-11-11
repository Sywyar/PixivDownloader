package top.sywyar.pixivdownload.plugin.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import top.sywyar.pixivdownload.config.InstanceCookies;
import top.sywyar.pixivdownload.common.SessionUtils;
import top.sywyar.pixivdownload.common.UuidUtils;
import top.sywyar.pixivdownload.i18n.AppLocaleResolver;
import top.sywyar.pixivdownload.i18n.LocaleCatalog;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.route.RouteAccessRegistry;
import top.sywyar.pixivdownload.plugin.runtime.artifact.PluginDevelopmentArtifacts;
import top.sywyar.pixivdownload.setup.CsrfProtectionFilter;
import top.sywyar.pixivdownload.setup.TrustedForwardedRequestFilter;
import top.sywyar.pixivdownload.setup.guest.GuestInviteSession;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@ResourceLock("SYSTEM_PROPERTIES")
@DisplayName("开发模式 Cookie 的读取、清理与混合模式边界")
class DevelopmentCookieIsolationTest {
    private String previousMode;
    private static final String UUID = "11111111-2222-4333-8444-555555555555";

    @BeforeEach void saveMode() { previousMode = System.getProperty(PluginDevelopmentArtifacts.ENABLED_PROPERTY); }
    @AfterEach void restoreMode() { mode(previousMode); }

    private static void mode(String value) {
        if (value == null) System.clearProperty(PluginDevelopmentArtifacts.ENABLED_PROPERTY);
        else System.setProperty(PluginDevelopmentArtifacts.ENABLED_PROPERTY, value);
    }

    private static MockHttpServletRequest request(int port, Cookie... cookies) {
        var request = new MockHttpServletRequest("POST", "/api/cookie-fixture/write");
        request.setServerName("localhost"); request.setServerPort(port); request.setCookies(cookies);
        return request;
    }

    @ParameterizedTest @NullSource @ValueSource(strings = {"false"})
    @DisplayName("未设置和显式关闭开发模式都保持原 Cookie 名称及读取优先级")
    void normalModeRetainsNamesAndCookiePriority(String configured) {
        mode(configured);
        var request = request(8081, new Cookie("pixiv_session", "cookie-session"),
                new Cookie("pixiv_user_id", UUID), new Cookie("pixiv_invite_token", "invalid-invite"),
                new Cookie("pixiv_dev_8081_pixiv_session", "development-session"));
        request.addHeader("X-Session-Token", "header-session");
        for (String name : List.of("pixiv_session", "pixiv_invite_token", "pixiv_hook_client", "pixiv_hook_ticket_", "pixiv_lang", "pixiv_user_id"))
            assertThat(InstanceCookies.name(request, name)).isEqualTo(name);
        assertThat(SessionUtils.extractToken(request)).isEqualTo("cookie-session");
        assertThat(UuidUtils.extractExistingUuid(request)).isEqualTo(UUID);
        assertThat(GuestInviteSession.isGuestRequest(request)).isTrue();
    }

    @Test @DisplayName("开发模式不认领旧参数和其他端口参数，也不遮蔽显式请求头")
    void developmentReadsOnlyCurrentInstance() {
        mode("true");
        var request = request(8081, new Cookie("pixiv_session", "legacy"),
                new Cookie("pixiv_dev_8080_pixiv_session", "other"), new Cookie("pixiv_invite_token", "legacy"),
                new Cookie("pixiv_dev_8080_pixiv_invite_token", "other"), new Cookie("pixiv_user_id", UUID));
        assertThat(SessionUtils.extractToken(request)).isNull();
        assertThat(UuidUtils.extractExistingUuid(request)).isNull();
        assertThat(GuestInviteSession.isGuestRequest(request)).isFalse();
        request.addHeader("X-Session-Token", "header-session");
        request.addHeader("X-User-UUID", UUID);
        assertThat(SessionUtils.extractToken(request)).isEqualTo("header-session");
        assertThat(UuidUtils.extractExistingUuid(request)).isEqualTo(UUID);
        request.setCookies(new Cookie("pixiv_dev_8081_pixiv_session", "invalid-current"),
                new Cookie("pixiv_dev_8081_pixiv_invite_token", "invalid-current"));
        assertThat(SessionUtils.extractToken(request)).isEqualTo("invalid-current");
        assertThat(GuestInviteSession.isGuestRequest(request)).isTrue();
    }

    @ParameterizedTest @CsvSource({"http,80", "https,443", "http,8081", "https,18443"})
    @DisplayName("隔离名消费可信反代规范化的外部端口及默认端口")
    void normalizedProxyPort(String scheme, int port) throws Exception {
        mode("true");
        var request = request(18080);
        request.setRemoteAddr("127.0.0.1");
        String host = "example.test" + (port == 80 || port == 443 ? "" : ":" + port);
        request.addHeader("Forwarded", "for=192.0.2.9;proto=" + scheme + ";host=\"" + host + "\"");
        var response = new MockHttpServletResponse();
        new TrustedForwardedRequestFilter("127.0.0.1/32").doFilter(request, response, (normalized, ignored) -> {
            assertThat(InstanceCookies.name((HttpServletRequest) normalized, "pixiv_session"))
                    .isEqualTo("pixiv_dev_" + port + "_pixiv_session");
            assertThat(((HttpServletRequest) normalized).getHeader("Forwarded")).isNull();
        });
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test @DisplayName("开发隔离凭证仍触发 CSRF，其他实例凭证不能改变当前来源契约")
    void csrfRecognizesEveryCurrentAmbientCredential() {
        mode("true");
        var resolver = new AppLocaleResolver(LocaleCatalog.defaultCatalog());
        var csrf = new CsrfProtectionFilter(resolver, TestI18nBeans.appMessages(),
                new RouteAccessRegistry(new PluginRegistry(List.of())));
        for (String name : List.of("pixiv_session", "pixiv_invite_token", "pixiv_user_id")) {
            var request = request(8081, new Cookie("pixiv_dev_8081_" + name, "ambient"));
            assertThat(csrf.allowsWrite(request)).isFalse();
            request.addHeader("Origin", "http://localhost:8080");
            assertThat(csrf.allowsWrite(request)).isFalse();
            request.removeHeader("Origin"); request.addHeader("Origin", "http://localhost:8081");
            assertThat(csrf.allowsWrite(request)).isTrue();
            assertThat(csrf.allowsWrite(request(8081, new Cookie(name, "legacy"),
                    new Cookie("pixiv_dev_8080_" + name, "other")))).isTrue();
        }
    }

    @Test @DisplayName("语言解析与写入只使用当前实例，参数和 Accept-Language 优先级保持")
    void localeIsolation() {
        mode("true");
        var resolver = new AppLocaleResolver(LocaleCatalog.defaultCatalog());
        var request = request(8081, new Cookie("pixiv_lang", "zh-CN"), new Cookie("pixiv_dev_8080_pixiv_lang", "zh-CN"));
        request.addHeader("Accept-Language", "en-US");
        assertThat(resolver.resolveLocale(request).toLanguageTag()).isEqualTo("en-US");
        request.setCookies(new Cookie("pixiv_dev_8081_pixiv_lang", "zh-CN"));
        assertThat(resolver.resolveLocale(request).toLanguageTag()).isEqualTo("zh-CN");
        request.setParameter("lang", "en-US");
        assertThat(resolver.resolveLocale(request).toLanguageTag()).isEqualTo("en-US");
        var response = new MockHttpServletResponse();
        resolver.setLocale(request, response, Locale.SIMPLIFIED_CHINESE);
        assertThat(response.getCookies()).extracting(Cookie::getName).containsExactly("pixiv_dev_8081_pixiv_lang");
        mode("false");
        response = new MockHttpServletResponse();
        resolver.setLocale(request, response, Locale.US);
        assertThat(response.getCookies()).extracting(Cookie::getName).containsExactly("pixiv_lang");
    }

    @ParameterizedTest @ValueSource(strings = {"true", "false"})
    @DisplayName("开发及混合模式的挑战身份与并发原生票据互不清除且只消费一次")
    void hookIdentityAndNativeTickets(String firstMode) {
        var first = new HookChallenges(); var second = new HookChallenges();
        mode(firstMode);
        var firstResponse = new MockHttpServletResponse();
        String subject = first.subject(request(8080), firstResponse);
        Cookie firstIdentity = firstResponse.getCookies()[0];
        mode("true");
        var secondResponse = new MockHttpServletResponse();
        second.subject(request(8081, firstIdentity), secondResponse);
        Cookie secondIdentity = secondResponse.getCookies()[0];
        mode(firstMode);
        assertThat(first.subject(request(8080, firstIdentity, secondIdentity), new MockHttpServletResponse())).isEqualTo(subject);
        var target = new HookChallenges.Target(subject, "scope", "digest");
        stage(first, "a", target); stage(first, "a2", target);
        firstResponse = new MockHttpServletResponse();
        first.nativeTicket(request(8080), firstResponse, "a", target);
        Cookie firstTicket = firstResponse.getCookies()[0];
        first.nativeTicket(request(8080, firstTicket), firstResponse, "a2", target);
        Cookie concurrentTicket = firstResponse.getCookies()[1];
        mode("true");
        stage(second, "b", target); secondResponse = new MockHttpServletResponse();
        second.nativeTicket(request(8081, firstTicket, concurrentTicket), secondResponse, "b", target);
        assertThat(secondResponse.getCookies()).hasSize(1);
        Cookie secondTicket = secondResponse.getCookies()[0];
        assertThat(secondTicket.getName()).doesNotStartWith("pixiv_hook_ticket_");
        mode(firstMode);
        stage(first, "a3", target);
        var reverseCleanup = new MockHttpServletResponse();
        first.nativeTicket(request(8080, firstTicket, concurrentTicket, secondTicket), reverseCleanup, "a3", target);
        assertThat(reverseCleanup.getCookies()).hasSize(1);
        Cookie thirdTicket = reverseCleanup.getCookies()[0];
        var consumed = new MockHttpServletResponse();
        assertThat(first.consume(request(8080, secondTicket, firstTicket), consumed, target, List.of()).proof()).isNotNull();
        assertThat(consumed.getCookies()).extracting(Cookie::getName).containsExactly(firstTicket.getName());
        assertThat(first.consume(request(8080, firstTicket), new MockHttpServletResponse(), target, List.of()).proof()).isNull();
        assertThat(first.consume(request(8080, concurrentTicket), new MockHttpServletResponse(), target, List.of()).proof()).isNotNull();
        assertThat(first.consume(request(8080, thirdTicket), new MockHttpServletResponse(), target, List.of()).proof()).isNotNull();
        mode("true");
        assertThat(second.consume(request(8081, secondTicket), new MockHttpServletResponse(), target, List.of()).proof()).isNotNull();
    }

    @Test @DisplayName("当前实例过期票据清理保留其他实例与非开发票据，请求头票据仍只消费一次")
    void expiryAndHeaderTickets() {
        mode("true");
        var clock = new MutableClock(); var hooks = new HookChallenges(clock);
        var target = new HookChallenges.Target("subject", "scope", "digest");
        stage(hooks, "expired", target); clock.now += 300_001;
        stage(hooks, "current", target);
        var response = new MockHttpServletResponse();
        hooks.nativeTicket(request(8081, new Cookie("pixiv_dev_8081_pixiv_hook_ticket_expired", "scope"),
                new Cookie("pixiv_dev_8080_pixiv_hook_ticket_other", "scope"), new Cookie("pixiv_hook_ticket_old", "scope")),
                response, "current", target);
        assertThat(response.getCookies()).extracting(Cookie::getName)
                .containsExactly("pixiv_dev_8081_pixiv_hook_ticket_expired", "pixiv_dev_8081_pixiv_hook_ticket_current");
        assertThat(response.getCookies()[0].getMaxAge()).isZero();
        var request = request(8081, new Cookie("pixiv_hook_ticket_current", "scope"));
        request.addHeader("X-Pixiv-Hook-Ticket", "current");
        response = new MockHttpServletResponse();
        assertThat(hooks.consume(request, response, target, List.of()).proof()).isNotNull();
        assertThat(response.getCookies()).isEmpty();
        assertThat(hooks.consume(request, response, target, List.of()).proof()).isNull();
    }

    private static void stage(HookChallenges hooks, String token, HookChallenges.Target target) {
        assertThat(hooks.retain(token, target.digest(), List.of())).isTrue();
        assertThat(hooks.stage(token, target.digest(), List.of(), Map.of("fixture", "proof"))).isTrue();
    }

    private static final class MutableClock extends Clock {
        long now;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now); }
        @Override public long millis() { return now; }
    }
}
