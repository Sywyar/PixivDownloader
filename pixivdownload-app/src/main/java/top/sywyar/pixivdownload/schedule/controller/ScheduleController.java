package top.sywyar.pixivdownload.schedule.controller;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import top.sywyar.pixivdownload.core.web.KnownErrorCodes;
import top.sywyar.pixivdownload.plugin.api.web.HttpApiGroup;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import top.sywyar.pixivdownload.setup.ProgramAccess;
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
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;
import top.sywyar.pixivdownload.plugin.api.schedule.management.ScheduleManagementException;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.schedule.ScheduleService;
import top.sywyar.pixivdownload.schedule.dto.ProxyOverrideRequest;
import top.sywyar.pixivdownload.schedule.dto.ScheduleCredentialBindRequest;
import top.sywyar.pixivdownload.schedule.dto.ScheduleCredentialPolicyActionRequest;
import top.sywyar.pixivdownload.schedule.dto.SchedulePendingDeleteRequest;
import top.sywyar.pixivdownload.schedule.dto.SchedulePendingView;
import top.sywyar.pixivdownload.schedule.dto.SchedulePendingResolveRequest;
import top.sywyar.pixivdownload.schedule.dto.ScheduleQueueView;
import top.sywyar.pixivdownload.schedule.dto.ScheduleSourceManifestView;
import top.sywyar.pixivdownload.schedule.dto.ScheduleTaskRequest;
import top.sywyar.pixivdownload.plugin.api.schedule.management.ScheduleTaskView;

import java.util.List;
import java.util.Locale;

/**
 * 计划任务管理 API，支持管理员会话与显式授权的程序身份。
 *
 * <p>浏览器入口保持 ADMIN 策略；程序由过滤器检查动作权限，本控制器检查来源或凭据策略 owner。
 *
 * <p>响应一律走 {@link ScheduleTaskView}。通用凭证绑定只接收中性取得凭证头与当前来源激活令牌；
 * 来源专属兼容路由由对应来源的适配器拥有。
 */
