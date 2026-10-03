package top.sywyar.pixivdownload.plugin.market.content;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.i18n.AppLocaleResolver;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;
import top.sywyar.pixivdownload.plugin.catalog.content.PluginCatalogContentService;
import top.sywyar.pixivdownload.plugin.catalog.error.PluginCatalogErrorResponse;
import top.sywyar.pixivdownload.plugin.catalog.error.PluginCatalogException;

/** 展示附件沿用市场的 ADMIN 路由，只接受目录身份与预期摘要。 */
@PluginManagedBean
@RestController
@RequestMapping("/api/plugin-market/content")
public final class PluginMarketContentController {
    private final PluginCatalogContentService content;
    private final AppMessages messages;
    private final AppLocaleResolver locales;

    public PluginMarketContentController(PluginCatalogContentService content, AppMessages messages,
                                         AppLocaleResolver locales) {
        this.content = content;
        this.messages = messages;
        this.locales = locales;
    }

    @GetMapping("/{repositoryId}/{pluginId}/{version}/{kind}")
    public ResponseEntity<PluginCatalogContentService.DocumentView> document(
            @PathVariable String repositoryId, @PathVariable String pluginId, @PathVariable String version,
            @PathVariable String kind, @RequestParam String locale, @RequestParam String sha256) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(content.document(repositoryId, pluginId, version, kind, locale, sha256));
    }

    @GetMapping("/{repositoryId}/{pluginId}/image")
    public ResponseEntity<byte[]> image(@PathVariable String repositoryId, @PathVariable String pluginId,
            @RequestParam String role, @RequestParam(defaultValue = "0") int index, @RequestParam String sha256) {
        var image = content.image(repositoryId, pluginId, role, index, sha256);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.mediaType()))
                .cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff")
                .body(image.bytes());
    }

    @ExceptionHandler(PluginCatalogException.class)
    public ResponseEntity<PluginCatalogErrorResponse> handle(PluginCatalogException failure, HttpServletRequest request) {
        String message = messages.getOrDefault(locales.resolveLocale(request), failure.messageKey(), failure.getMessage());
        return ResponseEntity.status(failure.status()).cacheControl(CacheControl.noStore())
                .body(new PluginCatalogErrorResponse(failure.code().name(), message, failure.status().value(),
                        failure.pluginId(), failure.version(), failure.dependencyInstallResults()));
    }
}
