package top.sywyar.pixivdownload.notification;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.common.NetworkUtils;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.push.PushChannelSettings;
import top.sywyar.pixivdownload.push.PushMessage;
import top.sywyar.pixivdownload.push.PushMessageFactory;
import top.sywyar.pixivdownload.push.PushResult;
import top.sywyar.pixivdownload.push.PushService;
import top.sywyar.pixivdownload.push.controller.PushTestRequest;
import top.sywyar.pixivdownload.push.controller.PushTestResponse;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * GUI 推送配置页"发送所有消息模板"按钮对应的 REST 端点（与"测试此渠道"分工：后者只发一条
 * {@code push.test.message}，本端点把<b>每个 {@link NotificationScenario 通知场景}</b>的推送文案各发一条，
 * 便于在该渠道上预览各类通知的实际呈现）。
 * <p>
 * 放在 {@code notification} 包而非 {@code push.controller}：消息模板的事实源是
 * {@link NotificationScenario}，由 {@code notification → push} 单向依赖渲染 + 下发，保持 {@code push} 框架
 * 不反向依赖 {@code notification}。请求体复用 {@link PushTestRequest}（GUI 当前推送表单），响应复用
 * {@link PushTestResponse}（与"测试此渠道"同一前端解析）。
 * <p>
 * 路径 {@code /api/gui/push-test-all} 由 {@code AuthFilter} 对 {@code /api/gui/**} 统一施加"本地请求 + GUI token"
 * 双校验；这里再次显式校验作为深度防御。<b>不</b>检查推送总开关，便于保存前预览；密钥绝不回显。
 */
@RestController
@RequestMapping("/api/gui")
@RequiredArgsConstructor
public class NotificationPushTestController {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final PushService pushService;
    private final PushMessageFactory messageFactory;
    private final AppMessages messages;

    @PostMapping("/push-test-all")
    public ResponseEntity<PushTestResponse> testAll(@RequestBody PushTestRequest body,
                                                    HttpServletRequest request) {
        if (!NetworkUtils.isTrustedLocalRequest(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (body == null) {
            return ResponseEntity.ok(PushTestResponse.from(List.of()));
        }
        List<PushChannelSettings> settings = body.toEnabledSettings();
        if (settings.isEmpty()) {
            return ResponseEntity.ok(PushTestResponse.from(List.of()));
        }

        Locale locale = LocaleContextHolder.getLocale();
        Map<String, String> sample = buildSamplePlaceholders(locale);

        // 遍历全部场景 → 每个场景一条推送；自动覆盖未来新增场景（无需在此枚举类型）。
        List<PushResult> all = new ArrayList<>();
        for (NotificationScenario scenario : NotificationScenario.values()) {
            PushMessage message = messageFactory.render(scenario.id(), scenario.level(), locale, sample);
            all.addAll(pushService.test(settings, message));
        }
        return ResponseEntity.ok(PushTestResponse.from(all));
    }

    /**
     * 预览用示例占位符：复用邮件侧同名 {@code mail.template.sample.*} 文案（推送与邮件共用同一套占位符键），
     * 单一事实源、避免重复维护示例数据；多余 key 在渲染时被忽略。绝不含任何凭证。
     */
    private Map<String, String> buildSamplePlaceholders(Locale locale) {
        LocalDateTime now = LocalDateTime.now();
        Map<String, String> ph = new LinkedHashMap<>();
        ph.put("account_id", messages.get(locale, "mail.template.sample.account-id"));
        ph.put("tasks_count", messages.get(locale, "mail.template.sample.tasks-count"));
        ph.put("tasks_list_html", messages.get(locale, "mail.template.sample.tasks-list-html"));
        ph.put("tasks_list_md", messages.get(locale, "mail.template.sample.tasks-list-md"));
        ph.put("warning_time", now.minusMinutes(5).format(TIME_FORMAT));
        ph.put("trigger_time", now.format(TIME_FORMAT));
        ph.put("task_name", messages.get(locale, "mail.template.sample.task-name"));
        ph.put("task_id", messages.get(locale, "mail.template.sample.task-id"));
        ph.put("task_type", messages.get(locale, "mail.template.common.task-type.user-new"));
        ph.put("task_trigger", messages.get(locale, "mail.template.common.trigger.interval", 60));
        ph.put("next_run_time", now.plusMinutes(60).format(TIME_FORMAT));
        ph.put("completed", messages.get(locale, "mail.template.sample.completed"));
        ph.put("consecutive_failures", messages.get(locale, "mail.template.sample.consecutive-failures"));
        ph.put("last_error_excerpt", messages.get(locale, "mail.template.sample.last-error-excerpt"));
        ph.put("work_id", messages.get(locale, "mail.template.sample.work-id"));
        ph.put("work_kind", messages.get(locale, "mail.template.pending-exhausted.kind.illust"));
        ph.put("work_url", "https://www.pixiv.net/artworks/" + messages.get(locale, "mail.template.sample.work-id"));
        ph.put("attempts", messages.get(locale, "mail.template.sample.attempts"));
        return ph;
    }
}
