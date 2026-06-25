package top.sywyar.pixivdownload.plugin;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import top.sywyar.pixivdownload.i18n.AppLocaleResolver;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.plugin.PluginManagementService.LifecycleAction;
import top.sywyar.pixivdownload.plugin.PluginManagementService.PluginActionResult;
import top.sywyar.pixivdownload.plugin.PluginManagementService.PluginManagementReport;
import top.sywyar.pixivdownload.plugin.runtime.install.PluginInstallOutcome;

/**
 * 插件管理后端 API（admin-only）：把只读插件状态与外置插件运行期生命周期动词暴露为管理 HTTP 入口，供 Web / GUI
 * 管理入口复用同一后端语义。
 * <p>
 * 路由由 {@link CorePlugin#routes()} 以 {@code /api/plugins/**} = {@code AccessPolicy.ADMIN} 显式声明，鉴权由
 * {@code AuthFilter} 独立执行（管理操作必须管理员 / solo 会话，绝不入访客 / 公开清单）；本控制器不重复实现鉴权。
 * 端点全部位于 {@code /api/plugins/} 前缀下——与恢复模式访问放行 {@code /api/plugins/} 一致，使核心进入恢复模式时
 * 管理员仍可查询状态并驱动修复。
 * <ul>
 *   <li>读：{@code GET /api/plugins/status} —— 全部插件的状态 + 运行期阶段 + 可用动词 + 是否处于恢复模式。</li>
 *   <li>写：{@code POST /api/plugins/{id}/{verb}}，verb ∈ load / start / quiesce / stop / unload / reload ——
 *       委托 {@link PluginManagementService}（含必选插件停用守卫、内置 / 未激活 / 未知 id 拒绝）。</li>
 *   <li>装：{@code POST /api/plugins/install}（{@code multipart/form-data}，{@code file} 部分）—— 上传本地
 *       {@code .jar} / {@code .zip} 插件包，委托 {@link PluginInstallService} 校验后安全落盘到 {@code plugins/}。
 *       <b>「安装」只落盘、不热加载</b>：新装 / 升级的包到下次核心重启才被发现、加载、启动，随后才进入
 *       {@code /status} 与运行期动词的可消费范围（响应 {@code effectiveAfterRestart} 即此信号）。结果分类经
 *       {@link PluginInstallOutcomeMapping} 派生 HTTP 状态与 i18n 文案——accepted（新装 / 升级 / 降级 / 已存在）
 *       返回 200，各类拒绝 / 失败返回对应 4xx / 5xx，响应体始终携带稳定 {@code outcome} + 本地化 {@code message}。</li>
 * </ul>
 * 本控制器是核心基础设施 Bean（根包扫描装配、非 {@code @PluginManagedBean}），不持有任何 PF4J 类型。
 */
@RestController
@RequestMapping("/api/plugins")
public class PluginManagementController {

    private final PluginManagementService pluginManagementService;
    private final PluginInstallService pluginInstallService;
    private final AppMessages messages;
    private final AppLocaleResolver localeResolver;

    public PluginManagementController(PluginManagementService pluginManagementService,
                                      PluginInstallService pluginInstallService,
                                      AppMessages messages, AppLocaleResolver localeResolver) {
        this.pluginManagementService = pluginManagementService;
        this.pluginInstallService = pluginInstallService;
        this.messages = messages;
        this.localeResolver = localeResolver;
    }

    /** 全部插件的状态 + 运行期阶段 + 可用动词 + 是否处于恢复模式。 */
    @GetMapping("/status")
    public PluginManagementReport status() {
        return pluginManagementService.list();
    }

    /** 把一个已卸下的外置插件重新接入核心注册中心。 */
    @PostMapping("/{id}/load")
    public PluginActionResult load(@PathVariable String id) {
        return pluginManagementService.perform(id, LifecycleAction.LOAD);
    }

    /** 启动（重建服务足迹）一个当前已停止 / 已加载的外置插件。 */
    @PostMapping("/{id}/start")
    public PluginActionResult start(@PathVariable String id) {
        return pluginManagementService.perform(id, LifecycleAction.START);
    }

