package top.sywyar.pixivdownload.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.push.PushConfig;
import top.sywyar.pixivdownload.push.PushDispatcher;
import top.sywyar.pixivdownload.push.PushLevel;
import top.sywyar.pixivdownload.push.PushMessage;
import top.sywyar.pixivdownload.push.PushMessageFactory;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 推送介质 {@link NotificationSink}：封装「{@link PushMessageFactory 渲染轻量 Markdown} +
 * {@link PushDispatcher 多通道下发}」这套构建链。消息 id / 严重程度取自场景的
 * {@link NotificationScenario#id()} / {@link NotificationScenario#level()}。
 *
 * <p>{@link #deliver} best-effort：渲染异常兜底（{@code PushDispatcher.push} 本身也不抛、各通道失败已隔离）；
 * {@link #verifyRenderable} 通过实际 {@link PushMessageFactory#render 渲染}（空占位符）静态校验
 * {@code push.message.{id}.title} 与 {@code .body} 在中、英 bundle 均存在（缺失时 i18n 回退为 key 本身，据此判定）。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PushNotificationSink implements NotificationSink {

    /** verifyRenderable 校验的语言（与项目支持的 locale 对齐：中、英）。 */
    private static final List<Locale> VERIFY_LOCALES = List.of(Locale.SIMPLIFIED_CHINESE, Locale.US);

    private final PushConfig pushConfig;
    private final PushMessageFactory messageFactory;
    private final PushDispatcher pushService;

    @Override
    public String medium() {
        return "push";
    }

    @Override
    public void deliver(NotificationScenario scenario, Locale locale, Map<String, String> placeholders) {
        if (!pushConfig.isEnabled()) {
            return;
        }
        try {
            PushMessage message = messageFactory.render(
                    scenario.id(), PushLevel.from(scenario.level()), locale, placeholders);
            pushService.push(message);
        } catch (RuntimeException e) {
            // PushService.push 已 best-effort 不抛；这里兜住渲染期的意外异常。
            log.error("Push notification [{}] failed: {}", scenario.id(), e.getClass().getSimpleName());
        }
    }

    @Override
    public void verifyRenderable(NotificationScenario scenario) {
        String titleKey = "push.message." + scenario.id() + ".title";
        String bodyKey = "push.message." + scenario.id() + ".body";
        PushLevel level = PushLevel.from(scenario.level());
        for (Locale locale : VERIFY_LOCALES) {
            PushMessage message = messageFactory.render(scenario.id(), level, locale, Map.of());
            if (isMissing(message.title(), titleKey)) {
                throw new IllegalStateException(
                        "push title i18n missing: " + titleKey + " @ " + locale);
            }
            if (isMissing(message.content(), bodyKey)) {
                throw new IllegalStateException(
                        "push body i18n missing: " + bodyKey + " @ " + locale);
            }
        }
    }

    /** i18n 缺失时 {@code MessageResolver} 回退为 key 本身：空 / 等于 key 即视为缺失。 */
    private static boolean isMissing(String value, String key) {
        return value == null || value.isBlank() || value.equals(key);
    }
}
