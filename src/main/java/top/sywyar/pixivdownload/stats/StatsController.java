package top.sywyar.pixivdownload.stats;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 统计仪表盘的只读聚合接口。路径 {@code /api/stats/**} 在 {@code AuthFilter} 中按 monitor
 * 语义保护——solo / multi 两种模式下都仅限已登录管理员访问（不在访客邀请白名单内）。
 */
@RestController
@RequestMapping("/api/stats")
@RequiredArgsConstructor
public class StatsController {

    private final StatsService statsService;

    @GetMapping("/dashboard")
    public StatsDto.Dashboard dashboard(
            @RequestParam(required = false, defaultValue = "0") int topAuthors,
            @RequestParam(required = false, defaultValue = "0") int topTags) {
        return statsService.dashboard(topAuthors, topTags);
    }
}
