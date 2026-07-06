package top.sywyar.pixivdownload.core.notification;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.notification.NotificationConfigKeys;
import top.sywyar.pixivdownload.notification.NotificationScenario;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 通知<b>类型开关</b>配置，映射插件配置中的 {@code notification.scenario.<id>.enabled}
 * （{@code <id>} 为 {@link NotificationScenario#id()}），并兼容旧 {@code config.yaml} 同名键。
 * <p>
 * 某场景被关闭后，{@link NotificationService#notify} 会跳过该场景的<b>全部</b>介质（邮件 + 推送）——
 * 即「不发送这个通知类型的通知」。未在配置中出现的场景<b>默认视为启用</b>，因此新增场景或旧配置缺项时
 * 默认全部勾选 / 全部发送。
 * <p>
 * 字段使用 {@code volatile}，与 {@link top.sywyar.pixivdownload.push.PushConfig} /
 * {@link top.sywyar.pixivdownload.mail.MailConfig} 风格一致，便于热重载时安全地被多线程读取。
 */
@Data
@Component
@ConfigurationProperties(prefix = "notification")
public class NotificationConfig {

    /** @deprecated 过渡兼容门面；新代码使用 {@link NotificationConfigKeys#SCENARIO_PREFIX}。 */
    @Deprecated
    public static final String KEY_SCENARIO_PREFIX = NotificationConfigKeys.SCENARIO_PREFIX;
    /** @deprecated 过渡兼容门面；新代码使用 {@link NotificationConfigKeys#SCENARIO_ENABLED_SUFFIX}。 */
    @Deprecated
    public static final String KEY_SCENARIO_ENABLED_SUFFIX = NotificationConfigKeys.SCENARIO_ENABLED_SUFFIX;

    /** 各通知场景的开关。key = {@link NotificationScenario#id()}；缺省（未配置）默认启用。 */
    private final Map<String, ScenarioToggle> scenario = new ConcurrentHashMap<>();

    /** 给定场景是否启用对外通知；未配置该场景时默认启用（true）。 */
    public boolean isScenarioEnabled(String id) {
        ScenarioToggle toggle = scenario.get(id);
        return toggle == null || toggle.isEnabled();
    }

    /** 设置某场景开关（热重载用）；该场景尚无条目时按需创建。 */
    public void setScenarioEnabled(String id, boolean enabled) {
        scenario.computeIfAbsent(id, k -> new ScenarioToggle()).setEnabled(enabled);
    }

    /** @deprecated 过渡兼容门面；新代码使用 {@link NotificationConfigKeys#scenarioEnabledKey(String)}。 */
    @Deprecated
    public static String scenarioEnabledKey(String id) {
        return NotificationConfigKeys.scenarioEnabledKey(id);
    }

    /** 单个场景的开关条目。 */
    @Data
    public static class ScenarioToggle {
        /** 是否发送该场景的对外通知，默认启用。 */
        private volatile boolean enabled = true;
    }
}
