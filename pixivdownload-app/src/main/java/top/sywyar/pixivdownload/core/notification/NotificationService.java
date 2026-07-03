package top.sywyar.pixivdownload.core.notification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.notification.NotificationScenario;
import top.sywyar.pixivdownload.notification.NotificationSink;

import java.util.Locale;
import java.util.Map;

/**
 * 统一通知协调器——业务侧的<b>唯一入口</b>。业务只需描述「发生了哪个 {@link NotificationScenario 场景}」，
 * 由本类按场景一次触发，扇出给每个 {@link NotificationSink 介质}（邮件 / 推送 / 未来新增）各自渲染并下发。
 *
 * <p>通过 {@link NotificationSinkRegistry} 读取活动插件贡献的介质，本类<b>不感知</b>
 * 任何具体介质——<b>绝不</b>出现 {@code if mail} / {@code if push} 之类分支。
 *
 * <p>整体 best-effort：各介质 {@link NotificationSink#deliver} 已承诺不抛，这里仍对每个介质再兜一层
 * {@link RuntimeException}（双保险，参照 {@code PushService.dispatch}），确保单介质的意外异常绝不中断扇出，
 * 也绝不向业务调用方抛出。
 *
 * <p>场景级开关：{@link NotificationConfig} 关闭某场景后，本类直接跳过该场景的<b>全部</b>介质
 * （邮件与推送都不发）。未配置的场景默认启用。
 */
@Service
@Slf4j
public class NotificationService {

    /** 介质注册中心：活动插件贡献的全部 sink。可能为空（未安装通知插件），属正常情况。 */
    private final NotificationSinkRegistry sinkRegistry;

    /** 通知类型开关：决定某场景是否对外发送（关闭则跳过全部介质）。 */
    private final NotificationConfig notificationConfig;

    public NotificationService(NotificationSinkRegistry sinkRegistry, NotificationConfig notificationConfig) {
        this.sinkRegistry = sinkRegistry;
        this.notificationConfig = notificationConfig;
    }

    /**
     * 触发一个通知场景：遍历每个介质 {@link NotificationSink#deliver 下发}，逐个隔离。绝不抛异常。
     * 若该场景被 {@link NotificationConfig} 关闭，则跳过全部介质、不发送任何通知。
     *
     * @param scenario     业务场景；为 {@code null} 时直接返回
     * @param locale       目标语言
     * @param placeholders 运行期占位符（各介质共用同一套键）
     */
    public void notify(NotificationScenario scenario, Locale locale, Map<String, String> placeholders) {
        if (scenario == null) {
            return;
        }
        if (notificationConfig != null && !notificationConfig.isScenarioEnabled(scenario.id())) {
            log.debug("Notification scenario [{}] disabled by config; skipping all sinks.", scenario.id());
            return;
        }
        for (NotificationSink sink : sinkRegistry.sinks()) {
            try {
                sink.deliver(scenario, locale, placeholders);
            } catch (RuntimeException e) {
                // deliver 已是 best-effort，这里是双保险：单介质意外异常不影响其它介质。
                log.warn("Notification sink [{}] failed for scenario [{}]: {}",
                        sink.medium(), scenario.id(), e.getClass().getSimpleName());
            }
        }
    }
}
