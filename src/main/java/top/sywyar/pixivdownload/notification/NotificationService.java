package top.sywyar.pixivdownload.notification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 统一通知协调器——业务侧的<b>唯一入口</b>。业务只需描述「发生了哪个 {@link NotificationScenario 场景}」，
 * 由本类按场景一次触发，扇出给每个 {@link NotificationSink 介质}（邮件 / 推送 / 未来新增）各自渲染并下发。
 *
 * <p>通过 Spring 的 {@code List<NotificationSink>} 注入自动发现全部介质（= 介质注册中心），本类<b>不感知</b>
 * 任何具体介质——<b>绝不</b>出现 {@code if mail} / {@code if push} 之类分支。加一种介质 = 加一个 bean，本类零改动
 * （开闭原则，对齐 {@code PushService} 的 {@code List<PushChannel>} 惯例）。
 *
 * <p>整体 best-effort：各介质 {@link NotificationSink#deliver} 已承诺不抛，这里仍对每个介质再兜一层
 * {@link RuntimeException}（双保险，参照 {@code PushService.dispatch}），确保单介质的意外异常绝不中断扇出，
 * 也绝不向业务调用方抛出。
 */
@Service
@Slf4j
public class NotificationService {

    /** 介质注册中心：Spring 自动发现的全部 sink。可能为空（未注册任何介质），属正常情况。 */
    private final List<NotificationSink> sinks;

    public NotificationService(List<NotificationSink> sinks) {
        this.sinks = sinks == null ? List.of() : sinks;
    }

    /**
     * 触发一个通知场景：遍历每个介质 {@link NotificationSink#deliver 下发}，逐个隔离。绝不抛异常。
     *
     * @param scenario     业务场景；为 {@code null} 时直接返回
     * @param locale       目标语言
     * @param placeholders 运行期占位符（各介质共用同一套键）
     */
    public void notify(NotificationScenario scenario, Locale locale, Map<String, String> placeholders) {
        if (scenario == null) {
            return;
        }
        for (NotificationSink sink : sinks) {
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
