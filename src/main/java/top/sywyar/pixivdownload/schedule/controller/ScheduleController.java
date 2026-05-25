package top.sywyar.pixivdownload.schedule.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.schedule.ScheduleService;
import top.sywyar.pixivdownload.schedule.dto.CookieAuthorizeRequest;
import top.sywyar.pixivdownload.schedule.dto.ScheduleTaskRequest;
import top.sywyar.pixivdownload.schedule.dto.ScheduleTaskView;

import java.util.List;
import java.util.Map;

/**
 * 计划任务管理 API（管理员专用）。
 *
 * <p>所有路径都在 {@code /api/schedule/} 前缀下，由 {@code AuthFilter} 按 monitor 语义强制登录
 * （solo 与 multi 均仅管理员），不入 {@code isPublic()} / {@code GUEST_ALLOWED_*}。
 *
 * <p>响应一律走 {@link ScheduleTaskView}（<b>不含</b> cookie 快照）；cookie 绝不回显。
 */
@RestController
@RequestMapping("/api/schedule")
@RequiredArgsConstructor
public class ScheduleController {

    private final ScheduleService scheduleService;

    @GetMapping("/tasks")
    public List<ScheduleTaskView> list() {
        return scheduleService.list();
    }

    @GetMapping("/tasks/{id}")
    public ScheduleTaskView get(@PathVariable long id) {
        return scheduleService.get(id);
    }

    @PostMapping("/tasks")
    public ScheduleTaskView create(@Valid @RequestBody ScheduleTaskRequest req) {
        return scheduleService.create(req);
    }

    @PutMapping("/tasks/{id}")
    public ScheduleTaskView update(@PathVariable long id, @Valid @RequestBody ScheduleTaskRequest req) {
        return scheduleService.update(id, req);
    }

    @DeleteMapping("/tasks/{id}")
    public Map<String, Object> delete(@PathVariable long id) {
        scheduleService.delete(id);
        return Map.of("success", true);
    }

    @PostMapping("/tasks/{id}/enabled")
    public ScheduleTaskView setEnabled(@PathVariable long id, @RequestParam boolean enabled) {
        return scheduleService.setEnabled(id, enabled);
    }

    @PostMapping("/tasks/{id}/authorize-cookie")
    public ScheduleTaskView authorizeCookie(@PathVariable long id,
                                            @Valid @RequestBody CookieAuthorizeRequest req) {
        return scheduleService.authorizeCookie(id, req.getCookie());
    }

    @PostMapping("/tasks/{id}/revoke-cookie")
    public ScheduleTaskView revokeCookie(@PathVariable long id) {
        return scheduleService.revokeCookie(id);
    }

    @PostMapping("/tasks/{id}/run")
    public Map<String, Object> runOnce(@PathVariable long id) {
        scheduleService.runOnce(id);
        return Map.of("success", true);
    }
}
