package top.sywyar.pixivdownload.schedule.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;
import top.sywyar.pixivdownload.schedule.ScheduleService;
import top.sywyar.pixivdownload.schedule.dto.AccountResumeRequest;
import top.sywyar.pixivdownload.schedule.dto.CookieAuthorizeRequest;
import top.sywyar.pixivdownload.schedule.dto.ProxyOverrideRequest;
import top.sywyar.pixivdownload.schedule.dto.SchedulePendingDeleteRequest;
import top.sywyar.pixivdownload.schedule.dto.SchedulePendingView;
import top.sywyar.pixivdownload.schedule.dto.ScheduleQueueView;
import top.sywyar.pixivdownload.schedule.dto.ScheduleSourceManifestView;
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
@PluginManagedBean
@RequiredArgsConstructor
public class ScheduleController {

    private final ScheduleService scheduleService;

    @GetMapping("/sources")
    public ResponseEntity<ScheduleSourceManifestView> sources() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(scheduleService.sources());
    }

    @GetMapping("/tasks")
    public List<ScheduleTaskView> list() {
        return scheduleService.list();
    }

    @GetMapping("/tasks/{id}")
    public ScheduleTaskView get(@PathVariable long id) {
        return scheduleService.get(id);
    }

    /** 最近一轮运行队列（本轮发现的每个作品及其处理结果），供前端卡片底部「本轮队列详情」展开展示。 */
    @GetMapping("/tasks/{id}/queue")
    public ScheduleQueueView queue(@PathVariable long id) {
        return scheduleService.queue(id);
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
        return scheduleService.authorizeCookie(
                id, req.getCookie(), req.getActivationToken());
    }

    @PostMapping("/tasks/{id}/revoke-cookie")
    public ScheduleTaskView revokeCookie(@PathVariable long id) {
        return scheduleService.revokeCookie(id);
    }

    /** 设置 / 清除任务级单独代理（host:port；body 的 proxy 为空 = 清除并回退全局代理设置）。 */
    @PostMapping("/tasks/{id}/proxy")
    public ScheduleTaskView updateProxy(@PathVariable long id,
                                        @RequestBody ProxyOverrideRequest req) {
        return scheduleService.updateProxy(id, req.getProxy());
    }

    @PostMapping("/tasks/{id}/run")
    public Map<String, Object> runOnce(@PathVariable long id) {
        scheduleService.manualRun(id);
        return Map.of("success", true);
    }

    // ── 暂停 / 恢复 ───────────────────────────────────────────────────────────────

    /** 手动挂起当前运行（MANUAL，不冻账号、不发邮件）。 */
    @PostMapping("/tasks/{id}/pause")
    public ScheduleTaskView pause(@PathVariable long id) {
        return scheduleService.pause(id);
    }

    /**
     * 恢复手动暂停 / 单任务挂起并<b>立即继续</b>：先清挂起（事务提交后 {@code next_run_time=now}），
     * 再触发一次后台运行立刻跑起来。{@code runOnce} 在 resume 事务提交后调用，异步执行线程读到的是已清挂起的状态；
     * 即时触发若因竞态被跳过，调度 tick 也会兜底捡起。返回刷新后的视图以即时反映「排队中」运行态。
     */
    @PostMapping("/tasks/{id}/resume")
    public ScheduleTaskView resume(@PathVariable long id) {
        ScheduleTaskView view = scheduleService.resume(id);
        // 仅对 enabled 任务即时触发后台运行；停用任务恢复后只清暂停态、不运行（停用 = 不执行）。
        if (view.enabled()) {
            scheduleService.runOnce(id);
            return scheduleService.get(id);
        }
        return view;
    }

    /** 账号级（过度访问）恢复：同账号所有任务，{@code mode=ignore|defer}。 */
    @PostMapping("/account/{accountId}/resume")
    public Map<String, Object> resumeAccount(@PathVariable String accountId,
                                             @RequestBody AccountResumeRequest req) {
        scheduleService.resumeAccount(accountId, req);
        return Map.of("success", true);
    }

    /** 隔离表（待重试）行列表。 */
    @GetMapping("/tasks/{id}/pending")
    public List<SchedulePendingView> pending(@PathVariable long id) {
        return scheduleService.pending(id);
    }

    /** 手动清除隔离表中某个「需人工」条目。 */
    @DeleteMapping("/tasks/{id}/pending")
    public Map<String, Object> clearPending(
            @PathVariable long id,
            @Valid @RequestBody SchedulePendingDeleteRequest request) {
        scheduleService.clearPending(id, request.getWorkType(), request.getWorkId());
        return Map.of("success", true);
    }
}
