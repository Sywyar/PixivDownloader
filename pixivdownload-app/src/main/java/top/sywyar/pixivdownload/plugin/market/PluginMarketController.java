package top.sywyar.pixivdownload.plugin.market;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.i18n.AppLocaleResolver;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.plugin.install.PluginInstallReport;
import top.sywyar.pixivdownload.plugin.install.PluginInstallResponse;
import top.sywyar.pixivdownload.plugin.install.PluginInstallResponseMapper;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogErrorResponse;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogException;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;

/**
 * 插件市场后端 API（admin-only）：浏览服务端配置的受信仓库列表与其 catalog，并按 {@code repositoryId} + {@code pluginId}
 * + {@code version} 从受信仓库下载、校验、安全落盘安装。<b>绝不接受任意下载 / 清单 URL</b>——只接受受控标识，下载地址只来自
 * 受信仓库清单。
 *
 * <ul>
 *   <li>{@code GET /api/plugin-market/repositories} —— 仓库列表 + 主开关状态 + 核心 API 版本 + 默认仓库 id。</li>
 *   <li>{@code GET /api/plugin-market/catalog?repositoryId=official} —— 指定仓库（空取默认）的 catalog 摘要 + 分类计数
 *       （主开关关闭 → {@code enabled=false} + 空，200）。</li>
 *   <li>{@code GET /api/plugin-market/plugins/{repositoryId}/{pluginId}} —— 指定仓库 + 插件 id 的详情 + 版本历史。</li>
 *   <li>{@code POST /api/plugin-market/{repositoryId}/{pluginId}/{version}/install} —— 按受控标识安装（请求体不含 URL）；
 *       安装经统一事务编排器原子替换并即时激活，失败时恢复旧版本。</li>
 * </ul>
 *
 * <p>本控制器由 {@link PluginMarketPluginConfiguration} 经 {@code @PluginManagedBean} + {@code @ConditionalOnPluginEnabled}
 * 装配——随 {@code plugin-market} 插件启停进出（禁用即缺席，其 {@code /api/plugin-market/**} 路由「未声明即 404」）。鉴权由
 * {@code AuthFilter} 经 {@code PluginMarketPlugin.routes()} 声明的 {@code /api/plugin-market/** = ADMIN} 独立执行；本控制器
 * <b>不</b>重复实现鉴权、<b>不</b>持有任何 PF4J 类型、<b>不</b>触数据库。
 */
@PluginManagedBean
@RestController
@RequestMapping("/api/plugin-market")
public class PluginMarketController {

    private final PluginMarketService marketService;
    private final PluginInstallResponseMapper installResponseMapper;
    private final AppMessages messages;
    private final AppLocaleResolver localeResolver;

    public PluginMarketController(PluginMarketService marketService,
                                  PluginInstallResponseMapper installResponseMapper,
                                  AppMessages messages, AppLocaleResolver localeResolver) {
        this.marketService = marketService;
        this.installResponseMapper = installResponseMapper;
        this.messages = messages;
        this.localeResolver = localeResolver;
    }

    /** 仓库列表 + 主开关状态 + 核心 API 版本 + 默认仓库 id（主开关关闭也返回仓库列表）。 */
    @GetMapping("/repositories")
    public PluginMarketRepositoriesView repositories() {
        return marketService.repositories();
    }

    /**
     * 指定仓库（{@code repositoryId} 缺省取默认）的 catalog 摘要 + 分类计数。<b>{@code repositoryId} 只能引用服务端已配置
     * 仓库</b>（未知 → {@code UNKNOWN_REPOSITORY}），绝不接受任意 URL。主开关关闭 → 200 + {@code enabled=false} + 空。
     */
    @GetMapping("/catalog")
    public PluginMarketView catalog(@RequestParam(name = "repositoryId", required = false) String repositoryId) {
        return marketService.catalog(repositoryId);
    }

    /** 指定仓库 + 插件 id 的详情 + 版本历史。{@code repositoryId} 只能引用已配置仓库；未知插件 → {@code UNKNOWN_PLUGIN}。 */
    @GetMapping("/plugins/{repositoryId}/{pluginId}")
    public PluginMarketEntryView pluginDetail(@PathVariable String repositoryId,
                                              @PathVariable String pluginId) {
        return marketService.pluginDetail(repositoryId, pluginId);
    }

    /**
     * 按 {@code repositoryId} + {@code pluginId} + {@code version} 安装。<b>三者均为路径变量、包地址由受信仓库解析，
     * 请求体不含 URL</b>。下载 + 校验 + 落盘后返回与本地上传安装一致的 {@link PluginInstallResponse}（稳定 {@code outcome}
     * + 本地化 {@code message}，含完整性不符 {@code REJECTED_INTEGRITY} 等结局）；catalog / 下载层失败经 {@code @ExceptionHandler}
     * 映射为稳定错误响应。
     */
    @PostMapping("/{repositoryId}/{pluginId}/{version}/install")
    public ResponseEntity<PluginInstallResponse> install(@PathVariable String repositoryId,
                                                         @PathVariable String pluginId,
                                                         @PathVariable String version,
                                                         HttpServletRequest request) {
        PluginInstallReport report = marketService.install(repositoryId, pluginId, version);
        return installResponseMapper.toResponse(report, request);
    }

    /**
     * catalog / 下载层失败：返回「稳定机器码 {@code code} + 本地化 {@code message} + 诊断上下文」。{@code code} 取
     * {@code PluginCatalogErrorCode.name()}（与界面语言无关），HTTP 状态与 i18n 文案 key 均由稳定码派生。
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
