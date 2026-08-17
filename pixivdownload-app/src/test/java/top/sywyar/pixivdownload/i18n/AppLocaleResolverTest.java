package top.sywyar.pixivdownload.i18n;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AppLocaleResolver 请求语言解析（基于 LocaleCatalog）")
class AppLocaleResolverTest {

    private final LocaleCatalog catalog = LocaleCatalog.defaultCatalog();
    private final AppLocaleResolver resolver = new AppLocaleResolver(catalog);

    @Test
    @DisplayName("?lang= 参数优先")
    void paramWins() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("lang", "en-US");
        assertThat(resolver.resolveLocale(request)).isEqualTo(Locale.US);
    }

    @Test
    @DisplayName("?lang= 参数支持 alias（zh-Hans → zh-CN，字符串与 Locale 形态一致）")
    void paramSupportsAliases() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("lang", "zh-Hans");
        assertThat(resolver.resolveLocale(request)).isEqualTo(Locale.SIMPLIFIED_CHINESE);
        request = new MockHttpServletRequest();
        request.setParameter("lang", "ZH_HANS");
        assertThat(resolver.resolveLocale(request)).isEqualTo(Locale.SIMPLIFIED_CHINESE);
    }

    @Test
    @DisplayName("Cookie 其次，参数缺省时生效")
    void cookieUsedWhenNoParam() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("pixiv_lang", "zh-CN"));
        assertThat(resolver.resolveLocale(request)).isEqualTo(Locale.SIMPLIFIED_CHINESE);
    }

    @Test
    @DisplayName("Accept-Language 基于 catalog：受支持语言可匹配、无匹配落到默认 en-US")
    void acceptLanguageIsCatalogDriven() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Accept-Language", "ja-JP,ja;q=0.9,en;q=0.8");
        assertThat(resolver.resolveLocale(request)).isEqualTo(Locale.JAPAN);

        request = new MockHttpServletRequest();
        request.addHeader("Accept-Language", "fr-FR,fr;q=0.9");
        assertThat(resolver.resolveLocale(request)).isEqualTo(Locale.US);
    }

    @Test
    @DisplayName("无任何信号时落到默认语言 en-US")
    void defaultsToEnglish() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        assertThat(resolver.resolveLocale(request)).isEqualTo(Locale.US);
    }

    @Test
    @DisplayName("setLocale 写入规范化 tag 的持久 Cookie")
    void setLocaleWritesNormalizedCookie() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        resolver.setLocale(request, response, Locale.forLanguageTag("en-GB"));
        Cookie cookie = response.getCookie("pixiv_lang");
        assertThat(cookie).isNotNull();
        assertThat(cookie.getValue()).isEqualTo("en-US");
        assertThat(cookie.getMaxAge()).isEqualTo(365 * 24 * 60 * 60);
    }
}
