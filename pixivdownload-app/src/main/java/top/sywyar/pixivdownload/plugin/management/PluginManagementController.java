package top.sywyar.pixivdownload.plugin.management;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;
import top.sywyar.pixivdownload.plugin.catalog.trust.PluginCatalogRevocationService;
import top.sywyar.pixivdownload.plugin.api.web.HttpApiGroup;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.SchemaProperty;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.multipart.MultipartFile;
import top.sywyar.pixivdownload.i18n.AppLocaleResolver;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.plugin.management.PluginManagementService.LifecycleAction;
import top.sywyar.pixivdownload.plugin.management.PluginManagementService.PluginActionResult;
import top.sywyar.pixivdownload.plugin.management.PluginManagementService.PluginManagementReport;
import top.sywyar.pixivdownload.plugin.install.PluginInstallOutcomeMapping;
import top.sywyar.pixivdownload.plugin.install.PluginInstallReport;
import top.sywyar.pixivdownload.plugin.install.PluginInstallResponse;
import top.sywyar.pixivdownload.plugin.install.PluginInstallResponseMapper;
import top.sywyar.pixivdownload.plugin.install.PluginInstallService;

/**
 * 插件管理后端 API（admin-only）：把只读插件状态与外置插件运行期生命周期动词暴露为管理 HTTP 入口，供 Web / GUI
 * 管理入口复用同一后端语义。
 * <p>
 * 路由由 {@link CorePlugin#routes()} 以 {@code /api/plugins/**} = {@code AccessPolicy.ADMIN} 显式声明，鉴权由
 * {@code AuthFilter} 执行身份与动作授权；程序管理再检查目标和消费者，并在变更预留内确认影响未变化。
 * 端点全部位于 {@code /api/plugins/} 前缀下——与恢复模式访问放行 {@code /api/plugins/} 一致，使核心进入恢复模式时
 * 管理员仍可查询状态并驱动修复。
 * <ul>
 *   <li>读：{@code GET /api/plugins/status} —— 全部插件的状态 + 运行期阶段 + 可用动词 + 是否处于恢复模式。</li>
 *   <li>写：{@code POST /api/plugins/{id}/{verb}}，verb ∈ load / start / quiesce / stop / unload / remove /
 *       restart / reload ——
 *       委托 {@link PluginManagementService}（含必选插件停用守卫、内置 / 未激活 / 未知 id 拒绝）。</li>
 *   <li>开关：{@code PUT /api/plugins/{id}/enabled} —— 持久化外置可选插件的期望启用态；
 *       {@code POST /api/plugins/backend-restart} —— 仅在桌面生命周期管理器持有 RUNNING 上下文时延迟重启后端。</li>
 *   <li>装：{@code POST /api/plugins/install}（{@code multipart/form-data}，{@code file} + 可选 {@code signature}
 *       部分）—— 上传本地 {@code .jar} / {@code .zip} 插件包，委托 {@link PluginInstallService} 校验后安全落盘到
 *       {@code plugins/}。签名用于发布者身份连续性；未签名包由管理员按精确 SHA-256 确认执行信任。
 *       安装走统一事务编排：校验后物理卸载旧代、原子替换并即时激活，失败时恢复旧版本。结果分类经
 *       {@link PluginInstallOutcomeMapping} 派生 HTTP 状态与 i18n 文案——accepted（新装 / 升级 / 降级 / 已存在）
 *       返回 200，各类拒绝 / 失败返回对应 4xx / 5xx，响应体始终携带稳定 {@code outcome} + 本地化 {@code message}。</li>
 * </ul>
 * 本控制器是核心基础设施 Bean（根包扫描装配、非 {@code @PluginManagedBean}），不持有任何 PF4J 类型。
 */
@HttpApiGroup("core-plugins")
@RestController
@RequestMapping("/api/plugins")
public class PluginManagementController {

    private final PluginManagementService pluginManagementService;
    private final PluginInstallService pluginInstallService;
    private final PluginInstallResponseMapper installResponseMapper;
    private final PluginEnabledConfigurationService enabledConfigurationService;
    private final BackendContextRestartService backendRestartService;
    private final AppMessages messages;
    private final AppLocaleResolver localeResolver;

