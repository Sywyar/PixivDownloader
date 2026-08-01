package top.sywyar.pixivdownload.plugin.api.schedule.work;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("计划作品通知展示契约")
class ScheduledWorkNotificationPresentationTest {

    @Test
    @DisplayName("展示名称成对规范化且空投影不携带任何字段")
    void displayNameTokensArePairedAndNormalized() {
        ScheduledWorkNotificationPresentation presentation =
                new ScheduledWorkNotificationPresentation(
                        " workbench ",
                        " schedule.work.illust ",
                        " https://www.example.invalid/works/42 ");

        assertThat(presentation.displayNamespace()).isEqualTo("workbench");
        assertThat(presentation.displayNameKey()).isEqualTo("schedule.work.illust");
        assertThat(presentation.referenceUrl())
                .isEqualTo("https://www.example.invalid/works/42");
        assertThat(ScheduledWorkNotificationPresentation.empty())
                .isEqualTo(new ScheduledWorkNotificationPresentation(" ", null, " "));

        assertThatThrownBy(() -> new ScheduledWorkNotificationPresentation(
                "workbench", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScheduledWorkNotificationPresentation(
                null, "schedule.work.illust", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("展示 token 接受精确上限并拒绝路径控制符与越界内容")
    void displayTokensAreBounded() {
        String namespace = "a" + "b".repeat(
                ScheduledWorkNotificationPresentation.MAX_DISPLAY_NAMESPACE_BYTES - 1);
        String key = "A" + "b".repeat(
                ScheduledWorkNotificationPresentation.MAX_DISPLAY_NAME_KEY_BYTES - 1);

        ScheduledWorkNotificationPresentation maximum =
                new ScheduledWorkNotificationPresentation(namespace, key, null);

        assertThat(maximum.displayNamespace()).hasSize(
                ScheduledWorkNotificationPresentation.MAX_DISPLAY_NAMESPACE_BYTES);
        assertThat(maximum.displayNameKey()).hasSize(
                ScheduledWorkNotificationPresentation.MAX_DISPLAY_NAME_KEY_BYTES);

        for (String invalidNamespace : List.of(
                "Workbench",
                "workbench/path",
                "workbench\n",
                "a" + "b".repeat(
                        ScheduledWorkNotificationPresentation.MAX_DISPLAY_NAMESPACE_BYTES))) {
            assertThatThrownBy(() -> new ScheduledWorkNotificationPresentation(
                    invalidNamespace, "schedule.work", null))
                    .as("非法 namespace: %s", invalidNamespace)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (String invalidKey : List.of(
                ".schedule.work",
                "schedule/work",
                "schedule.work\n",
                "A" + "b".repeat(
                        ScheduledWorkNotificationPresentation.MAX_DISPLAY_NAME_KEY_BYTES))) {
            assertThatThrownBy(() -> new ScheduledWorkNotificationPresentation(
                    "workbench", invalidKey, null))
                    .as("非法 i18n key: %s", invalidKey)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("引用地址只接受无用户信息和控制字符的有界 HTTPS 绝对地址")
    void referenceUrlRequiresSafeBoundedHttpsUrl() {
        String prefix = "https://example.invalid/";
        int prefixBytes = prefix.getBytes(StandardCharsets.UTF_8).length;
        String maximumUrl = prefix + "a".repeat(
                ScheduledWorkNotificationPresentation.MAX_REFERENCE_URL_BYTES - prefixBytes);

        assertThat(new ScheduledWorkNotificationPresentation(
                null, null, maximumUrl).referenceUrl()).isEqualTo(maximumUrl);
        assertThat(new ScheduledWorkNotificationPresentation(
                null,
                null,
                "https://example.invalid/work/42?locale=zh-CN#detail").referenceUrl())
                .isEqualTo("https://example.invalid/work/42?locale=zh-CN#detail");

        for (String invalidUrl : List.of(
                "http://example.invalid/work/42",
                "/work/42",
                "https:/work/42",
                "https://user:password@example.invalid/work/42",
                "https://example.invalid/work/\n42",
                "https://example.invalid/work/%00",
                "https://example.invalid/work/%FF",
                maximumUrl + "a")) {
            assertThatThrownBy(() -> new ScheduledWorkNotificationPresentation(
                    null, null, invalidUrl))
                    .as("非法引用地址: %s", invalidUrl)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("引用地址拒绝明文及百分号编码的通用凭证材料")
    void referenceUrlRejectsCredentialMaterial() {
        for (String unsafeUrl : List.of(
                "https://example.invalid/work/42?access_token=opaque-value",
                "https://example.invalid/work/42?access%5Ftoken=opaque-value",
                "https://example.invalid/work/42?next=Cookie%3A%20opaque-value",
                "https://example.invalid/work/42?next=Bearer%20abcdef1",
                "https://example.invalid/work/42?next=token%3Dopaque-value",
                "https://example.invalid/work/42?next=token%253Dopaque-value",
                "https://example.invalid/work/42?next=token%25253Dopaque-value",
                "https://example.invalid/work/42?access%255Ftoken=opaque-value",
                "https://example.invalid/work/42?cookiePresent=opaque-value",
                "https://example.invalid/token%3Dopaque-value",
                "https://example.invalid/token%253Dopaque-value",
                "https://example.invalid/work/%2500")) {
            assertThatThrownBy(() -> new ScheduledWorkNotificationPresentation(
                    null, null, unsafeUrl))
                    .as("含凭证材料的引用地址: %s", unsafeUrl)
                    .isInstanceOf(IllegalArgumentException.class);
        }

        assertThat(new ScheduledWorkNotificationPresentation(
                null,
                null,
                "https://example.invalid/work/42?page=2&locale=zh-CN"
                        + "&cookiePresent=false&tokenCount=0&percent=100%25"
                        + "&cookie%2550resent=%2566alse&tokenCount=%2530")
                .referenceUrl())
                .endsWith("cookie%2550resent=%2566alse&tokenCount=%2530");
    }

    @Test
    @DisplayName("作品执行器默认不贡献通知展示并保持兼容默认方法")
    void workExecutorDefaultsToEmptyNotificationPresentation() throws Exception {
        ScheduledWorkExecutor executor = new ScheduledWorkExecutor() {
            @Override
            public String workType() {
                return "fixture:work";
            }

            @Override
            public ScheduledWorkResult execute(
                    ScheduledWork work,
                    ScheduledWorkContext context) throws ScheduledExecutionException {
                return ScheduledWorkResult.completed();
            }
        };
        ScheduledWork work = new ScheduledWork(
                new ScheduledWorkKey("fixture:work", "opaque-1"),
                "fixture.work",
                1,
                "{}",
                ScheduledWorkPresentation.empty(),
                List.of());

        assertThat(executor.notificationPresentation(work))
                .isEqualTo(ScheduledWorkNotificationPresentation.empty());
        assertThat(ScheduledWorkExecutor.class
                .getMethod("notificationPresentation", ScheduledWork.class)
                .isDefault()).isTrue();
    }
}
