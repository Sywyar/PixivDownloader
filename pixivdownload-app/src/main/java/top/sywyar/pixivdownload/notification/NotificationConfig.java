package top.sywyar.pixivdownload.notification;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 通知<b>类型开关</b>配置，映射 config.yaml 中的 {@code notification.scenario.<id>.enabled}
 * （{@code <id>} 为 {@link NotificationScenario#id()}）。
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

    /** config.yaml 中场景开关的 key 前缀（首次安装 / 模板生成 / 测试代码复用）。 */
    public static final String KEY_SCENARIO_PREFIX = "notification.scenario.";
    /** 场景开关 key 的后缀。 */
    public static final String KEY_SCENARIO_ENABLED_SUFFIX = ".enabled";

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

    /** 某场景在 config.yaml 中对应的开关 key，例如 {@code notification.scenario.run-summary.enabled}。 */
    public static String scenarioEnabledKey(String id) {
        return KEY_SCENARIO_PREFIX + id + KEY_SCENARIO_ENABLED_SUFFIX;
    }

    /** 单个场景的开关条目。 */
    @Data
    public static class ScenarioToggle {
        /** 是否发送该场景的对外通知，默认启用。 */
        private volatile boolean enabled = true;
    }
}
