package top.sywyar.pixivdownload.setup;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.BooleanSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.annotations.media.SchemaProperty;

import top.sywyar.pixivdownload.plugin.api.web.HttpApiNullable;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import top.sywyar.pixivdownload.plugin.api.web.HttpApiGroup;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import top.sywyar.pixivdownload.common.GuiTokenProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.sywyar.pixivdownload.gui.ToolApiRequests;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.i18n.NamespaceMessageResolver;
import top.sywyar.pixivdownload.i18n.LocalizedException;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;
import top.sywyar.pixivdownload.plugin.api.web.ProgramIdentity;
import java.io.IOException;
import java.util.*;

/** 管理员会话与受保护的本机 GUI 共用密钥管理；完整秘密仅创建时返回。 */
@HttpApiGroup("core-keys")
@RestController

public final class ProgramKeysController {
    public static void describePolicySchema(Components components) {
        var policy = components.getSchemas().get(ProgramKeyStore.Policy.class.getCanonicalName());
        if (policy == null) return;
        var enabled = new BooleanSchema();
        enabled.setConst(true);
        policy.setIf(new ObjectSchema().additionalProperties(true).addProperty("anyIp", enabled).addRequiredItem("anyIp"));
        policy.setThen(new ObjectSchema().additionalProperties(true).addProperty("networks", new ArraySchema().maxItems(0)));
        policy.setElse(new ObjectSchema().additionalProperties(true).addProperty("networks", new ArraySchema().minItems(1)));
    }

    static final int BODY_LIMIT = 128 * 1024;
    private final ProgramKeyService keys;
    private final SetupService setup;
    private final AppMessages messages;
    private final ToolApiRequests requests;
    private final GuiTokenProvider guiTokens;
    private final NamespaceMessageResolver labels;
    public ProgramKeysController(ProgramKeyService keys, SetupService setup, AppMessages messages, ToolApiRequests requests,
                                 GuiTokenProvider guiTokens, NamespaceMessageResolver labels) {
        this.keys = keys; this.setup = setup; this.messages = messages; this.requests = requests;
        this.guiTokens = guiTokens;
        this.labels = labels;
    }
    @GetMapping(ProgramAccess.SELF_PATH)
    @io.swagger.v3.oas.annotations.Operation(operationId = "getCurrentProgramKey")
    public ResponseEntity<ProgramIdentity> current(HttpServletRequest request) {
        return response(200, ProgramAccess.identity(request).orElseThrow(() -> new LocalizedException(
                HttpStatus.UNAUTHORIZED, "auth.program.required", "This operation requires a program key")));
    }