    /** 静默一个正在服务的外置插件（停止接收新请求 / 新计划任务派发、清退在途任务）。 */
    @PostMapping("/{id}/quiesce")
    public PluginActionResult quiesce(@PathVariable String id) {
        return pluginManagementService.perform(id, LifecycleAction.QUIESCE);
    }

    /** 停止一个外置插件（拆除服务足迹）。 */
    @PostMapping("/{id}/stop")
    public PluginActionResult stop(@PathVariable String id) {
        return pluginManagementService.perform(id, LifecycleAction.STOP);
    }

    /** 卸下一个外置插件（先确保停止，再从核心注册中心移除）。 */
    @PostMapping("/{id}/unload")
    public PluginActionResult unload(@PathVariable String id) {
        return pluginManagementService.perform(id, LifecycleAction.UNLOAD);
    }

    /** 重载一个外置插件（stop 后再 start，回收并重建服务足迹）。 */
    @PostMapping("/{id}/reload")
    public PluginActionResult reload(@PathVariable String id) {
        return pluginManagementService.perform(id, LifecycleAction.RELOAD);
    }

    /**
     * 安装一个上传的本地插件包（{@code .jar} / {@code .zip}）：委托 {@link PluginInstallService} 校验后安全落盘到
     * {@code plugins/}。<b>只落盘、不热加载</b>——成功安装的包到下次核心重启才被发现、加载、启动
     * （响应 {@code effectiveAfterRestart}）。结果分类（accepted / 各类拒绝 / 失败）经 {@link PluginInstallOutcomeMapping}
     * 派生 HTTP 状态与 i18n 文案，响应体始终携带稳定机器码 {@code outcome} + 本地化 {@code message} + 依赖诊断。
     *
     * @param file           上传的插件包（multipart {@code file} 部分；空 / 缺失 → REJECTED_EMPTY → 400）
     * @param allowDowngrade 是否允许覆盖更高版本（force；默认 false）
     */
    @PostMapping(value = "/install", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<PluginInstallResponse> install(
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "allowDowngrade", defaultValue = "false") boolean allowDowngrade,
            HttpServletRequest request) {
        PluginInstallReport report = pluginInstallService.install(file, allowDowngrade);
        PluginInstallOutcome outcome = report.outcome();
        HttpStatus httpStatus = PluginInstallOutcomeMapping.httpStatus(outcome);
        String fallback = report.diagnostics().isEmpty() ? outcome.name() : report.diagnostics().get(0);
        String message = messages.getOrDefault(localeResolver.resolveLocale(request),
                PluginInstallOutcomeMapping.messageKey(outcome), fallback);
        PluginInstallResponse body = new PluginInstallResponse(
                outcome.name(),
                report.accepted(),
                report.effectiveAfterRestart(),
                httpStatus.value(),
                message,
                report.pluginId(),
                report.version(),
                report.previousVersion(),
                report.dependencies(),
                report.unsatisfiedDependencies(),
                report.diagnostics());
        return ResponseEntity.status(httpStatus).body(body);
    }

    /**
     * 管理操作失败：返回「稳定机器码 {@code code} + 本地化 {@code message} + 诊断上下文」。{@code code} 取
     * {@link PluginManagementErrorCode#name()}（与界面语言无关、供管理入口按机器语义分支），{@code message} 按请求
     * 语言解析、绝不作为机器分支依据；HTTP 状态与 i18n 文案 key 均由稳定码派生。
     */
    @ExceptionHandler(PluginManagementException.class)
    public ResponseEntity<PluginManagementErrorResponse> handle(PluginManagementException ex,
                                                                HttpServletRequest request) {
        String message = messages.getOrDefault(localeResolver.resolveLocale(request),
                ex.messageKey(), ex.getMessage());
        PluginManagementErrorResponse body = new PluginManagementErrorResponse(
                ex.code().name(),
                message,
                ex.status().value(),
                ex.pluginId(),
                ex.action(),
                ex.runtimePhase());
        return ResponseEntity.status(ex.status()).body(body);
    }
}
