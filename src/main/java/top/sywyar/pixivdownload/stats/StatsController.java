package top.sywyar.pixivdownload.stats;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.i18n.AppMessages;

import java.util.Locale;
import java.util.Map;

/**
 * 统计仪表盘的只读聚合接口。路径 {@code /api/stats/**} 在 {@code AuthFilter} 中按 monitor
 * 语义保护——solo / multi 两种模式下都仅限已登录管理员访问（不在访客邀请白名单内）。
 */
@RestController
@RequestMapping("/api/stats")
@RequiredArgsConstructor
public class StatsController {

    private final StatsService statsService;
    private final AppMessages messages;

    @GetMapping("/dashboard")
    public StatsDto.Dashboard dashboard(
            @RequestParam(required = false, defaultValue = "0") int topAuthors,
            @RequestParam(required = false, defaultValue = "0") int topTags) {
        return statsService.dashboard(topAuthors, topTags);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException e, Locale locale) {
        String rawMessage = e.getMessage();
        String message = rawMessage == null || rawMessage.isBlank()
                ? messages.getOrDefault(locale, "error.request.param.invalid", "请求参数错误")
                : messages.getOrDefault(locale, rawMessage, rawMessage);
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }
}