    record Edit(long revision, ProgramKeyStore.Policy policy) {}
    record Revision(long revision) {}
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @GetMapping("/api/security/program-keys")
    public ResponseEntity<List<ProgramKeyStore.View>> list(HttpServletRequest request) throws IOException {
        admin(request); return response(200, keys.list());
    }
    record KeyRow(UUID id, long revision, String name, int permissionCount, boolean anyIp, List<String> networks,
                  long createdAt, @HttpApiNullable Long lastUsedAt, ProgramKeyStore.Transport transport) {
        static KeyRow from(ProgramKeyStore.View key) {
            var policy = key.policy();
            return new KeyRow(key.id(), key.revision(), policy.name(), policy.permissions().size(), policy.anyIp(),
                    policy.networks(), key.createdAt(), key.lastUsedAt(), key.transport());
        }
    }

    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @GetMapping("/api/gui/program-keys")
    public ResponseEntity<List<KeyRow>> desktopList(HttpServletRequest request) throws IOException {
        admin(request);
        return response(200, keys.list().stream().map(KeyRow::from).toList());
    }

    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = "auth.program.not-found"))))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @GetMapping("/api/gui/program-keys/{id}")
    public ResponseEntity<ProgramKeyStore.View> desktopKey(@PathVariable UUID id, HttpServletRequest request) throws IOException {
        admin(request);
        return response(200, keys.get(id));
    }

    record DesktopPermission(String id, String owner, String ownerLabel, String label) {}

    @GetMapping("/api/gui/program-keys/permissions")
    public ResponseEntity<List<DesktopPermission>> desktopPermissions(HttpServletRequest request, Locale locale) {
        admin(request);
        return response(200, keys.permissions().stream().map(permission -> {
            var ownerLabel = labels.resolve(permission.ownerNamespace(), locale, permission.ownerLabelKey())
                    .orElseGet(() -> permission.ownerNamespace() == null && permission.ownerLabelKey() != null
                            && !permission.ownerLabelKey().isBlank() ? permission.ownerLabelKey() : permission.owner());
            var label = labels.resolve(permission.namespace(), locale, permission.labelKey()).orElse(permission.id());
            return new DesktopPermission(permission.id(), permission.owner(), ownerLabel, label);
        }).toList());
    }

    @GetMapping("/api/security/program-keys/permissions")
    public ResponseEntity<List<ProgramKeyService.Permission>> permissions(HttpServletRequest request) {
        admin(request); return response(200, keys.permissions());
    }
    record IssuedKey(UUID id, String secret) {
        @Override public String toString() { return "IssuedKey[redacted]"; }
    }

    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = "application/json", schema = @Schema(implementation = ProgramKeyStore.Policy.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Copy the key now", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "201", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = {"auth.program.name-invalid", "auth.program.permissions-invalid", "auth.program.networks-invalid", ToolApiRequests.INVALID_TOOL_REQUEST}))))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = {"auth.program.capacity", "auth.program.changed"}))))
    @ApiResponse(responseCode = "413", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ToolApiRequests.BodyLimit.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @PostMapping("/api/gui/program-keys")
    public ResponseEntity<IssuedKey> createFromGui(HttpServletRequest request) throws IOException {
        var created = Objects.requireNonNull(create(request).getBody());
        return response(201, new IssuedKey(created.key().id(), created.secret()));
    }

    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = "application/json", schema = @Schema(implementation = ProgramKeyStore.Policy.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201",
            description = "Key created. Copy the secret before closing this response.", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "201", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = {"auth.program.name-invalid", "auth.program.permissions-invalid", "auth.program.networks-invalid", ToolApiRequests.INVALID_TOOL_REQUEST}))))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = {"auth.program.capacity", "auth.program.changed"}))))
    @ApiResponse(responseCode = "413", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ToolApiRequests.BodyLimit.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @PostMapping("/api/security/program-keys")
    public ResponseEntity<ProgramKeyStore.Created> create(HttpServletRequest request) throws IOException {
        admin(request);
        return response(201, keys.create(ToolApiRequests.read(request, ProgramKeyStore.Policy.class, BODY_LIMIT)));
    }
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = "application/json", schema = @Schema(implementation = Edit.class),
            examples = @io.swagger.v3.oas.annotations.media.ExampleObject(value = """
                    {"revision":3,"policy":{"name":"download-monitor","permissions":["core:downloads.read"],"anyIp":false,"networks":["127.0.0.1"]}}
                    """)))
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = {"auth.program.name-invalid", "auth.program.permissions-invalid", "auth.program.networks-invalid", ToolApiRequests.INVALID_TOOL_REQUEST}))))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = "auth.program.not-found"))))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = {"auth.program.capacity", "auth.program.changed"}))))
    @ApiResponse(responseCode = "413", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ToolApiRequests.BodyLimit.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @PutMapping("/api/security/program-keys/{id}")
    public ResponseEntity<ProgramKeyStore.View> update(@PathVariable UUID id, HttpServletRequest request) throws IOException {
        admin(request);
        var edit = ToolApiRequests.read(request, Edit.class, BODY_LIMIT);
        if (edit.policy() == null) throw new IllegalArgumentException("PROGRAM_KEY_PERMISSIONS");
        return response(200, keys.update(id, edit.revision(), edit.policy()));
    }
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = "application/json", schema = @Schema(implementation = Revision.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Key revoked")
    @ApiResponse(responseCode = "204", content = @Content)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = {"auth.program.name-invalid", "auth.program.permissions-invalid", "auth.program.networks-invalid", ToolApiRequests.INVALID_TOOL_REQUEST}))))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = "auth.program.not-found"))))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = {"auth.program.capacity", "auth.program.changed"}))))
    @ApiResponse(responseCode = "413", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ToolApiRequests.BodyLimit.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @DeleteMapping("/api/security/program-keys/{id}")
    public ResponseEntity<Void> revoke(@PathVariable UUID id, HttpServletRequest request) throws IOException {
        admin(request);
        keys.revoke(id, ToolApiRequests.read(request, Revision.class, BODY_LIMIT).revision());
        return response(204, null);
    }
    record ChangeTransport(long revision, ProgramKeyStore.Transport transport) {}

    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = "application/json", schema = @Schema(implementation = ChangeTransport.class)))
    @io.swagger.v3.oas.annotations.Operation(summary = "Change this key's public HTTP policy",
            description = "Send REVIEWED at the current revision. After confirming the risk again, send HTTP_ALLOWED "
                    + "with the returned revision. HTTPS_REQUIRED removes the exception. Editing the key also removes it.")
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = {"auth.program.name-invalid", "auth.program.permissions-invalid", "auth.program.networks-invalid", ToolApiRequests.INVALID_TOOL_REQUEST}))))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = "auth.program.not-found"))))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = {"auth.program.capacity", "auth.program.changed"}))))
    @ApiResponse(responseCode = "413", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ToolApiRequests.BodyLimit.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @PostMapping("/api/security/program-keys/{id}/transport")
    public ResponseEntity<ProgramKeyStore.View> changeTransport(@PathVariable UUID id, HttpServletRequest request) throws IOException {
        admin(request);
        var change = ToolApiRequests.read(request, ChangeTransport.class, BODY_LIMIT);
        if (change.transport() == null) throw new IllegalArgumentException("Missing transport policy");
        return response(200, keys.transport(id, change.revision(), change.transport()));
    }

    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = "application/json", schema = @Schema(implementation = ChangeTransport.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Transport policy updated")
    @ApiResponse(responseCode = "204", content = @Content)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = {"auth.program.name-invalid", "auth.program.permissions-invalid", "auth.program.networks-invalid", ToolApiRequests.INVALID_TOOL_REQUEST}))))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = "auth.program.not-found"))))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = {"auth.program.capacity", "auth.program.changed"}))))
    @ApiResponse(responseCode = "413", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ToolApiRequests.BodyLimit.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @PostMapping("/api/gui/program-keys/{id}/transport")
    public ResponseEntity<Void> transportFromGui(@PathVariable UUID id, HttpServletRequest request) throws IOException {
        changeTransport(id, request);
        return response(204, null);
    }

    private void admin(HttpServletRequest request) {
        if (request.getRequestURI().startsWith(request.getContextPath() + "/api/gui/")) {
            if (GuiTokenProvider.accepts(guiTokens, request)) return;
            throw new LocalizedException(HttpStatus.FORBIDDEN, "auth.local-only", "Local GUI authentication is required");
        }
        if (!setup.isAdminLoggedIn(request)) throw new LocalizedException(HttpStatus.FORBIDDEN,
                "auth.program.management", "Sign in as administrator to manage program keys");
    }

    /** 本机 GUI 通道只发送 GET / POST，写入仍复用相同修订与持久化校验。 */
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = "application/json", schema = @Schema(implementation = Edit.class),
            examples = @io.swagger.v3.oas.annotations.media.ExampleObject(value = """
                    {"revision":3,"policy":{"name":"download-monitor","permissions":["core:downloads.read"],"anyIp":false,"networks":["127.0.0.1"]}}
                    """)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Key updated")
    @ApiResponse(responseCode = "204", content = @Content)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = {"auth.program.name-invalid", "auth.program.permissions-invalid", "auth.program.networks-invalid", ToolApiRequests.INVALID_TOOL_REQUEST}))))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = "auth.program.not-found"))))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = {"auth.program.capacity", "auth.program.changed"}))))
    @ApiResponse(responseCode = "413", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ToolApiRequests.BodyLimit.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @PostMapping("/api/gui/program-keys/{id}/update")
    public ResponseEntity<Void> updateFromGui(@PathVariable UUID id, HttpServletRequest request) throws IOException {
        update(id, request);
        return response(204, null);
    }

    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = "application/json", schema = @Schema(implementation = Revision.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Key revoked")
    @ApiResponse(responseCode = "204", content = @Content)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = {"auth.program.name-invalid", "auth.program.permissions-invalid", "auth.program.networks-invalid", ToolApiRequests.INVALID_TOOL_REQUEST}))))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = "auth.program.not-found"))))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = {"auth.program.capacity", "auth.program.changed"}))))
    @ApiResponse(responseCode = "413", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ToolApiRequests.BodyLimit.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @PostMapping("/api/gui/program-keys/{id}/revoke")
    public ResponseEntity<Void> revokeFromGui(@PathVariable UUID id, HttpServletRequest request) throws IOException {
        return revoke(id, request);
    }

    private static <T> ResponseEntity<T> response(int status, T body) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(body);
    }
    @ExceptionHandler(IOException.class)
    public ResponseEntity<?> unavailable(IOException failure, Locale locale) {
        org.slf4j.LoggerFactory.getLogger(getClass()).warn("Program key storage unavailable: failureType={}", failure.getClass().getSimpleName());
        return response(503, ApiErrorResponse.of("PROGRAM_KEY_STORE_UNAVAILABLE", messages.get(locale, "auth.program.unavailable")));
    }
    @ExceptionHandler({IllegalStateException.class, NoSuchElementException.class})
    public ResponseEntity<?> conflict(RuntimeException failure, Locale locale) {
        String key = failure instanceof NoSuchElementException ? "auth.program.not-found"
                : "PROGRAM_KEY_CAPACITY".equals(failure.getMessage()) ? "auth.program.capacity" : "auth.program.changed";
        return response(failure instanceof NoSuchElementException ? 404 : 409,
                ApiErrorResponse.of(key, messages.get(locale, key, ProgramKeyStore.MAX_KEYS)));
    }
    @ExceptionHandler({IllegalArgumentException.class, tools.jackson.core.JacksonException.class})
    public ResponseEntity<?> invalid(Exception failure, Locale locale) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            String key = switch (String.valueOf(cause.getMessage())) {
                case "PROGRAM_KEY_NAME" -> "auth.program.name-invalid";
                case "PROGRAM_KEY_PERMISSIONS" -> "auth.program.permissions-invalid";
                case "PROGRAM_KEY_NETWORKS" -> "auth.program.networks-invalid";
                default -> null;
            };
            if (key != null) return response(400, ApiErrorResponse.of(key, messages.get(locale, key,
                    ProgramKeyStore.MAX_NAME_LENGTH, ProgramKeyStore.MAX_PERMISSIONS, ProgramKeyStore.MAX_NETWORKS)));
        }
        var result = requests.invalid(failure, locale, BODY_LIMIT);
        return response(result.getStatusCode().value(), result.getBody());
    }
}