    public PluginManagementController(PluginManagementService pluginManagementService,
                                      PluginInstallService pluginInstallService,
                                      PluginInstallResponseMapper installResponseMapper,
                                      PluginEnabledConfigurationService enabledConfigurationService,
                                      BackendContextRestartService backendRestartService,
                                      AppMessages messages, AppLocaleResolver localeResolver) {
        this.pluginManagementService = pluginManagementService;
        this.pluginInstallService = pluginInstallService;
        this.installResponseMapper = installResponseMapper;
        this.enabledConfigurationService = enabledConfigurationService;
        this.backendRestartService = backendRestartService;
        this.messages = messages;
        this.localeResolver = localeResolver;
    }

    /** 全部插件的状态 + 运行期阶段 + 可用动词 + 是否处于恢复模式。 */
    @GetMapping("/status")
    public PluginManagementReport status() {
        return pluginManagementService.list();
    }

    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json",
            schema = @Schema(implementation = PluginCatalogRevocationService.RefreshResult.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "422", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "500", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "502", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @PostMapping("/revocations/{repositoryId}/refresh")
    public ResponseEntity<?> refreshRevocations(@PathVariable String repositoryId, HttpServletRequest request) {
        var result = pluginManagementService.refreshRevocations(repositoryId);
        if (result.refreshed()) return ResponseEntity.ok(result);
        var code = top.sywyar.pixivdownload.plugin.catalog.error.PluginCatalogErrorCode.valueOf(result.code());
        String error = messages.getOrDefault(localeResolver.resolveLocale(request), code.messageKey(), code.name());
        return ResponseEntity.status(code.status()).body(
                top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse.of(code.name(), error));
    }

    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PluginManagementErrorResponse.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PluginManagementErrorResponse.class)))
    @GetMapping("/{id}/impact")
    public PluginManagementService.PluginImpactPreview impact(@PathVariable String id) {
        return pluginManagementService.previewImpact(id);
    }

    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PluginManagementErrorResponse.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PluginManagementErrorResponse.class)))
    @ApiResponse(responseCode = "500", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PluginManagementErrorResponse.class)))
    @PostMapping("/{id}/actions/{verb}")
    public PluginActionResult performPreviewed(@PathVariable String id, @PathVariable String verb,
                                               @RequestBody PluginImpactRequest request, HttpServletRequest httpRequest) {
        LifecycleAction action = java.util.Arrays.stream(LifecycleAction.values())
                .filter(value -> value.token().equals(verb)).findFirst()
                .orElseThrow(() -> new PluginManagementException(PluginManagementErrorCode.ILLEGAL_TRANSITION,
                        id, verb, null, "unknown lifecycle action"));
        return withTarget(httpRequest, id, () -> pluginManagementService.performPreviewed(id, action, request.fingerprint()));
    }

    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PluginManagementErrorResponse.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PluginManagementErrorResponse.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PluginManagementErrorResponse.class)))
    @ApiResponse(responseCode = "500", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PluginManagementErrorResponse.class)))
    @PutMapping("/{id}/enabled-preview")
    public PluginEnabledConfigurationService.PluginEnabledState enabledPreviewed(
            @PathVariable String id, @RequestBody PluginImpactRequest request, HttpServletRequest httpRequest) {
        if (request.enabled() == null) {
            throw new PluginManagementException(PluginManagementErrorCode.INVALID_TOGGLE_REQUEST,
                    id, "update-enabled", null, "enabled is required");
        }
        return withTarget(httpRequest, id, () -> pluginManagementService.confirmImpact(id, request.fingerprint(),
                () -> enabledConfigurationService.update(id, request.enabled())));
    }

    public record PluginImpactRequest(String fingerprint, Boolean enabled) { }

    /** 把一个已卸下的外置插件重新接入核心注册中心。 */
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PluginManagementErrorResponse.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PluginManagementErrorResponse.class)))
    @PostMapping("/{id}/load")
    public PluginActionResult load(@PathVariable String id, HttpServletRequest httpRequest) {
        return withTarget(httpRequest, id, () -> pluginManagementService.perform(id, LifecycleAction.LOAD));
    }

    /** 启动（重建服务足迹）一个当前已停止 / 已加载的外置插件。 */
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PluginManagementErrorResponse.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PluginManagementErrorResponse.class)))
    @PostMapping("/{id}/start")
    public PluginActionResult start(@PathVariable String id, HttpServletRequest httpRequest) {
        return withTarget(httpRequest, id, () -> pluginManagementService.perform(id, LifecycleAction.START));
    }

    /** 静默一个正在服务的外置插件（停止接收新请求 / 新计划任务派发、清退在途任务）。 */
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PluginManagementErrorResponse.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PluginManagementErrorResponse.class)))
    @PostMapping("/{id}/quiesce")
    public PluginActionResult quiesce(@PathVariable String id, HttpServletRequest httpRequest) {
        return withTarget(httpRequest, id, () -> pluginManagementService.perform(id, LifecycleAction.QUIESCE));
    }

    /** 停止一个外置插件（拆除服务足迹）。 */
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PluginManagementErrorResponse.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PluginManagementErrorResponse.class)))
    @PostMapping("/{id}/stop")
    public PluginActionResult stop(@PathVariable String id, HttpServletRequest httpRequest) {
        return withTarget(httpRequest, id, () -> pluginManagementService.perform(id, LifecycleAction.STOP));
    }

    /** 卸下一个外置插件（先确保停止，再从核心注册中心移除）。 */
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PluginManagementErrorResponse.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PluginManagementErrorResponse.class)))
    @ApiResponse(responseCode = "500", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PluginManagementErrorResponse.class)))
    @PostMapping("/{id}/unload")
    public PluginActionResult unload(@PathVariable String id, HttpServletRequest httpRequest) {
        return withTarget(httpRequest, id, () -> pluginManagementService.perform(id, LifecycleAction.UNLOAD));
    }

    /** 卸下并删除磁盘插件包。 */
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PluginManagementErrorResponse.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PluginManagementErrorResponse.class)))
    @ApiResponse(responseCode = "500", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PluginManagementErrorResponse.class)))
    @PostMapping("/{id}/remove")
    public PluginActionResult remove(@PathVariable String id, HttpServletRequest httpRequest) {
        return withTarget(httpRequest, id, () -> pluginManagementService.perform(id, LifecycleAction.REMOVE));
    }

    /** 重启服务足迹，保留原 generation 与 classloader。 */
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PluginManagementErrorResponse.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PluginManagementErrorResponse.class)))
    @ApiResponse(responseCode = "500", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PluginManagementErrorResponse.class)))
    @PostMapping("/{id}/restart")
    public PluginActionResult restart(@PathVariable String id, HttpServletRequest httpRequest) {
        return withTarget(httpRequest, id, () -> pluginManagementService.perform(id, LifecycleAction.RESTART));
    }

    /** 物理重载一个外置插件，创建新的 generation 与 classloader。 */
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PluginManagementErrorResponse.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PluginManagementErrorResponse.class)))
    @ApiResponse(responseCode = "500", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PluginManagementErrorResponse.class)))
    @PostMapping("/{id}/reload")
    public PluginActionResult reload(@PathVariable String id, HttpServletRequest httpRequest) {
        return withTarget(httpRequest, id, () -> pluginManagementService.perform(id, LifecycleAction.RELOAD));
    }

    /** 持久化一个外置可选插件的期望启用态；实际生效方式由描述符生命周期策略决定。 */
    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json",
            schema = @Schema(implementation = PluginEnabledConfigurationService.PluginEnabledState.class)))
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PluginManagementErrorResponse.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PluginManagementErrorResponse.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PluginManagementErrorResponse.class)))
    @ApiResponse(responseCode = "500", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PluginManagementErrorResponse.class)))
    @PutMapping("/{id}/enabled")
    public PluginEnabledConfigurationService.PluginEnabledState updateEnabled(
            @Parameter(example = "example-plugin") @PathVariable String id,
            @RequestBody PluginEnabledRequest request, HttpServletRequest httpRequest) {
        if (request == null || request.enabled() == null) {
            throw new PluginManagementException(PluginManagementErrorCode.INVALID_TOGGLE_REQUEST,
                    id, "update-enabled", null, "Plugin enabled request must include enabled");
        }
        return withTarget(httpRequest, id, () -> enabledConfigurationService.update(id, request.enabled()));
    }

    /** 以当前已安装 artifact 的精确 SHA-256 批准下一次执行。 */
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PluginManagementErrorResponse.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PluginManagementErrorResponse.class)))
    @PutMapping("/{id}/trust")
    public PluginManagementService.PluginTrustView approveTrust(
            @PathVariable String id,
            @RequestParam("confirmArtifactSha256") String confirmArtifactSha256, HttpServletRequest httpRequest) {
        requireTarget(httpRequest, id);
        return pluginManagementService.approveTrust(id, confirmArtifactSha256);
    }

    /** 撤销持久化执行信任；当前 generation 不被强停，下一次加载前生效。 */
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PluginManagementErrorResponse.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PluginManagementErrorResponse.class)))
    @DeleteMapping("/{id}/trust")
    public PluginManagementService.PluginTrustView revokeTrust(@PathVariable String id, HttpServletRequest httpRequest) {
        requireTarget(httpRequest, id);
        return pluginManagementService.revokeTrust(id);
    }

    /** 仅在桌面生命周期管理器持有 RUNNING 后端上下文时接受延迟重启请求。 */
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PluginManagementErrorResponse.class)))
    @PostMapping("/backend-restart")
    public BackendContextRestartService.BackendRestartResult restartBackend() {
        return backendRestartService.requestRestart();
    }

    /**
     * 安装一个上传的本地插件包（{@code .jar} / {@code .zip}）：委托 {@link PluginInstallService} 校验后安全落盘到
     * {@code plugins/}，并由统一生命周期编排器即时加载 / 启动；失败时报告旧版本是否已恢复。结果分类
     * （accepted / 各类拒绝 / 失败）经 {@link PluginInstallOutcomeMapping}
     * 派生 HTTP 状态与 i18n 文案，响应体始终携带稳定机器码 {@code outcome} + 本地化 {@code message} + 依赖诊断。
     *
     * @param file           上传的插件包（multipart {@code file} 部分；空 / 缺失 → REJECTED_EMPTY → 400）
     * @param signature      可选 detached 签名 JSON（存在时用于发布者身份连续性）
     * @param allowDowngrade 是否允许覆盖更高版本（force；默认 false）
     */
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PluginInstallResponse.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PluginInstallResponse.class)))
    @ApiResponse(responseCode = "413", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PluginInstallResponse.class)))
    @ApiResponse(responseCode = "422", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PluginInstallResponse.class)))
    @ApiResponse(responseCode = "500", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PluginInstallResponse.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PluginInstallResponse.class)))
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = MediaType.MULTIPART_FORM_DATA_VALUE, schema = @Schema(type = "object", requiredProperties = "file"),
            schemaProperties = {
                    @SchemaProperty(name = "file", schema = @Schema(type = "string", format = "binary")),
                    @SchemaProperty(name = "signature", schema = @Schema(type = "string", format = "binary"))
            }))
    @Operation(extensions = @Extension(properties = @ExtensionProperty(name = "x-codeSamples", parseValue = true, value = """
            [{"lang":"Shell","label":"cURL","source":"curl --header \\"Authorization: Bearer $PROGRAM_KEY\\" --header \\"Origin: $INSTANCE\\" --form \\"file=@plugin.jar\\" \\"$INSTANCE/api/plugins/install\\""}]
            """)))
    @PostMapping(value = "/install", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<PluginInstallResponse> install(
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "signature", required = false) MultipartFile signature,
            @RequestParam(value = "allowDowngrade", defaultValue = "false") boolean allowDowngrade,
            @RequestParam(value = "confirmTrust", required = false) String confirmTrust,
            HttpServletRequest request) {
        PluginInstallReport report = pluginInstallService.install(
                file, signature, allowDowngrade, confirmTrust);
        return installResponseMapper.toResponse(report, request);
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

    private void requireTarget(HttpServletRequest request, String id) {
        top.sywyar.pixivdownload.setup.ProgramAccess.require(request, id, "plugins.manage",
                () -> pluginManagementService.list().plugins().stream().anyMatch(target ->
                        target.id().equals(id) && PluginManagementService.allowsManagementGrant(target)));
    }

    private <T> T withTarget(HttpServletRequest request, String id, java.util.function.Supplier<T> action) {
        if (top.sywyar.pixivdownload.setup.ProgramAccess.identity(request).isEmpty()) return action.get();
        requireTarget(request, id);
        var impact = pluginManagementService.previewImpact(id);
        for (var consumer : impact.consumers()) {
            top.sywyar.pixivdownload.setup.ProgramAccess.require(request, consumer.id(), "plugins.manage",
                    () -> PluginManagementService.allowsManagementGrant(consumer));
        }
        return pluginManagementService.confirmImpact(id, impact.fingerprint(), action);
    }

    /** 插件期望启用态写请求。 */
    @Schema(name = "PluginEnabledRequest")
    public record PluginEnabledRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "true") Boolean enabled) {
    }
}