@HttpApiGroup("core-schedule")
@RestController
@RequestMapping("/api/schedule")
@RequiredArgsConstructor
@KnownErrorCodes(status = "503", value = "DATABASE_UNAVAILABLE")
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
    public List<ScheduleTaskView> list(HttpServletRequest httpRequest) {
        return scheduleService.list().stream()
                .filter(task -> ProgramAccess.permits(httpRequest, task.sourceOwnerPluginId(), "schedule.manage"))
                .toList();
    }

    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @KnownErrorCodes(status = "400", value = "schedule.error.not-found")
    @GetMapping("/tasks/{id}")
    public ScheduleTaskView get(@PathVariable long id, HttpServletRequest httpRequest) {
        requireTask(httpRequest, id);
        return scheduleService.get(id);
    }

    /** 最近一轮运行队列（本轮发现的每个作品及其处理结果），供前端卡片底部「本轮队列详情」展开展示。 */
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @KnownErrorCodes(status = "400", value = "schedule.error.not-found")
    @GetMapping("/tasks/{id}/queue")
    public ScheduleQueueView queue(@PathVariable long id, HttpServletRequest httpRequest) {
        requireTask(httpRequest, id);
        return scheduleService.queue(id);
    }

    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @KnownErrorCodes(status = "400", value = {
            "schedule.error.create-state-version", "schedule.error.max-tasks", "schedule.error.definition-invalid",
            "schedule.error.source-unavailable", "schedule.error.source-write-unavailable",
            "schedule.error.interval-invalid", "schedule.error.cron-invalid", "schedule.error.trigger-invalid"})
    @KnownErrorCodes(status = "409", value = {
            "schedule.error.concurrent-change", "schedule.error.source-publication-changed"})
    @PostMapping("/tasks")
    public ScheduleTaskView create(@Valid @RequestBody ScheduleTaskRequest req, HttpServletRequest httpRequest) {
        requireSource(httpRequest, req);
        return scheduleService.create(req);
    }

    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @KnownErrorCodes(status = "400", value = {
            "schedule.error.not-found", "schedule.error.busy", "schedule.error.expected-state-version",
            "schedule.error.source-owner-change-forbidden", "schedule.error.definition-invalid",
            "schedule.error.source-unavailable", "schedule.error.source-write-unavailable",
            "schedule.error.interval-invalid", "schedule.error.cron-invalid", "schedule.error.trigger-invalid"})
    @KnownErrorCodes(status = "409", value = {
            "schedule.error.concurrent-change", "schedule.error.source-publication-changed"})
    @PutMapping("/tasks/{id}")
    public ScheduleTaskView update(@PathVariable long id, @Valid @RequestBody ScheduleTaskRequest req, HttpServletRequest httpRequest) {
        requireTask(httpRequest, id);
        requireSource(httpRequest, req);
        return scheduleService.update(id, req);
    }

    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @KnownErrorCodes(status = "400", value = {
            "schedule.error.not-found", "schedule.error.busy", "schedule.error.concurrent-change"})
    @DeleteMapping("/tasks/{id}")
    public SuccessResponse delete(@PathVariable long id, HttpServletRequest httpRequest) {
        requireTask(httpRequest, id);
        scheduleService.delete(id);
        return new SuccessResponse(true);
    }

    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @KnownErrorCodes(status = "400", value = {
            "schedule.error.not-found", "schedule.error.busy", "schedule.error.concurrent-change"})
    @PostMapping("/tasks/{id}/enabled")
    public ScheduleTaskView setEnabled(@PathVariable long id, @RequestParam boolean enabled, HttpServletRequest httpRequest) {
        requireTask(httpRequest, id);
        return scheduleService.setEnabled(id, enabled);
    }

    /** 经中性取得凭证头绑定任务当前执行计划声明的凭证策略。 */
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @KnownErrorCodes(status = "400", value = {
            "schedule.error.not-found", "schedule.error.busy", "schedule.error.credential-empty",
            "schedule.error.credential-invalid", "schedule.error.credential-fixed",
            "schedule.error.credential-unchanged", "schedule.error.execution-capability-unavailable",
            "schedule.error.credential-probe-failed", "schedule.error.credential-policy-unavailable"})
    @KnownErrorCodes(status = "409", value = {
            "schedule.error.concurrent-change", "schedule.error.credential-policy-publication-changed",
            "schedule.error.source-publication-changed"})
    @PostMapping("/tasks/{id}/credential")
    public ScheduleTaskView bindCredential(
            @PathVariable long id,
            @Valid @RequestBody ScheduleCredentialBindRequest request,
            @RequestHeader(AcquisitionCredentialResolver.HEADER_NAME)
            String acquisitionCredential, HttpServletRequest httpRequest) {
        requireTask(httpRequest, id);
        String credential = AcquisitionCredentialResolver.resolve(
                acquisitionCredential);
        return scheduleService.bindCredential(id, credential, request.activationToken());
    }

    /** 解除任务当前持久化的凭证策略绑定。 */
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @KnownErrorCodes(status = "400", value = {
            "schedule.error.not-found", "schedule.error.busy", "schedule.error.credential-policy-unavailable",
            "schedule.error.credential-fixed"})
    @KnownErrorCodes(status = "409", value = {
            "schedule.error.concurrent-change", "schedule.error.credential-policy-publication-changed"})
    @DeleteMapping("/tasks/{id}/credential")
    public ScheduleTaskView revokeCredential(@PathVariable long id, HttpServletRequest httpRequest) {
        requireTask(httpRequest, id);
        return scheduleService.revokeCredential(id);
    }

    /** 只接受精确 policy publication 身份；动作语义由当前凭证策略纯值规划。 */
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @KnownErrorCodes(status = "400", value = {
            "schedule.error.credential-account-not-found", "schedule.error.credential-account-action-unsupported",
            "schedule.error.credential-account-action-invalid", "schedule.error.credential-account-action-failed"})
    @KnownErrorCodes(status = "409", value = {
            "schedule.error.concurrent-change", "schedule.error.credential-policy-publication-changed"})
    @PostMapping("/credential-policies/actions")
    public SuccessResponse applyCredentialPolicyAction(
            @Valid @RequestBody ScheduleCredentialPolicyActionRequest request, HttpServletRequest httpRequest) {
        ProgramAccess.require(httpRequest, request.getOwnerPluginId(), "schedule.manage",
                () -> scheduleService.hasCredentialPolicy(request.getOwnerPluginId(), request.getPolicyId()));
        scheduleService.applyCredentialPolicyAction(request);
        return new SuccessResponse(true);
    }

    /** 设置 / 清除任务级单独代理（host:port；body 的 proxy 为空 = 清除并回退全局代理设置）。 */
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @KnownErrorCodes(status = "400", value = {
            "schedule.error.not-found", "schedule.error.busy", "schedule.error.proxy-invalid",
            "schedule.error.concurrent-change"})
    @PostMapping("/tasks/{id}/proxy")
    public ScheduleTaskView updateProxy(@PathVariable long id,
                                        @RequestBody ProxyOverrideRequest req, HttpServletRequest httpRequest) {
        requireTask(httpRequest, id);
        return scheduleService.updateProxy(id, req.getProxy());
    }

    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "429", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @KnownErrorCodes(status = "400", value = {
            "schedule.error.not-found", "schedule.error.busy", "schedule.error.run-disabled",
            "schedule.error.run-suspended"})
    @KnownErrorCodes(status = "429", value = "task.queue.full")
    @PostMapping("/tasks/{id}/run")
    public SuccessResponse runOnce(@PathVariable long id, HttpServletRequest httpRequest) {
        requireTask(httpRequest, id);
        scheduleService.manualRun(id);
        return new SuccessResponse(true);
    }

    // ── 暂停 / 恢复 ───────────────────────────────────────────────────────────────

    /** 手动挂起当前运行（MANUAL，不冻账号、不发邮件）。 */
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @KnownErrorCodes(status = "400", value = {
            "schedule.error.not-found", "schedule.error.already-suspended", "schedule.error.pause-idle",
            "schedule.error.concurrent-change"})
    @PostMapping("/tasks/{id}/pause")
    public ScheduleTaskView pause(@PathVariable long id, HttpServletRequest httpRequest) {
        requireTask(httpRequest, id);
        return scheduleService.pause(id);
    }

    /**
     * 恢复手动暂停 / 单任务挂起并<b>立即继续</b>：先清挂起（事务提交后 {@code next_run_time=now}），
     * 再触发一次后台运行立刻跑起来。{@code runOnce} 在 resume 事务提交后调用，异步执行线程读到的是已清挂起的状态；
     * 即时触发若因竞态被跳过，调度 tick 也会兜底捡起。返回刷新后的视图以即时反映「排队中」运行态。
     */
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @KnownErrorCodes(status = "400", value = {
            "schedule.error.not-found", "schedule.error.busy", "schedule.error.concurrent-change"})
    @PostMapping("/tasks/{id}/pending/resolve")
    public ScheduleTaskView resolvePending(@PathVariable long id,
            @Valid @RequestBody SchedulePendingResolveRequest request, HttpServletRequest httpRequest) {
        requireTask(httpRequest, id);
        return scheduleService.resolvePending(id, request.expectedStateVersion(), request.workType(),
                request.workId(), request.userAction(), request.rememberForRun());
    }

    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "429", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @KnownErrorCodes(status = "400", value = {
            "schedule.error.not-found", "schedule.error.busy", "schedule.error.resume-not-paused",
            "schedule.error.concurrent-change"})
    @KnownErrorCodes(status = "429", value = "task.queue.full")
    @PostMapping("/tasks/{id}/resume")
    public ScheduleTaskView resume(@PathVariable long id, HttpServletRequest httpRequest) {
        requireTask(httpRequest, id);
        ScheduleTaskView view = scheduleService.resume(id);
        // 仅对 enabled 任务即时触发后台运行；停用任务恢复后只清暂停态、不运行（停用 = 不执行）。
        if (view.enabled()) {
            scheduleService.runOnce(id);
            return scheduleService.get(id);
        }
        return view;
    }

    /** 隔离表（待重试）行列表。 */
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @KnownErrorCodes(status = "400", value = "schedule.error.not-found")
    @GetMapping("/tasks/{id}/pending")
    public List<SchedulePendingView> pending(@PathVariable long id, HttpServletRequest httpRequest) {
        requireTask(httpRequest, id);
        return scheduleService.pending(id);
    }

    /** 手动清除隔离表中某个「需人工」条目。 */
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @KnownErrorCodes(status = "400", value = {
            "schedule.error.not-found", "schedule.error.busy", "schedule.error.concurrent-change"})
    @DeleteMapping("/tasks/{id}/pending")
    public SuccessResponse clearPending(
            @PathVariable long id,
            @Valid @RequestBody SchedulePendingDeleteRequest request, HttpServletRequest httpRequest) {
        requireTask(httpRequest, id);
        scheduleService.clearPending(id, request.getWorkType(), request.getWorkId());
        return new SuccessResponse(true);
    }

    private void requireTask(HttpServletRequest request, long id) {
        if (ProgramAccess.identity(request).isEmpty()) return;
        ProgramAccess.require(request, scheduleService.get(id).sourceOwnerPluginId(), "schedule.manage");
    }

    private void requireSource(HttpServletRequest request, ScheduleTaskRequest body) {
        if (ProgramAccess.identity(request).isEmpty()) return;
        var source = scheduleService.sources().sources().stream()
                .filter(value -> value.sourceType().equals(body.getSourceType().trim()))
                .findFirst().orElseThrow(ProgramAccess::forbidden);
        ProgramAccess.require(request, source.ownerPluginId(), "schedule.manage",
                () -> source.activationToken().equals(body.getActivationToken()));
        ScheduleService.requireActivationToken(source.activationToken(), body.getActivationToken());
    }

    public record SuccessResponse(boolean success) {}

    @ExceptionHandler(ScheduleManagementException.class)
    public ResponseEntity<ApiErrorResponse> handleLocalized(ScheduleManagementException failure, Locale locale) {
        return ResponseEntity.status((failure.code() == ScheduleManagementException.Code.CONFLICT ? 409 : 400)).body(ApiErrorResponse.of(
                failure.messageCode(), messages.getOrDefault(locale, failure.messageCode(),
                        failure.defaultMessage(), failure.messageArgs())));
    }
}
