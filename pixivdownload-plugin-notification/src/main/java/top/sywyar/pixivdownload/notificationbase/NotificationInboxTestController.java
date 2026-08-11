package top.sywyar.pixivdownload.notificationbase;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.notification.NotificationScenario;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;
import top.sywyar.pixivdownload.web.LocalRequestTrust;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** GUI 通知配置页的站内信预览端点；外层与控制器内层均执行 GUI 本地请求校验。 */
@PluginManagedBean
@RestController
@RequestMapping("/api/gui")
public class NotificationInboxTestController {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final InboxNotificationSink sink;

    public NotificationInboxTestController(InboxNotificationSink sink) {
        this.sink = sink;
    }

    @PostMapping("/notification-inbox-test")
    public ResponseEntity<InboxTestResponse> test(HttpServletRequest request) {
        if (!trustedLocalRequest(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        NotificationScenario scenario = NotificationScenario.RUN_SUMMARY;
        try {
            sink.deliverForTest(scenario, LocaleContextHolder.getLocale(), samplePlaceholders());
            return ResponseEntity.ok(InboxTestResponse.success(1));
        } catch (RuntimeException exception) {
            return ResponseEntity.internalServerError().body(InboxTestResponse.failed(List.of(scenario.id())));
        }
    }

    @PostMapping("/notification-inbox-test-all")
    public ResponseEntity<InboxTestResponse> testAll(HttpServletRequest request) {
        if (!trustedLocalRequest(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        Locale locale = LocaleContextHolder.getLocale();
        Map<String, String> placeholders = samplePlaceholders();
        List<String> failures = new ArrayList<>();
        for (NotificationScenario scenario : NotificationScenario.values()) {
            try {
                sink.deliverForTest(scenario, locale, placeholders);
            } catch (RuntimeException exception) {
                failures.add(scenario.id());
            }
        }
        return ResponseEntity.ok(InboxTestResponse.of(NotificationScenario.values().length, failures));
    }

    private static Map<String, String> samplePlaceholders() {
        LocalDateTime now = LocalDateTime.now();
        Map<String, String> placeholders = new LinkedHashMap<>();
        placeholders.put("account_id", "12345678");
        placeholders.put("tasks_count", "2");
        placeholders.put("tasks_list_html", "<ul><li>#1</li><li>#2</li></ul>");
        placeholders.put("tasks_list_md", "- #1\n- #2");
        placeholders.put("warning_time", now.minusMinutes(5).format(TIME_FORMAT));
        placeholders.put("trigger_time", now.format(TIME_FORMAT));
        placeholders.put("task_name", "PixivDownloader");
        placeholders.put("task_id", "1");
        placeholders.put("task_type", "Pixiv");
        placeholders.put("task_trigger", "0 0 * * *");
        placeholders.put("next_run_time", now.plusHours(1).format(TIME_FORMAT));
        placeholders.put("completed", "3");
        placeholders.put("consecutive_failures", "3");
        placeholders.put("last_error_excerpt", "HTTP 503");
        placeholders.put("work_id", "12345678");
        placeholders.put("work_kind", "illust");
        placeholders.put("work_url", "https://www.pixiv.net/artworks/12345678");
        placeholders.put("attempts", "3");
        return Map.copyOf(placeholders);
    }

    private static boolean trustedLocalRequest(HttpServletRequest request) {
        return request != null && LocalRequestTrust.isTrustedLocalRequest(
                request.getRemoteAddr(),
                request.getHeader("Host"),
                request.getHeader("X-Forwarded-For"),
                request.getHeader("X-Real-IP"),
                request.getHeader("Forwarded"),
                request.getHeader("Origin"));
    }

    public record InboxTestResponse(boolean success, int total, int succeeded, List<Failure> failures) {

        public InboxTestResponse {
            failures = List.copyOf(failures);
        }

        static InboxTestResponse success(int total) {
            return new InboxTestResponse(true, total, total, List.of());
        }

        static InboxTestResponse failed(List<String> scenarioIds) {
            return of(scenarioIds.size(), scenarioIds);
        }

        static InboxTestResponse of(int total, List<String> scenarioIds) {
            List<Failure> failures = scenarioIds.stream().map(Failure::new).toList();
            return new InboxTestResponse(failures.isEmpty(), total, total - failures.size(), failures);
        }
    }

    public record Failure(String scenarioId) {
    }
}
