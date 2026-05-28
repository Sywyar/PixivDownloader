package top.sywyar.pixivdownload.mail.template;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("MailTemplateRegistry 单元测试")
class MailTemplateRegistryTest {

    private final AppMessages messages = TestI18nBeans.appMessages();
    private final MailTemplateRegistry registry = new MailTemplateRegistry(messages);

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
        ph.put("work_id", "123456");
        ph.put("work_kind", "插画");
        ph.put("attempts", "5");
        ph.put("trigger_time", "2026-05-27 12:00:00");
        ph.put("last_error_excerpt", "受限内容");

        RenderedMail zh = registry.render(MailTemplateRegistry.TEMPLATE_PENDING_EXHAUSTED,
                Locale.SIMPLIFIED_CHINESE, ph);
        assertThat(zh.subject()).contains("需人工处理");
        assertThat(zh.htmlBody())
                .contains("画师计划")
                .contains("123456")
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
        ph.put("warning_time", "2026-05-27 12:00:00");
        ph.put("trigger_time", "2026-05-27 12:01:00");
        ph.put("warning_excerpt", "policies excerpt");

        RenderedMail zh = registry.render(MailTemplateRegistry.TEMPLATE_OVERUSE_PAUSED, Locale.SIMPLIFIED_CHINESE, ph);
        assertThat(zh.subject()).contains("过度访问");
        assertThat(zh.htmlBody()).contains("12345").contains("policies excerpt").doesNotContain("{{");

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
        ph.put("consecutive_failures", "5");
        ph.put("trigger_time", "2026-05-27 12:00:00");
        ph.put("last_error_excerpt", "限制级需登录");

        RenderedMail zh = registry.render(MailTemplateRegistry.TEMPLATE_CIRCUIT_BREAKER, Locale.SIMPLIFIED_CHINESE, ph);
        assertThat(zh.subject()).contains("5");
        assertThat(zh.htmlBody()).contains("测试任务").contains("限制级需登录").doesNotContain("{{");
    }

    @Test
    @DisplayName("auth-expired 渲染含任务信息、无裸占位符")
    void authExpiredRenders() throws Exception {
        Map<String, String> ph = new LinkedHashMap<>();
        ph.put("task_name", "画师计划");
        ph.put("task_id", "9");
        ph.put("trigger_time", "2026-05-27 12:00:00");

        RenderedMail zh = registry.render(MailTemplateRegistry.TEMPLATE_AUTH_EXPIRED, Locale.SIMPLIFIED_CHINESE, ph);
        assertThat(zh.htmlBody()).contains("画师计划").doesNotContain("{{");
        assertThat(zh.subject()).isNotBlank();
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
