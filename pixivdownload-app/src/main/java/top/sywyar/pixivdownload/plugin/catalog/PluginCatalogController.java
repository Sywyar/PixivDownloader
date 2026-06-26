package top.sywyar.pixivdownload.plugin.catalog;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.i18n.AppLocaleResolver;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.plugin.PluginInstallReport;
import top.sywyar.pixivdownload.plugin.PluginInstallResponse;
import top.sywyar.pixivdownload.plugin.PluginInstallResponseMapper;

import java.util.List;

/**
 * 受信插件 catalog 后端 API（admin-only）：列出服务端配置的受信目录里的可安装条目摘要，并按 {@code id} + {@code version}
 * 从受信目录下载、校验、安全落盘安装。<b>绝不接受任意下载 URL</b>——下载地址只来自受信目录元数据，端点不取任何 URL 请求参数。
 * <p>
 * 路由在 {@code /api/plugins/} 前缀下，已由 {@code CorePlugin.routes()} 的 {@code /api/plugins/** = ADMIN} 覆盖，鉴权由
 * {@code AuthFilter} 独立执行；本控制器<b>不</b>重复实现鉴权、<b>不</b>新增路由声明，未声明路由仍 404 的语义不受影响。
 * <ul>
 *   <li>{@code GET /api/plugins/catalog} —— 受信目录可安装条目摘要（未启用时返回 {@code enabled=false} + 空列表）。</li>
 *   <li>{@code POST /api/plugins/catalog/{pluginId}/{version}/install} —— 从受信目录按 id+version 选包、下载、校验、落盘。
 *       <b>请求体不含 URL</b>；安装只落盘、不热加载，重启后生效（响应 {@code effectiveAfterRestart}）。</li>
 * </ul>
 * 本控制器是核心基础设施 Bean（组件扫描装配、非 {@code @PluginManagedBean}），不持有任何 PF4J 类型。
 */
@RestController
@RequestMapping("/api/plugins/catalog")
public class PluginCatalogController {

    private final PluginCatalogAcquisitionService acquisitionService;
    private final PluginInstallResponseMapper installResponseMapper;
    private final AppMessages messages;
    private final AppLocaleResolver localeResolver;

    public PluginCatalogController(PluginCatalogAcquisitionService acquisitionService,
                                   PluginInstallResponseMapper installResponseMapper,
                                   AppMessages messages, AppLocaleResolver localeResolver) {
        this.acquisitionService = acquisitionService;
        this.installResponseMapper = installResponseMapper;
        this.messages = messages;
        this.localeResolver = localeResolver;
    }

    /**
     * 受信目录可安装条目摘要。未启用 → {@code enabled=false} + 空列表（200，正常「功能未开」）；启用但清单不可用 →
     * {@link PluginCatalogErrorCode#CATALOG_UNAVAILABLE} 错误。
     */
    @GetMapping
    public PluginCatalogView catalog() {
        if (!acquisitionService.isEnabled()) {
            return PluginCatalogView.disabled();
        }
        PluginCatalogManifest manifest = acquisitionService.loadManifest(); // throws CATALOG_UNAVAILABLE
        List<PluginCatalogEntryView> entries = manifest.entries().stream()
                .map(PluginCatalogEntryView::from)
                .toList();
        return new PluginCatalogView(true, entries);
    }

    /**
     * 从受信目录按 {@code id} + {@code version} 安装。<b>id / version 为路径变量，包地址由受信目录解析，请求体不含 URL</b>。
     * 下载 + 校验 + 落盘后返回与本地上传安装一致的 {@link PluginInstallResponse}（稳定 {@code outcome} + 本地化
     * {@code message}，含完整性不符 {@code REJECTED_INTEGRITY} 等结局）。catalog / 下载层失败由 {@code @ExceptionHandler}
     * 映射为稳定错误响应。
     */
    @PostMapping("/{pluginId}/{version}/install")
    public ResponseEntity<PluginInstallResponse> install(@PathVariable String pluginId,
                                                         @PathVariable String version,
                                                         HttpServletRequest request) {
        PluginInstallReport report = acquisitionService.install(pluginId, version);
        return installResponseMapper.toResponse(report, request);
    }

    /**
     * catalog / 下载层失败：返回「稳定机器码 {@code code} + 本地化 {@code message} + 诊断上下文」。{@code code} 取
     * {@link PluginCatalogErrorCode#name()}（与界面语言无关），HTTP 状态与 i18n 文案 key 均由稳定码派生。
     */
    @ExceptionHandler(PluginCatalogException.class)
    public ResponseEntity<PluginCatalogErrorResponse> handle(PluginCatalogException ex, HttpServletRequest request) {
        String message = messages.getOrDefault(localeResolver.resolveLocale(request),
                ex.messageKey(), ex.getMessage());
        PluginCatalogErrorResponse body = new PluginCatalogErrorResponse(
                ex.code().name(), message, ex.status().value(), ex.pluginId(), ex.version());
        return ResponseEntity.status(ex.status()).body(body);
    }
}
