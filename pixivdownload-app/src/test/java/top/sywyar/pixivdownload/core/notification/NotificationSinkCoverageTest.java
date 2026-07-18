package top.sywyar.pixivdownload.core.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.notification.NotificationScenario;
import top.sywyar.pixivdownload.notification.NotificationSink;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 通知核心壳边界守卫：mail / push 介质已外置为插件，app 默认 Spring 上下文不再携带
 * {@link NotificationSink} 实现。各介质对 {@link NotificationScenario} 的模板 / 文案齐全性由对应插件模块测试守护。
 */
@DisplayName("通知 sink 外置化边界")
class NotificationSinkCoverageTest {

    @Test
    @DisplayName("默认无外置插件时通知协调器没有介质也不抛错")
    void noSinksWithoutPluginsIsAllowed() {
        NotificationSinkRegistry registry = new NotificationSinkRegistry(List.of());
        NotificationService service = new NotificationService(
                registry, new NotificationConfig(), new AppMessages(new StaticMessageSource()));

        assertThat(registry.sinks()).isEmpty();
        assertThatCode(() -> service.notify(NotificationScenario.RUN_SUMMARY,
                Locale.SIMPLIFIED_CHINESE, Map.of())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("通知场景枚举仍是核心稳定契约")
    void scenariosRemainCoreContracts() {
        assertThat(NotificationScenario.values())
                .isNotEmpty()
                .extracting(NotificationScenario::id)
                .doesNotHaveDuplicates()
                .allSatisfy(id -> assertThat(id).isNotBlank());
    }
}
