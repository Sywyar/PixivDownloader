package top.sywyar.pixivdownload.notification;

import java.util.Locale;
import java.util.Map;

/**
 * 将中性业务通知场景交给宿主活动介质的稳定派发端口。
 *
 * <p>实现负责场景开关、活动介质选择和逐介质故障隔离；介质不可用或派发失败不得影响业务调用方。
 */
@FunctionalInterface
public interface NotificationDispatcher {

    void notify(NotificationScenario scenario, Locale locale, Map<String, String> placeholders);
}
