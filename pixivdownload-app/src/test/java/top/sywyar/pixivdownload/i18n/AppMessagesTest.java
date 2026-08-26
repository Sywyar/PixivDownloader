package top.sywyar.pixivdownload.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.StaticMessageSource;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class AppMessagesTest {

    @AfterEach
    void resetLocaleContext() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    @DisplayName("日志文案不跟随请求语言")
    void logMessagesAlwaysUseEnglish() {
        StaticMessageSource source = new StaticMessageSource();
        source.addMessage("sample", Locale.US, "English");
        source.addMessage("sample", Locale.SIMPLIFIED_CHINESE, "中文");
        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);

        assertThat(new AppMessages(source).getForLog("sample")).isEqualTo("English");
    }
}
