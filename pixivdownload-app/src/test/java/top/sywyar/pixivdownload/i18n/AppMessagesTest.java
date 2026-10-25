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

    @Test
    @DisplayName("文件名模板提示保留字面占位符并格式化作品标识")
    void filenameTemplateMessagePreservesLiteralPlaceholder() {
        LocaleCatalog catalog = LocaleCatalog.defaultCatalog();
        AppMessages messages = new AppMessages(new CatalogMessageSource(catalog, "i18n.messages"));

        for (LocaleDescriptor locale : catalog.visibleLocales()) {
            assertThat(messages.get(locale.toLocale(), "download.file.log.author-name-missing", "work-42"))
                    .as(locale.tag())
                    .contains("{author_name}", "work-42")
                    .doesNotContain("{0}");
        }
    }
}
