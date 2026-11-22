package top.sywyar.pixivdownload.gui.config;

import io.swagger.v3.oas.annotations.responses.ApiResponse;
import top.sywyar.pixivdownload.plugin.api.web.HttpApiGroup;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.gui.ToolApiRequests;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.plugin.api.gui.*;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogProperties;
import top.sywyar.pixivdownload.plugin.catalog.error.PluginCatalogErrorCode;
import top.sywyar.pixivdownload.plugin.catalog.error.PluginCatalogException;
import top.sywyar.pixivdownload.plugin.catalog.repository.PluginRepository;
import top.sywyar.pixivdownload.plugin.catalog.repository.PluginRepositoryImportService;
import top.sywyar.pixivdownload.plugin.catalog.repository.PluginRepositoryRegistry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 仓库设置的显式保存；描述符导入继续使用市场预览与信任入口。 */
@HttpApiGroup("core-market")
@RestController
@RequestMapping("/api/settings/repositories")
public final class RepositorySettingsController {
    private static final int BODY_LIMIT = 256 * 1024;
    private final DesktopUiHost.ConfigFile file;
    private final PluginRepositoryImportService imports;
    private final AppMessages messages;
    private final ToolApiRequests requests;

    @Autowired
    public RepositorySettingsController(PluginRepositoryImportService imports, AppMessages messages, ToolApiRequests requests) {
        this(new ConfigFileEditor(RuntimeFiles.resolveConfigYamlPath()).asConfigFile(), imports, messages, requests);
    }

    RepositorySettingsController(DesktopUiHost.ConfigFile file, PluginRepositoryImportService imports,
                                 AppMessages messages, ToolApiRequests requests) {
        this.file = file; this.imports = imports; this.messages = messages; this.requests = requests;
    }

