package top.sywyar.pixivdownload.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.mail.MailService;
import top.sywyar.pixivdownload.mail.template.MailTemplateRegistry;
import top.sywyar.pixivdownload.mail.template.RenderedMail;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 邮件介质 {@link NotificationSink}：封装「{@link MailTemplateRegistry 渲染富 HTML} + {@link MailService 发信}」
 * 这套构建链。模板 id 直接取场景的 {@link NotificationScenario#id() canonical id}。
 *
 * <p>{@link #deliver} best-effort：渲染或发信失败仅记日志（{@code MailService.send} 本身也不抛）；
 * {@link #verifyRenderable} 通过实际 {@link MailTemplateRegistry#render 渲染}（空占位符）静态校验
 * 「模板已登记 + {@code mail/templates/{id}.html} 可加载 + {@code mail.template.{id}.subject} i18n 存在」三件套，
 * 中英各校验一次。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MailNotificationSink implements NotificationSink {

    /** verifyRenderable 校验的语言（与项目支持的 locale 对齐：中、英）。 */
    private static final List<Locale> VERIFY_LOCALES = List.of(Locale.SIMPLIFIED_CHINESE, Locale.US);

    private final MailTemplateRegistry templateRegistry;
    private final MailService mailService;

    @Override
    public String medium() {
        return "mail";
    }

    @Override
    public void deliver(NotificationScenario scenario, Locale locale, Map<String, String> placeholders) {
        try {
            RenderedMail mail = templateRegistry.render(scenario.id(), locale, placeholders);
            mailService.send(mail.subject(), mail.htmlBody());
        } catch (Exception e) {
            // 渲染异常（IO / 未登记）兜底；MailService.send 自身已 best-effort 不抛。
            log.error("Mail notification [{}] failed: {}", scenario.id(), e.getMessage());
        }
    }

    @Override
    public void verifyRenderable(NotificationScenario scenario) {
        String subjectKey = "mail.template." + scenario.id() + ".subject";
        for (Locale locale : VERIFY_LOCALES) {
            RenderedMail mail;
            try {
                // render 内部：未登记 → IllegalArgumentException；HTML 缺失 → IOException；subject 走 i18n。
                mail = templateRegistry.render(scenario.id(), locale, Map.of());
            } catch (IOException e) {
                throw new IllegalStateException(
                        "mail template HTML not loadable for scenario " + scenario.id(), e);
            }
            String subject = mail.subject();
            if (subject == null || subject.isBlank() || subject.equals(subjectKey)) {
                throw new IllegalStateException(
                        "mail subject i18n missing for scenario " + scenario.id() + " @ " + locale);
            }
        }
    }
}
