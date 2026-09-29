package top.sywyar.pixivdownload.plugin.catalog.operation;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.sywyar.pixivdownload.i18n.AppLocaleResolver;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.plugin.catalog.error.PluginCatalogErrorResponse;
import top.sywyar.pixivdownload.plugin.catalog.error.PluginCatalogException;
import top.sywyar.pixivdownload.plugin.install.PluginInstallResponse;
import top.sywyar.pixivdownload.plugin.install.PluginInstallResponseMapper;
import top.sywyar.pixivdownload.plugin.lifecycle.ExternalPluginOperation;

import java.time.Instant;
import java.util.List;

/** 获取动作归市场贡献；已发起操作的只读查询归核心管理面，市场卸下后仍可查询。 */
@RestController
public class PluginAcquisitionController {
    private final PluginAcquisitionOperations operations;
    private final PluginInstallResponseMapper mapper;
    private final AppMessages messages;
    private final AppLocaleResolver locales;

    public PluginAcquisitionController(PluginAcquisitionOperations operations, PluginInstallResponseMapper mapper,
                                       AppMessages messages, AppLocaleResolver locales) {
        this.operations = operations;
        this.mapper = mapper;
        this.messages = messages;
        this.locales = locales;
    }

    @PostMapping("/api/plugin-market/operations")
    public View prepare(@RequestBody PrepareRequest body, HttpServletRequest request) {
        return view(operations.prepare(body.repositoryId(), body.pluginId(), body.version(),
                body.fingerprint(), body.confirmTrust()), request);
    }

    @PostMapping("/api/plugin-market/operations/{id}/execute")
    public View execute(@PathVariable String id, HttpServletRequest request) {
        return view(operations.execute(id), request);
    }

    @GetMapping("/api/plugins/acquisitions/{id}")
    public View get(@PathVariable String id, HttpServletRequest request) {
        return view(operations.get(id), request);
    }

    @GetMapping("/api/plugins/acquisitions")
    public List<View> list(HttpServletRequest request) {
        return operations.list().stream().map(value -> view(value, request)).toList();
    }

    private View view(PluginAcquisitionOperations.Snapshot value, HttpServletRequest request) {
        var failure = value.failure();
        var error = failure == null ? null : new PluginCatalogErrorResponse(failure.code().name(),
                messages.getOrDefault(locales.resolveLocale(request), failure.code().messageKey(), failure.code().name()),
                failure.code().status().value(), failure.pluginId(), failure.version(), failure.dependencyInstallResults());
        return new View(value.id(), value.repositoryId(), value.pluginId(), value.version(), value.currentPluginId(),
                value.operation(), value.transactionId(), value.createdAt(), value.updatedAt(), value.started(), value.finished(),
                value.report() == null ? null : mapper.toResponse(value.report(), request).getBody(), error);
    }

    @ExceptionHandler(PluginCatalogException.class)
    public ResponseEntity<PluginCatalogErrorResponse> failure(PluginCatalogException failure, HttpServletRequest request) {
        return ResponseEntity.status(failure.status()).body(new PluginCatalogErrorResponse(failure.code().name(),
                messages.getOrDefault(locales.resolveLocale(request), failure.messageKey(), failure.code().name()),
                failure.status().value(), failure.pluginId(), failure.version(), failure.dependencyInstallResults()));
    }

    public record PrepareRequest(String repositoryId, String pluginId, String version, String fingerprint, String confirmTrust) { }
    public record View(String id, String repositoryId, String pluginId, String version, String currentPluginId,
            ExternalPluginOperation operation, String transactionId, Instant createdAt, Instant updatedAt,
            boolean started, boolean finished, PluginInstallResponse result, PluginCatalogErrorResponse failure) { }
}