    public record Snapshot(String revision, List<RepositoryConfigEntry> entries) {}
    public record Save(String revision, List<RepositoryConfigEntry> entries, Boolean trustConfirmed) {}
    public record Saved(boolean restartRequired) {}
    public record FieldError(String code, String error, int index) implements ApiErrorResponse {}
    private static final class InvalidEntry extends IllegalArgumentException {
        final int index;
        InvalidEntry(int index, String key) { super(key); this.index = index; }
    }

    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "500", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @GetMapping
    public Snapshot read() throws IOException {
        var result = new java.util.concurrent.atomic.AtomicReference<Snapshot>();
        file.withWriteLock(() -> result.set(new Snapshot(revision(file.snapshot()),
                new PluginRepositoryConfigEditor(file).read().stream().map(RepositorySettingsController::view).toList())));
        return result.get();
    }

    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = "application/json", schema = @Schema(implementation = Save.class)))
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(anyOf = {ApiErrorResponse.Basic.class, FieldError.class})))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "413", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ToolApiRequests.BodyLimit.class)))
    @ApiResponse(responseCode = "422", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "500", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @PutMapping(consumes = "application/json")
    public Saved save(HttpServletRequest request) throws IOException {
        var body = ToolApiRequests.read(request, Save.class, BODY_LIMIT);
        if (body.revision() == null || body.entries() == null || body.entries().size() > 256) throw new IllegalArgumentException();
        file.withWriteLock(() -> {
            if (!body.revision().equals(revision(file.snapshot()))) throw new IllegalArgumentException("SETTINGS_CHANGED");
            var editor = new PluginRepositoryConfigEditor(file);
            var previous = editor.read();
            var merged = new ArrayList<RepositoryConfigEntry>();
            for (int index = 0; index < body.entries().size(); index++) {
                var entry = body.entries().get(index);
                validate(entry, merged, index);
                var old = previous.stream().filter(value -> id(value).equals(id(entry))).findFirst().orElse(null);
                if (!Boolean.TRUE.equals(body.trustConfirmed()) && requiresTrust(old, entry))
                    throw new PluginCatalogException(PluginCatalogErrorCode.REPOSITORY_TRUST_CONFIRMATION_REQUIRED,
                            "Repository settings change requires explicit trust confirmation");
                var keys = entry.trustedKeys().stream().map(key -> new TrustedKeyConfigEntry(key.keyId(), key.algorithm(),
                        key.publicKey(), key.state(), key.publisher(), key.trustLabel(), old == null ? Map.of()
                        : old.trustedKeys().stream().filter(value -> value.keyId().equals(key.keyId()))
                                .findFirst().map(TrustedKeyConfigEntry::extraFields).orElse(Map.of()))).toList();
                merged.add(copy(entry, keys, old == null ? Map.of() : old.extraFields()));
            }
            // 用真实运行期绑定与仓库构造器核对信任根、保留标识和描述符元数据。
            try {
                var yaml = String.join("\n", PluginRepositoryConfigEditor.renderBlock(merged));
                var sources = new org.springframework.boot.env.YamlPropertySourceLoader().load("repository-draft",
                        new org.springframework.core.io.ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8)));
                var properties = new org.springframework.boot.context.properties.bind.Binder(
                        org.springframework.boot.context.properties.source.ConfigurationPropertySources.from(sources))
                        .bind("plugin-catalog", PluginCatalogProperties.class).orElseGet(PluginCatalogProperties::new);
                new PluginRepositoryRegistry(properties);
            } catch (RuntimeException invalid) { throw new IllegalArgumentException("INVALID_REPOSITORY_SETTINGS", invalid); }
            imports.writeDraft(file, merged);
        });
        org.slf4j.LoggerFactory.getLogger(getClass()).info("Repository settings saved through HTTP count={}", body.entries().size());
        return new Saved(true);
    }

    private static void validate(RepositoryConfigEntry entry, List<RepositoryConfigEntry> others, int index) {
        if (entry == null || !entry.extraFields().isEmpty()
                || entry.trustedKeys().stream().anyMatch(key -> !key.extraFields().isEmpty())) throw new IllegalArgumentException();
        var keys = new ArrayList<String>();
        keys.add(RepositorySettingsValidation.validateId(entry.id(), others, Set.of(
                PluginRepository.OFFICIAL_ID, PluginRepository.LEGACY_CONFIGURED_ID, PluginRepository.COMMUNITY_ID)));
        keys.add(RepositorySettingsValidation.validateManifestUrl(entry.manifestUrl(),
                !entry.proxyPolicy().equalsIgnoreCase("custom") || entry.strictHttps()));
        keys.add(RepositorySettingsValidation.validateProxyPolicy(entry.proxyPolicy()));
        keys.add(RepositorySettingsValidation.validateTimeoutOverride(optional(entry.connectTimeoutMs())));
        keys.add(RepositorySettingsValidation.validateTimeoutOverride(optional(entry.readTimeoutMs())));
        keys.add(RepositorySettingsValidation.validateSizeOverride(optional(entry.maxManifestBytes())));
        keys.add(RepositorySettingsValidation.validateSizeOverride(optional(entry.maxPackageBytes())));
        for (String key : keys) if (key != null) throw new InvalidEntry(index, key);
    }

    private static String optional(long value) { return value == 0 ? "" : Long.toString(value); }
    private static String id(RepositoryConfigEntry entry) { return entry.id().trim().toLowerCase(Locale.ROOT); }
    private static boolean requiresTrust(RepositoryConfigEntry old, RepositoryConfigEntry entry) {
        return old == null || !view(old).trustedKeys().equals(entry.trustedKeys())
                || !old.manifestUrl().equals(entry.manifestUrl()) || !old.proxyPolicy().equals(entry.proxyPolicy())
                || old.allowRedirects() != entry.allowRedirects() || old.strictHttps() != entry.strictHttps()
                || old.allowNonPublicAddresses() != entry.allowNonPublicAddresses();
    }
    private static RepositoryConfigEntry view(RepositoryConfigEntry entry) {
        return copy(entry, entry.trustedKeys().stream().map(key -> TrustedKeyConfigEntry.create(key.keyId(), key.algorithm(),
                key.publicKey(), key.state(), key.publisher(), key.trustLabel())).toList(), Map.of());
    }
    private static RepositoryConfigEntry copy(RepositoryConfigEntry entry, List<TrustedKeyConfigEntry> keys, Map<String, Object> extra) {
        return new RepositoryConfigEntry(entry.id(), entry.displayNameKey(), entry.manifestUrl(), entry.enabled(), entry.proxyPolicy(),
                entry.allowRedirects(), entry.strictHttps(), entry.allowNonPublicAddresses(), entry.useProxy(), entry.connectTimeoutMs(),
                entry.readTimeoutMs(), entry.maxManifestBytes(), entry.maxPackageBytes(), keys, extra);
    }
    private static String revision(DesktopUiHost.ConfigSnapshot snapshot) {
        try {
            var hash = java.security.MessageDigest.getInstance("SHA-256");
            hash.update((byte) (snapshot.existed() ? 1 : 0));
            return HexFormat.of().formatHex(hash.digest(String.join("\n", snapshot.lines()).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
    }

    @ExceptionHandler(PluginCatalogException.class)
    public ResponseEntity<ApiErrorResponse> catalogFailure(PluginCatalogException failure, Locale locale) {
        return ResponseEntity.status(failure.status()).body(ApiErrorResponse.of(failure.code().name(), messages.get(locale, failure.messageKey())));
    }
    @ExceptionHandler(IOException.class)
    public ResponseEntity<ApiErrorResponse> storageFailure(IOException failure, Locale locale) {
        org.slf4j.LoggerFactory.getLogger(getClass()).warn("Repository settings storage failed type={}", failure.getClass().getSimpleName());
        return ResponseEntity.internalServerError().body(ApiErrorResponse.of("SETTINGS_SAVE_FAILED", messages.get(locale, "desktop.ui.action.failed")));
    }
    @ExceptionHandler({IllegalArgumentException.class, tools.jackson.core.JacksonException.class})
    public ResponseEntity<? extends ApiErrorResponse> invalid(Exception failure, Locale locale) {
        if (failure instanceof InvalidEntry entry) return ResponseEntity.badRequest().body(new FieldError(
                "INVALID_REPOSITORY_FIELD", messages.get(locale, entry.getMessage()), entry.index));
        if ("SETTINGS_CHANGED".equals(failure.getMessage())) return ResponseEntity.status(409)
                .body(ApiErrorResponse.of("SETTINGS_CHANGED", messages.get(locale, "settings.api.stale")));
        return requests.invalid(failure, locale, BODY_LIMIT);
    }
}
