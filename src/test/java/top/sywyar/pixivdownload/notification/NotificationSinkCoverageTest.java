package top.sywyar.pixivdownload.notification;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import top.sywyar.pixivdownload.config.RuntimeFiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 「成对 / 齐全维护」守护测试：遍历 {@link NotificationScenario} 全部常量 × <b>Spring 注入的</b>
 * {@code List<NotificationSink>}，对每个组合调 {@link NotificationSink#verifyRenderable}——任一介质缺失某场景的
 * 渲染资源（邮件模板 / subject i18n、推送 title / body i18n）即 loud-failure。
 *
 * <p>遍历<b>注入的</b> sink 列表（而非硬编码介质），使本测试<b>自动覆盖未来新增的介质</b>：新增一个
 * {@link NotificationSink} bean 后，它必须为<b>所有</b>现存场景实现 {@code verifyRenderable}，否则这里立即变红。
 *
 * <p><b>这条测试是 push-notifications.md 第 4 节铁律的机器保障——绝不可删除或放宽。</b>
 */
@SpringBootTest(properties = {
        "pixivdownload.config-dir=target/test-runtime/config",
        "pixivdownload.state-dir=target/test-runtime/state",
        "pixivdownload.data-dir=target/test-runtime/data",
        "setup.browser.auto-open=false"
})
@DisplayName("通知场景 × 介质 成对/齐全维护守护测试")
class NotificationSinkCoverageTest {

    static {
        System.setProperty(RuntimeFiles.CONFIG_DIR_PROPERTY, "target/test-runtime/config");
        System.setProperty(RuntimeFiles.STATE_DIR_PROPERTY, "target/test-runtime/state");
        System.setProperty(RuntimeFiles.DATA_DIR_PROPERTY, "target/test-runtime/data");
    }

    @AfterAll
    static void tearDownRuntimeDirs() {
        System.clearProperty(RuntimeFiles.CONFIG_DIR_PROPERTY);
        System.clearProperty(RuntimeFiles.STATE_DIR_PROPERTY);
        System.clearProperty(RuntimeFiles.DATA_DIR_PROPERTY);
    }

    @Autowired
    private List<NotificationSink> sinks;

    @Test
    @DisplayName("邮件与推送两种介质均被自动发现")
    void bothMediaDiscovered() {
        assertThat(sinks).isNotEmpty();
        assertThat(sinks).extracting(NotificationSink::medium).contains("mail", "push");
    }

    @Test
    @DisplayName("每个通知场景在每个介质上都有可渲染的资源，缺失即 loud-failure")
    void everyScenarioRenderableInEverySink() {
        assertThat(NotificationScenario.values()).isNotEmpty();
        for (NotificationScenario scenario : NotificationScenario.values()) {
            for (NotificationSink sink : sinks) {
                assertThatCode(() -> sink.verifyRenderable(scenario))
                        .as("介质 [%s] 缺少场景 [%s] 的渲染资源", sink.medium(), scenario.id())
                        .doesNotThrowAnyException();
            }
        }
    }
}
