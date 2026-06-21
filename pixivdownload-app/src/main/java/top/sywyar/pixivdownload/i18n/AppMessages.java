package top.sywyar.pixivdownload.i18n;

import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
@RequiredArgsConstructor
public class AppMessages {

    private final MessageSource messageSource;

    public String get(String code, Object... args) {
        return getOrDefault(LocaleContextHolder.getLocale(), code, code, args);
    }

    public String get(Locale locale, String code, Object... args) {
        return getOrDefault(locale, code, code, args);
    }

    public String get(MessageSourceResolvable resolvable) {
        return get(LocaleContextHolder.getLocale(), resolvable);
    }

    public String get(Locale locale, MessageSourceResolvable resolvable) {
        return messageSource.getMessage(resolvable, AppLocale.normalize(locale));
    }

    public String getOrDefault(String code, String defaultMessage, Object... args) {
        return getOrDefault(LocaleContextHolder.getLocale(), code, defaultMessage, args);
    }

    public String getOrDefault(Locale locale, String code, String defaultMessage, Object... args) {
        return messageSource.getMessage(
                code,
                args,
                defaultMessage,
                AppLocale.normalize(locale)
        );
    }

    /**
     * 解析日志专用文案,固定使用 JVM 系统语言({@link Locale#getDefault()})。
     * 日志语句可能在后台线程上执行(无请求上下文),也不应随请求 locale 漂移,
     * 因此跟随 JVM 系统语言。运维通过启动参数 {@code -Duser.language=en} 切换全部日志语言。
     */
    public String getForLog(String code, Object... args) {
        return getOrDefault(Locale.getDefault(), code, code, args);
    }

    public String getForLog(MessageSourceResolvable resolvable) {
        return get(Locale.getDefault(), resolvable);
    }
}
