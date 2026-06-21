package top.sywyar.pixivdownload.i18n;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.LocaleResolver;

import java.time.Duration;
import java.util.Locale;

@Component
public class AppLocaleResolver implements LocaleResolver {

    private static final int COOKIE_MAX_AGE_SECONDS = (int) Duration.ofDays(365).getSeconds();

    @Override
    public Locale resolveLocale(HttpServletRequest request) {
        Locale paramLocale = AppLocale.parse(request.getParameter(AppLocale.LANGUAGE_PARAM_NAME));
        if (paramLocale != null) {
            return paramLocale;
        }

        Locale cookieLocale = AppLocale.parse(readCookieValue(request, AppLocale.LANGUAGE_COOKIE_NAME));
        if (cookieLocale != null) {
            return cookieLocale;
        }

        return AppLocale.resolveAcceptLanguage(request.getHeader(HttpHeaders.ACCEPT_LANGUAGE));
    }

    @Override
    public void setLocale(HttpServletRequest request, @Nullable HttpServletResponse response, @Nullable Locale locale) {
        if (response == null) {
            return;
        }

        Locale normalizedLocale = AppLocale.normalize(locale);
        Cookie cookie = new Cookie(AppLocale.LANGUAGE_COOKIE_NAME, normalizedLocale.toLanguageTag());
        cookie.setPath("/");
        cookie.setHttpOnly(false);
        cookie.setSecure(request.isSecure());
        cookie.setMaxAge(COOKIE_MAX_AGE_SECONDS);
        response.addCookie(cookie);
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
