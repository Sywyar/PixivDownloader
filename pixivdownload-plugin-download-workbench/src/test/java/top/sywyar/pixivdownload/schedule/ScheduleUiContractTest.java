package top.sywyar.pixivdownload.schedule;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("计划任务页面资源绑定与来源中性 lint")
class ScheduleUiContractTest {

    private static final List<String> RESOURCES = List.of(
            "static/pixiv-batch/modes/schedule-core.js",
            "static/pixiv-batch/modes/schedule-editor.js",
            "static/pixiv-batch/modes/schedule-view.js",
            "static/pixiv-batch/modes/schedule-queue.js",
            "static/pixiv-batch/modes/schedule.js"
    );
    private static final Pattern BATCH_STYLESHEET = Pattern.compile(
            "href=\"(/pixiv-batch/pixiv-batch[^\"]*\\.css)\"");

    @Test
    @DisplayName("源码 lint：计划宿主不引用 Pixiv 私有来源类型，来源模块不写 HTML")
    void sourceFrontendKeepsSourceSpecificIdentifiersOutOfHost() throws IOException {
        String source = readSource();
        String pixivModule = readSource("static/pixiv-batch/pixiv-schedule-sources.js");

        assertThat(source)
                .doesNotContain("USER_NEW")
                .doesNotContain("USER_REQUEST")
                .doesNotContain("MY_BOOKMARKS")
                .doesNotContain("FOLLOW_LATEST")
                .doesNotContain("COLLECTION");
        assertThat(pixivModule).doesNotContain("innerHTML");
    }

    @Test
    @DisplayName("抓取上限由来源贡献受控文案键且宿主默认不泄露来源名称")
    void fetchLimitPresentationUsesControlledSourceKeysAndNeutralDefaults() throws IOException {
        String page = readSource("static/pixiv-batch.html");
        Properties zh = readProperties("i18n/web/batch.properties");
        Properties en = readProperties("i18n/web/batch_en.properties");
        assertThat(page)
                .contains("data-i18n=\"schedule.field.fetch-limit.hint.watermark\"")
                .contains("data-i18n=\"schedule.field.fetch-limit.hint.per-run\"");

        assertThat(propertyValue(zh, "schedule.field.fetch-limit.hint.watermark"))
                .doesNotContain("Pixiv");
        assertThat(propertyValue(zh, "schedule.field.fetch-limit.hint.per-run"))
                .doesNotContain("Pixiv");
        assertThat(propertyValue(zh, "schedule.confirm.full-fetch"))
                .doesNotContain("Pixiv");
        assertThat(propertyValue(en, "schedule.field.fetch-limit.hint.watermark"))
                .doesNotContain("Pixiv");
        assertThat(propertyValue(en, "schedule.field.fetch-limit.hint.per-run"))
                .doesNotContain("Pixiv");
        assertThat(propertyValue(en, "schedule.confirm.full-fetch"))
                .doesNotContain("Pixiv");
        assertThat(propertyValue(zh, "schedule.pixiv.confirm.full-fetch")).contains("Pixiv");
        assertThat(propertyValue(en, "schedule.pixiv.confirm.full-fetch")).contains("Pixiv");
    }

    @Test
    @DisplayName("来源缺席时计划任务宿主文案保持来源中性")
    void scheduleHostCredentialCopyStaysNeutralWithoutSourceModule() throws IOException {
        String page = readSource("static/pixiv-batch.html");
        Properties zh = readProperties("i18n/web/batch.properties");
        Properties en = readProperties("i18n/web/batch_en.properties");
        List<String> genericKeys = List.of(
                "schedule.status.saved-authorized",
                "schedule.status.saved-no-credential",
                "schedule.status.saved-overrides",
                "schedule.status.saved-override-failed",
                "schedule.status.override-saved",
                "schedule.status.override-unchanged",
                "schedule.confirm.delete",
                "schedule.confirm.clear-proxy",
                "schedule.confirm.clear-credential",
                "schedule.error.no-credential",
                "schedule.error.revoke-credential",
                "schedule.run-status.auth-expired",
                "schedule.light.auth-expired",
                "schedule.snapshot.field.cookie"
        );

        genericKeys.forEach(key -> {
            assertThat(propertyValue(zh, key)).doesNotContain("Pixiv", "Cookie", "PHPSESSID", "R-18", "我的收藏");
            List.of("Pixiv", "Cookie", "PHPSESSID", "R-18", "My bookmarks")
                    .forEach(forbidden -> assertThat(propertyValue(en, key))
                            .doesNotContainIgnoringCase(forbidden));
        });
        assertThat(page)
                .contains("data-i18n=\"schedule.override.title-generic\"")
                .contains("data-i18n=\"schedule.override.intro-generic\"")
                .contains("data-i18n=\"schedule.field.credential-enabled\"")
                .contains("data-i18n-placeholder=\"schedule.field.credential.placeholder\"")
                .contains("data-i18n=\"schedule.action.use-saved-credential\"")
                .contains("data-i18n=\"schedule.field.credential.hint\"");
        assertThat(propertyValue(zh, "schedule.pixiv.confirm.clear-proxy")).contains("Pixiv");
        assertThat(propertyValue(zh, "schedule.pixiv.confirm.clear-cookie")).contains("Cookie", "R-18");
        assertThat(propertyValue(en, "schedule.pixiv.confirm.clear-proxy")).contains("Pixiv");
        assertThat(propertyValue(en, "schedule.pixiv.confirm.clear-cookie"))
                .containsIgnoringCase("cookie")
                .contains("R-18");
    }

    @Test
    @DisplayName("计划宿主资源不硬编码来源会话字段或策略私有命名")
    void scheduleHostResourcesStaySourceNeutral() throws IOException {
        assertThat(readSource()).doesNotContainIgnoringCase(
                "PHPSESSID",
                "authorize-cookie",
                "revoke-cookie",
                "clear-cookie",
                "no-cookie",
                "schedule-overuse");
        assertThat(readLinkedBatchStyles()).doesNotContain("schedule-overuse");
    }

    private static String readSource() throws IOException {
        return readSources(RESOURCES);
    }

    private static String readSources(List<String> resources) throws IOException {
        StringBuilder source = new StringBuilder();
        for (String resource : resources) {
            source.append(readSource(resource)).append('\n');
        }
        return source.toString();
    }

    private static String readLinkedBatchStyles() throws IOException {
        Matcher matcher = BATCH_STYLESHEET.matcher(readSource("static/pixiv-batch.html"));
        StringBuilder source = new StringBuilder();
        int count = 0;
        while (matcher.find()) {
            source.append(readSource("static" + matcher.group(1))).append('\n');
            count++;
        }
        assertThat(count).as("下载页应加载 pixiv-batch 样式资源").isPositive();
        return source.toString();
    }

    private static String readSource(String resource) throws IOException {
        try (InputStream input = ScheduleUiContractTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertThat(input).as("测试 classpath 缺少 " + resource).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Properties readProperties(String resource) throws IOException {
        Properties properties = new Properties();
        properties.load(new StringReader(readSource(resource)));
        return properties;
    }

    private static String propertyValue(Properties properties, String key) {
        String value = properties.getProperty(key);
        assertThat(value).as(key).isNotBlank();
        return value;
    }
}
