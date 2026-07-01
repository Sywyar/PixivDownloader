package top.sywyar.pixivdownload.mail.template;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.mail.TestMessageResolver;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("MailTemplateRegistry 单元测试")
class MailTemplateRegistryTest {

    private final MailTemplateRegistry registry = new MailTemplateRegistry(TestMessageResolver.INSTANCE);

    @Test
    @DisplayName("mail-config-success 模板可按中文 locale 渲染：subject 与正文都走 i18n")
    void chineseRenderShouldWork() throws IOException {
        RenderedMail rendered = registry.render(
                MailTemplateRegistry.TEMPLATE_CONFIG_SUCCESS,
                Locale.SIMPLIFIED_CHINESE,
                placeholders());

        assertThat(rendered.subject()).contains("邮件配置成功");
        assertThat(rendered.htmlBody())
                .contains("邮件配置成功")
                .contains("Pixiv 下载助手")
                .contains("关键信息")
                .contains("应用")
                .contains("SMTP 主机")
                .contains("发送时间")
                .contains("PixivDownloader")       // {{app_name}} 注入的运行时值
                .contains("smtp.example.com")       // {{smtp_host}} 注入的运行时值
                .contains("2026-05-27 12:34:56")    // {{time}} 注入的运行时值
                .contains("管理员")                 // 嵌套 {{username}} 通过 i18n 值二级替换
                .doesNotContain("{{")
                .doesNotContain("}}")
                .doesNotContain("PHPSESSID");
    }

    @Test
    @DisplayName("英文 locale 走 i18n 查表，使用英文文案，无需 _en.html")
    void englishRenderShouldUseI18nMessages() throws IOException {
        Map<String, String> values = placeholders();
        values.put("username", "administrator");

        RenderedMail rendered = registry.render(
                MailTemplateRegistry.TEMPLATE_CONFIG_SUCCESS,
                Locale.ENGLISH,
                values);

        assertThat(rendered.subject()).contains("Mail configuration successful");
        assertThat(rendered.htmlBody())
                .contains("Mail configuration successful")
                .contains("Pixiv Downloader")
                .contains("Details")
                .contains("Application")
                .contains("SMTP host")
                .contains("Sent at")
                .contains("administrator")
                .doesNotContain("{{")
                .doesNotContain("}}");
    }

    @Test
    @DisplayName("{{i18n:key}} 与 {{key}} 两类占位符分别解析，i18n 值里的 {{username}} 也会二级替换")
    void i18nPlaceholderShouldResolveNestedRuntimeKey() throws IOException {
        Map<String, String> values = placeholders();
        values.put("username", "操作员");

        RenderedMail rendered = registry.render(
                MailTemplateRegistry.TEMPLATE_CONFIG_SUCCESS,
                Locale.SIMPLIFIED_CHINESE,
                values);

        // greeting i18n value = "你好，{{username}}：" → 二级替换为 "你好，操作员："
        assertThat(rendered.htmlBody()).contains("你好，操作员：");
    }

    @Test
    @DisplayName("缺失运行时占位符兜底为空串，绝不外发裸 {{key}}")
    void missingPlaceholderShouldFallbackToEmpty() throws IOException {
        RenderedMail rendered = registry.render(
                MailTemplateRegistry.TEMPLATE_CONFIG_SUCCESS,
                Locale.SIMPLIFIED_CHINESE,
                Map.of()); // 故意全空

        assertThat(rendered.htmlBody()).doesNotContain("{{");
        assertThat(rendered.htmlBody()).doesNotContain("}}");
    }

