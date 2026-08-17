package top.sywyar.pixivdownload.notification;

/**
 * 通知场景配置键的中性命名空间。发送介质插件只消费这些开关，不拥有它们。
 */
public final class NotificationConfigKeys {

    private NotificationConfigKeys() {
    }

    /** 通知场景开关的 key 前缀。 */
    public static final String SCENARIO_PREFIX = "notification.scenario.";
    /** 场景开关 key 的后缀。 */
    public static final String SCENARIO_ENABLED_SUFFIX = ".enabled";

    /**
     * 某场景对应的完整开关 key，例如 {@code notification.scenario.run-summary.enabled}。
     *
     * @param id 标识
     * @return 方法返回的字符串
     */
    public static String scenarioEnabledKey(String id) {
        return SCENARIO_PREFIX + id + SCENARIO_ENABLED_SUFFIX;
    }
}
