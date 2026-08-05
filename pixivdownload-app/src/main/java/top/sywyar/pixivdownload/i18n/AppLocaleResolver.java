package top.sywyar.pixivdownload.i18n;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.LocaleResolver;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 基于 {@link LocaleCatalog} 的 Web {@link LocaleResolver}。
 * <p>
 * 解析优先级：{@code ?lang=} 参数 → {@code pixiv_lang} Cookie → {@code Accept-Language}（按 catalog
 * 的可见语言集做 RFC 4647 匹配）。所有结果都经 catalog 归一化到正式 tag，无匹配时落到默认语言。
 */
@Component
public class AppLocaleResolver implements LocaleResolver {

    private static final int COOKIE_MAX_AGE_SECONDS = (int) Duration.ofDays(365).getSeconds();

    private final LocaleCatalog catalog;

    public AppLocaleResolver(LocaleCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public Locale resolveLocale(HttpServletRequest request) {
        Locale paramLocale = parse(request.getParameter(catalog.languageParameterName()));
        if (paramLocale != null) {
            return paramLocale;
        }

        Locale cookieLocale = parse(readCookieValue(request, catalog.languageCookieName()));
        if (cookieLocale != null) {
            return cookieLocale;
        }

        return resolveAcceptLanguage(request.getHeader(HttpHeaders.ACCEPT_LANGUAGE));
    }

    @Override
    public void setLocale(HttpServletRequest request, @Nullable HttpServletResponse response, @Nullable Locale locale) {
        if (response == null) {
            return;
        }

        Locale normalizedLocale = catalog.resolve(locale).toLocale();
        Cookie cookie = new Cookie(catalog.languageCookieName(), normalizedLocale.toLanguageTag());
        cookie.setPath("/");
        cookie.setHttpOnly(false);
        cookie.setSecure(request.isSecure());
        cookie.setMaxAge(COOKIE_MAX_AGE_SECONDS);
        response.addCookie(cookie);
    }

    private Locale parse(String candidate) {
        return catalog.match(candidate).map(LocaleDescriptor::toLocale).orElse(null);
    }

    private Locale resolveAcceptLanguage(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return catalog.defaultLocale().toLocale();
        }
        try {
            List<Locale> visible = catalog.visibleLocales().stream()
                    .map(LocaleDescriptor::toLocale)
                    .toList();
            Locale matched = Locale.lookup(Locale.LanguageRange.parse(headerValue), visible);
            Optional<LocaleDescriptor> descriptor = catalog.match(matched);
            return descriptor.map(LocaleDescriptor::toLocale).orElse(catalog.defaultLocale().toLocale());
        } catch (IllegalArgumentException ignored) {
            return catalog.defaultLocale().toLocale();
        }
    }

    private String readCookieValue(HttpServletRequest request, String cookieName) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }

        for (Cookie cookie : cookies) {
            if (cookieName.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