    @Test
    @DisplayName("未知模板 id 抛 IllegalArgumentException")
    void unknownIdShouldThrow() {
        assertThatThrownBy(() -> registry.render("does-not-exist", Locale.ENGLISH, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does-not-exist");
    }

    @Test
    @DisplayName("模板登记表暴露 mail-config-success")
    void templatesShouldIncludeConfigSuccess() {
        assertThat(registry.templates()).containsKey(MailTemplateRegistry.TEMPLATE_CONFIG_SUCCESS);
    }

    @Test
    @DisplayName("三个调度器通知模板均已登记")
    void templatesShouldIncludeSchedulerNotifications() {
        assertThat(registry.templates())
                .containsKey(MailTemplateRegistry.TEMPLATE_OVERUSE_PAUSED)
                .containsKey(MailTemplateRegistry.TEMPLATE_AUTH_EXPIRED)
                .containsKey(MailTemplateRegistry.TEMPLATE_CIRCUIT_BREAKER);
    }

    @Test
    @DisplayName("pending-exhausted 已登记，中英渲染含作品信息且无裸 {{}}")
    void pendingExhaustedRenders() throws Exception {
        assertThat(registry.templates()).containsKey(MailTemplateRegistry.TEMPLATE_PENDING_EXHAUSTED);

        Map<String, String> ph = new LinkedHashMap<>();
        ph.put("task_name", "画师计划");
        ph.put("task_id", "9");
        ph.put("task_type", "画师新作");
        ph.put("task_trigger", "每 60 分钟");
        ph.put("work_id", "123456");
        ph.put("work_kind", "插画");
        ph.put("work_url", "https://www.pixiv.net/artworks/123456");
        ph.put("attempts", "5");
        ph.put("trigger_time", "2026-05-27 12:00:00");
        ph.put("next_run_time", "2026-05-27 13:00:00");
        ph.put("last_error_excerpt", "受限内容");

        RenderedMail zh = registry.render(MailTemplateRegistry.TEMPLATE_PENDING_EXHAUSTED,
                Locale.SIMPLIFIED_CHINESE, ph);
        assertThat(zh.subject()).contains("需人工处理");
        assertThat(zh.htmlBody())
                .contains("画师计划")
                .contains("123456")
                .contains("画师新作")                                 // {{task_type}}
                .contains("https://www.pixiv.net/artworks/123456")    // {{work_url}}
                .contains("受限内容")
                .doesNotContain("{{");

        RenderedMail en = registry.render(MailTemplateRegistry.TEMPLATE_PENDING_EXHAUSTED,
                Locale.US, ph);
        assertThat(en.subject().toLowerCase()).contains("manual");
        assertThat(en.htmlBody()).doesNotContain("{{");
    }

    @Test
    @DisplayName("overuse-paused 中英渲染：subject 走 i18n、运行期占位符全部替换、无裸 {{}}")
    void overusePausedRenders() throws Exception {
        Map<String, String> ph = new LinkedHashMap<>();
        ph.put("account_id", "12345");
        ph.put("tasks_count", "3");
        ph.put("tasks_list_html", "画师计划A（ID 1）<br>搜索计划B（ID 2）");
        ph.put("warning_time", "2026-05-27 12:00:00");
        ph.put("trigger_time", "2026-05-27 12:01:00");
        ph.put("warning_excerpt", "policies excerpt");

        RenderedMail zh = registry.render(MailTemplateRegistry.TEMPLATE_OVERUSE_PAUSED, Locale.SIMPLIFIED_CHINESE, ph);
        assertThat(zh.subject()).contains("过度访问");
        assertThat(zh.htmlBody())
                .contains("12345")
                .contains("画师计划A（ID 1）")   // {{tasks_list_html}} 逐条任务名/ID
                .contains("搜索计划B（ID 2）")
                .contains("policies excerpt")
                .doesNotContain("{{");

        RenderedMail en = registry.render(MailTemplateRegistry.TEMPLATE_OVERUSE_PAUSED, Locale.US, ph);
        assertThat(en.subject().toLowerCase()).contains("overuse");
        assertThat(en.htmlBody()).doesNotContain("{{");
    }

    @Test
    @DisplayName("circuit-breaker subject 含连续失败次数占位符替换")
    void circuitBreakerSubjectFillsFailures() throws Exception {
        Map<String, String> ph = new LinkedHashMap<>();
        ph.put("task_name", "测试任务");
        ph.put("task_id", "7");
        ph.put("task_type", "保存的搜索");
        ph.put("task_trigger", "Cron：0 0 * * * *");
        ph.put("consecutive_failures", "5");
        ph.put("trigger_time", "2026-05-27 12:00:00");
        ph.put("last_error_excerpt", "限制级需登录");

        RenderedMail zh = registry.render(MailTemplateRegistry.TEMPLATE_CIRCUIT_BREAKER, Locale.SIMPLIFIED_CHINESE, ph);
        assertThat(zh.subject()).contains("5");
        assertThat(zh.htmlBody())
                .contains("测试任务")
                .contains("保存的搜索")           // {{task_type}}
                .contains("Cron：0 0 * * * *")    // {{task_trigger}}
                .contains("限制级需登录")
                .contains("不会自动重试")          // 挂起任务展示「恢复方式」而非误导的下次运行时间
                .doesNotContain("{{");
    }

    @Test
    @DisplayName("auth-expired 渲染含任务信息、无裸占位符")
    void authExpiredRenders() throws Exception {
        Map<String, String> ph = new LinkedHashMap<>();
        ph.put("task_name", "画师计划");
        ph.put("task_id", "9");
        ph.put("task_type", "已关注用户的新作");
        ph.put("task_trigger", "每 30 分钟");
        ph.put("trigger_time", "2026-05-27 12:00:00");

        RenderedMail zh = registry.render(MailTemplateRegistry.TEMPLATE_AUTH_EXPIRED, Locale.SIMPLIFIED_CHINESE, ph);
        assertThat(zh.htmlBody())
                .contains("画师计划")
                .contains("已关注用户的新作")   // {{task_type}}
                .contains("每 30 分钟")          // {{task_trigger}}
                .contains("不会自动重试")        // 挂起任务展示「恢复方式」而非误导的下次运行时间
                .doesNotContain("{{");
        assertThat(zh.subject()).isNotBlank();
    }

    @Test
    @DisplayName("degraded-anonymous 中英渲染：含新下载数 / 运行时间 / 下次运行，无裸占位符")
    void degradedAnonymousRenders() throws Exception {
        assertThat(registry.templates()).containsKey(MailTemplateRegistry.TEMPLATE_DEGRADED_ANONYMOUS);

        Map<String, String> ph = new LinkedHashMap<>();
        ph.put("task_name", "搜索计划");
        ph.put("task_id", "12");
        ph.put("task_type", "保存的搜索");
        ph.put("task_trigger", "每 60 分钟");
        ph.put("completed", "8");
        ph.put("trigger_time", "2026-05-27 12:00:00");
        ph.put("next_run_time", "2026-05-27 13:00:00");

        RenderedMail zh = registry.render(MailTemplateRegistry.TEMPLATE_DEGRADED_ANONYMOUS,
                Locale.SIMPLIFIED_CHINESE, ph);
        assertThat(zh.subject()).contains("受限模式");
        assertThat(zh.htmlBody())
                .contains("搜索计划")
                .contains("保存的搜索")        // {{task_type}}
                .contains("本轮新下载")        // common.info-card.completed
                .contains("8")                 // {{completed}}
                .contains("2026-05-27 13:00:00") // {{next_run_time}}
                .doesNotContain("{{")
                .doesNotContain("PHPSESSID");

        RenderedMail en = registry.render(MailTemplateRegistry.TEMPLATE_DEGRADED_ANONYMOUS, Locale.US, ph);
        assertThat(en.subject().toLowerCase()).contains("restricted");
        assertThat(en.htmlBody()).doesNotContain("{{");
    }

    @Test
    @DisplayName("run-failed 中英渲染：note 区含失败原因摘要，无裸占位符")
    void runFailedRenders() throws Exception {
        assertThat(registry.templates()).containsKey(MailTemplateRegistry.TEMPLATE_RUN_FAILED);

        Map<String, String> ph = new LinkedHashMap<>();
        ph.put("task_name", "画师计划");
        ph.put("task_id", "9");
        ph.put("task_type", "画师新作");
        ph.put("task_trigger", "每 30 分钟");
        ph.put("trigger_time", "2026-05-27 12:00:00");
        ph.put("next_run_time", "2026-05-27 12:30:00");
        ph.put("last_error_excerpt", "Connection timed out");

        RenderedMail zh = registry.render(MailTemplateRegistry.TEMPLATE_RUN_FAILED, Locale.SIMPLIFIED_CHINESE, ph);
        assertThat(zh.subject()).contains("失败");
        assertThat(zh.htmlBody())
                .contains("画师计划")
                .contains("Connection timed out") // {{last_error_excerpt}}
                .doesNotContain("{{");

        RenderedMail en = registry.render(MailTemplateRegistry.TEMPLATE_RUN_FAILED, Locale.US, ph);
        assertThat(en.subject().toLowerCase()).contains("failed");
        assertThat(en.htmlBody()).doesNotContain("{{");
    }

    @Test
    @DisplayName("run-summary subject 含新下载数占位符替换，中英渲染无裸占位符")
    void runSummaryRenders() throws Exception {
        assertThat(registry.templates()).containsKey(MailTemplateRegistry.TEMPLATE_RUN_SUMMARY);

        Map<String, String> ph = new LinkedHashMap<>();
        ph.put("task_name", "系列计划");
        ph.put("task_id", "3");
        ph.put("task_type", "系列下载");
        ph.put("task_trigger", "Cron：0 0 * * * *");
        ph.put("completed", "5");
        ph.put("trigger_time", "2026-05-27 12:00:00");
        ph.put("next_run_time", "2026-05-27 18:00:00");

        RenderedMail zh = registry.render(MailTemplateRegistry.TEMPLATE_RUN_SUMMARY, Locale.SIMPLIFIED_CHINESE, ph);
        assertThat(zh.subject()).contains("5"); // {{completed}} 进入 subject
        assertThat(zh.htmlBody())
                .contains("系列计划")
                .contains("系列下载")
                .contains("5")
                .doesNotContain("{{");

        RenderedMail en = registry.render(MailTemplateRegistry.TEMPLATE_RUN_SUMMARY, Locale.US, ph);
        assertThat(en.subject()).contains("5");
        assertThat(en.htmlBody()).doesNotContain("{{");
    }

    private static Map<String, String> placeholders() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("app_name", "PixivDownloader");
        map.put("username", "管理员");
        map.put("smtp_host", "smtp.example.com");
        map.put("time", "2026-05-27 12:34:56");
        return map;
    }
}
