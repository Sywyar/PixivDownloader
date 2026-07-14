package top.sywyar.pixivdownload.schedule;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("计划任务前端状态与 pending 操作契约")
class ScheduleUiContractTest {

    private static final String RESOURCE = "static/pixiv-batch/modes/schedule.js";

    @Test
    @DisplayName("四类运行阻断原因有独立状态灯且只参与立即运行禁用")
    void operationalSuspensionsHaveDistinctLightsWithoutBlockingEditOrDelete() throws IOException {
        String source = readSource();

        assertThat(source)
                .contains("t.suspendReason === 'SOURCE_UNAVAILABLE'")
                .contains("t.suspendReason === 'EXECUTOR_UNAVAILABLE'")
                .contains("t.suspendReason === 'QUIESCED'")
                .contains("t.suspendReason === 'MIGRATION_ERROR'")
                .contains("code === 'SOURCE_UNAVAILABLE'")
                .contains("code === 'EXECUTOR_UNAVAILABLE'")
                .contains("code === 'QUIESCED'")
                .contains("code === 'MIGRATION_ERROR'")
                .contains("const suspended = !!t.suspendReason")
                .contains("const automaticSuspension = ['SOURCE_UNAVAILABLE', 'EXECUTOR_UNAVAILABLE', 'QUIESCED']")
                .contains("const manualRecoveryRequired = suspended && !automaticSuspension")
                .contains("schedule.disabled.run-capability")
                .contains("schedule.meta.next-capability")
                .contains("const runAttr = (t.enabled && !busy && !suspended)")
                .contains("const sourceEditable = t.sourceAvailable !== false")
                .contains("${editAttr} onclick=\"startEditScheduleTask(${t.id})\"")
                .contains("${busyAttr} onclick=\"deleteScheduleTask(${t.id})\"");
    }

    @Test
    @DisplayName("pending 清除以 data 绑定和 JSON 正文传递不透明复合身份")
    void pendingClearUsesDataBindingAndJsonBody() throws IOException {
        String source = readSource();

        assertThat(source)
                .contains("data-schedule-pending-clear")
                .contains("button.dataset.workType, button.dataset.workId")
                .contains("const workType = scheduleKindLabel(p.workType)")
                .contains("{workType, workId: p.workId, attempts: p.attempts, manual}")
                .contains("typeof detail.legacyReason === 'string'")
                .contains("headers: {'Content-Type': 'application/json'}")
                .contains("body: JSON.stringify({workType, workId})")
                .doesNotContain("onclick=\"clearPendingItem(")
                .doesNotContain("/pending/${encodeURIComponent(");
    }

    @Test
    @DisplayName("持久化中断结果直接驱动重新排期状态灯")
    void interruptedOutcomeDrivesRecoveryLight() throws IOException {
        String source = readSource();

        assertThat(source)
                .contains("t.lastOutcome === 'INTERRUPTED'")
                .doesNotContain("if (t.runStartedTime != null)");
    }

    @Test
    @DisplayName("宿主不解释来源定义且来源模块缺席时只读展示持久化摘要")
    void sourceFrontendUsesScopedHandlersAndPresentationFallback() throws IOException {
        String source = readSource();
        String runtime = readSource("static/pixiv-batch/batch-schedule-sources.js");
        String pixivModule = readSource("static/pixiv-batch/pixiv-schedule-sources.js");

        assertThat(source)
                .contains("runtime.captureForMode(state.mode, scheduleSourceContext())")
                .contains("runtime.restoreTask(task, scheduleSourceContext())")
                .contains("presentationFallbackSections(t)")
                .contains("task.presentation")
                .doesNotContain("USER_NEW")
                .doesNotContain("USER_REQUEST")
                .doesNotContain("MY_BOOKMARKS")
                .doesNotContain("FOLLOW_LATEST")
                .doesNotContain("COLLECTION");
        assertThat(runtime)
                .contains("registerModule")
                .contains("activationSequence")
                .contains("controller.abort()")
                .contains("schedule source contribution is not declared by this module");
        assertThat(pixivModule)
                .contains("user-new")
                .contains("user-request")
                .contains("my-bookmarks")
                .contains("follow-latest")
                .contains("collection")
                .doesNotContain("innerHTML");
    }

    @Test
    @DisplayName("抓取上限由来源贡献受控文案键且宿主默认不泄露来源名称")
    void fetchLimitPresentationUsesControlledSourceKeysAndNeutralDefaults() throws IOException {
        String source = readSource();
        String runtime = readSource("static/pixiv-batch/batch-schedule-sources.js");
        String core = readSource("static/pixiv-batch/batch-core.js");
        String pixivModule = readSource("static/pixiv-batch/pixiv-schedule-sources.js");
        String page = readSource("static/pixiv-batch.html");
        String zh = readSource("i18n/web/batch.properties");
        String en = readSource("i18n/web/batch_en.properties");

        assertThat(source)
                .contains("scheduleFetchLimitI18nKey")
                .contains("snap.fetchLimitPresentation")
                .contains("preview.fetchLimitPresentation");
        assertThat(runtime)
                .contains("normalizeFetchLimitPresentation")
                .contains("typeof raw.then === 'function'")
                .contains("watermarkHintKey")
                .contains("fullFetchConfirmKey");
        assertThat(core).contains("updateSaveScheduleCardVisibility()");
        assertThat(pixivModule)
                .contains("schedule.pixiv.fetch-limit.hint.watermark")
                .contains("schedule.pixiv.fetch-limit.hint.per-run")
                .contains("schedule.pixiv.confirm.full-fetch")
                .contains("schedule.pixiv.confirm.clear-proxy")
                .contains("schedule.pixiv.confirm.clear-cookie");
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
        String source = readSource();
        String page = readSource("static/pixiv-batch.html");
        String zh = readSource("i18n/web/batch.properties");
        String en = readSource("i18n/web/batch_en.properties");
        List<String> genericKeys = List.of(
                "schedule.status.saved-authorized",
                "schedule.status.saved-no-cookie",
                "schedule.status.saved-overrides",
                "schedule.status.saved-override-failed",
                "schedule.status.override-saved",
                "schedule.status.override-unchanged",
                "schedule.confirm.delete",
                "schedule.confirm.clear-proxy",
                "schedule.confirm.clear-cookie",
                "schedule.error.no-cookie",
                "schedule.error.revoke-cookie",
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
        assertThat(source)
                .contains("scheduleSourcePresentationI18nKey")
                .contains("Promise.resolve(actions).catch(() => {})")
                .contains("clearProxyConfirmI18nKey")
                .contains("clearCredentialConfirmI18nKey");
        assertThat(propertyValue(zh, "schedule.pixiv.confirm.clear-proxy")).contains("Pixiv");
        assertThat(propertyValue(zh, "schedule.pixiv.confirm.clear-cookie")).contains("Cookie", "R-18");
        assertThat(propertyValue(en, "schedule.pixiv.confirm.clear-proxy")).contains("Pixiv");
        assertThat(propertyValue(en, "schedule.pixiv.confirm.clear-cookie"))
                .containsIgnoringCase("cookie")
                .contains("R-18");
    }

    private static String readSource() throws IOException {
        return readSource(RESOURCE);
    }

    private static String readSource(String resource) throws IOException {
        try (InputStream input = ScheduleUiContractTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertThat(input).as("测试 classpath 缺少 " + resource).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String propertyValue(String source, String key) {
        return source.lines()
                .filter(line -> line.startsWith(key + "="))
                .map(line -> line.substring(key.length() + 1))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("缺少 i18n key: " + key));
    }
}
