package top.sywyar.pixivdownload.mail.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.common.AppInfo;
import top.sywyar.pixivdownload.common.NetworkUtils;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.mail.MailSenderSettings;
import top.sywyar.pixivdownload.mail.MailService;
import top.sywyar.pixivdownload.mail.template.MailTemplateRegistry;
import top.sywyar.pixivdownload.mail.template.RenderedMail;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * GUI 配置页"发送测试邮件"按钮对应的 REST 端点。
 * <p>
 * 路径 {@code /api/gui/mail-test} 由 {@code AuthFilter} 对 {@code /api/gui/**} 统一施加 "本地请求 + GUI token"
 * 双校验；这里再次显式调用 {@link NetworkUtils#isTrustedLocalRequest} 作为深度防御。
 * <p>
 * 接收 GUI 当前表单的 SMTP 设置 DTO（含密码，仅在 localhost 同进程内传递），调用
 * {@link MailService#sendTest} 发送 {@link MailTemplateRegistry#TEMPLATE_CONFIG_SUCCESS} 模板邮件，
 * 把成功 / 失败摘要原样回给 GUI；失败摘要绝不含密码（由 {@code MailService.safeMessage} 截断 + 异常链脱敏）。
 */
@RestController
@RequestMapping("/api/gui")
@RequiredArgsConstructor
@Slf4j
public class MailTestController {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final MailService mailService;
    private final MailTemplateRegistry templateRegistry;
    private final AppMessages messages;

    @PostMapping("/mail-test")
    public ResponseEntity<MailTestResponse> test(@RequestBody MailTestRequest body,
                                                 HttpServletRequest request) {
        if (!NetworkUtils.isTrustedLocalRequest(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (body == null) {
            return ResponseEntity.badRequest().body(MailTestResponse.fail(
                    messages.get("mail.error.settings-missing")));
        }

        Locale locale = LocaleContextHolder.getLocale();
        MailSenderSettings settings = body.toSenderSettings();

        try {
            RenderedMail rendered = templateRegistry.render(
                    MailTemplateRegistry.TEMPLATE_CONFIG_SUCCESS,
                    locale,
                    buildPlaceholders(settings, locale));
            mailService.sendTest(settings, rendered.subject(), rendered.htmlBody());
            return ResponseEntity.ok(MailTestResponse.ok());
        } catch (MailService.MailSendException e) {
            return ResponseEntity.ok(MailTestResponse.fail(e.getMessage()));
        } catch (IOException e) {
            log.warn(logMessage("mail.log.test.template-failed", e.getMessage()));
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(MailTestResponse.fail(messages.get(locale, "mail.error.template-failed")));
        }
    }

    private Map<String, String> buildPlaceholders(MailSenderSettings settings, Locale locale) {
        Map<String, String> placeholders = new LinkedHashMap<>();
        placeholders.put("app_name", AppInfo.NAME);
        placeholders.put("username", messages.get(locale, "mail.template.placeholder.administrator"));
        placeholders.put("smtp_host", settings.host() == null ? "" : settings.host());
        placeholders.put("time", LocalDateTime.now().format(TIME_FORMAT));
        return placeholders;
    }

    private String logMessage(String code, Object... args) {
        return messages.getForLog(code, args);
    }
}
