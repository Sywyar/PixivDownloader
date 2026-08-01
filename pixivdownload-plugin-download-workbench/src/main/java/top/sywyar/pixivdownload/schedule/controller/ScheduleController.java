package top.sywyar.pixivdownload.schedule.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.core.web.AcquisitionCredentialResolver;
import top.sywyar.pixivdownload.download.response.ErrorResponse;
import top.sywyar.pixivdownload.download.web.LocalizedException;
import top.sywyar.pixivdownload.download.web.WorkbenchErrorResponses;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;
import top.sywyar.pixivdownload.schedule.ScheduleService;
import top.sywyar.pixivdownload.schedule.dto.ProxyOverrideRequest;
import top.sywyar.pixivdownload.schedule.dto.ScheduleCredentialBindRequest;
import top.sywyar.pixivdownload.schedule.dto.ScheduleCredentialPolicyActionRequest;
import top.sywyar.pixivdownload.schedule.dto.SchedulePendingDeleteRequest;
import top.sywyar.pixivdownload.schedule.dto.SchedulePendingView;
import top.sywyar.pixivdownload.schedule.dto.ScheduleQueueView;
import top.sywyar.pixivdownload.schedule.dto.ScheduleSourceManifestView;
import top.sywyar.pixivdownload.schedule.dto.ScheduleTaskRequest;
import top.sywyar.pixivdownload.schedule.dto.ScheduleTaskView;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 计划任务管理 API（管理员专用）。
 *
 * <p>所有路径都在 {@code /api/schedule/} 前缀下，由 {@code AuthFilter} 按 monitor 语义强制登录
 * （solo 与 multi 均仅管理员），不入 {@code isPublic()} / {@code GUEST_ALLOWED_*}。
 *
 * <p>响应一律走 {@link ScheduleTaskView}。通用凭证绑定只接收中性取得凭证头与当前来源激活令牌；
 * 来源专属兼容路由由对应来源的适配器拥有。
 */
@RestController
@RequestMapping("/api/schedule")
@PluginManagedBean
@RequiredArgsConstructor
public class ScheduleController {

    private final ScheduleService scheduleService;
    private final MessageResolver messages;

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

    /** 经中性取得凭证头绑定任务当前执行计划声明的凭证策略。 */
    @PostMapping("/tasks/{id}/credential")
    public ScheduleTaskView bindCredential(
            @PathVariable long id,
            @Valid @RequestBody ScheduleCredentialBindRequest request,
            @RequestHeader(AcquisitionCredentialResolver.HEADER_NAME)
            String acquisitionCredential) {
        String credential = AcquisitionCredentialResolver.resolve(
                acquisitionCredential, null);
        return scheduleService.bindCredential(id, credential, request.activationToken());
    }

    /** 解除任务当前持久化的凭证策略绑定。 */
    @DeleteMapping("/tasks/{id}/credential")
    public ScheduleTaskView revokeCredential(@PathVariable long id) {
        return scheduleService.revokeCredential(id);
    }

    /** 只接受精确 policy publication 身份；动作语义由当前凭证策略纯值规划。 */
    @PostMapping("/credential-policies/actions")
    public Map<String, Object> applyCredentialPolicyAction(
            @Valid @RequestBody ScheduleCredentialPolicyActionRequest request) {
        scheduleService.applyCredentialPolicyAction(request);
        return Map.of("success", true);
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

    @ExceptionHandler(LocalizedException.class)
    public ResponseEntity<ErrorResponse> handleLocalized(LocalizedException failure, Locale locale) {
        return WorkbenchErrorResponses.localized(failure, messages, locale);
    }
}
