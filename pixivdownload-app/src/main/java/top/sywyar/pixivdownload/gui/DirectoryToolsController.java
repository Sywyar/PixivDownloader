package top.sywyar.pixivdownload.gui;

import top.sywyar.pixivdownload.common.InvalidPage;
import top.sywyar.pixivdownload.plugin.api.contract.InputFieldException;

import io.swagger.v3.oas.annotations.media.SchemaProperty;

import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.Parameter;
import top.sywyar.pixivdownload.plugin.api.web.HttpApiGroup;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.db.DatabaseTarget;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopDatabaseSettings;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** 检查和修正当前数据库的作品路径；不移动或删除作品文件。 */
@HttpApiGroup("core-files")
@RestController
@RequestMapping("/api/tools/directories")
public final class DirectoryToolsController {
    private static final int BODY_LIMIT = 32 * 1024;
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(DirectoryToolsController.class);
    private final DesktopUiTools tools = new DesktopUiTools(() -> "");
    private final AtomicBoolean checking = new AtomicBoolean();
    private final DataSource dataSource;
    private final DatabaseTarget target;
    private final DownloadConfig download;
    private final ToolApiRequests requests;
    private final AppMessages messages;

    public DirectoryToolsController(DataSource dataSource, DatabaseTarget target, DownloadConfig download,
                                    ToolApiRequests requests, AppMessages messages) {
        this.dataSource = dataSource;
        this.target = target;
        this.download = download;
        this.requests = requests;
        this.messages = messages;
    }

    public record Update(long artworkId, boolean moved, String path) {}

    @Parameter(name = "offset", in = ParameterIn.QUERY, schema = @Schema(type = "integer", format = "int32", defaultValue = "0", minimum = "0"))
    @Parameter(name = "limit", in = ParameterIn.QUERY, schema = @Schema(type = "integer", format = "int32", defaultValue = "100", minimum = "1", maximum = "1000"))
    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json",
            schema = @Schema(implementation = DesktopUiTools.FolderPage.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = "DIRECTORY_CHECK_BUSY"))))
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ToolApiRequests.InvalidRequest.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @GetMapping
    public ResponseEntity<?> check(@RequestParam(defaultValue = "0") int offset,
                                   @RequestParam(defaultValue = "100") int limit, Locale locale) throws SQLException {
        InvalidPage.check(offset, limit, 1000, null);
        if (!checking.compareAndSet(false, true)) return ResponseEntity.status(409)
                .body(ApiErrorResponse.of("DIRECTORY_CHECK_BUSY", messages.get(locale, "gui.message.backend-busy")));
        try (var connection = dataSource.getConnection()) {
            target.requireConnectionTarget(connection);
            return ResponseEntity.ok(tools.checkArtworkFolders(connection, target, download.getRootFolder(), offset, limit));
        } finally { checking.set(false); }
    }

    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = "application/json", schema = @Schema(implementation = Update.class)))
    @ApiResponse(responseCode = "204", content = @Content)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ToolApiRequests.InvalidRequest.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = "ARTWORK_NOT_FOUND"))))
    @ApiResponse(responseCode = "413", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ToolApiRequests.BodyLimit.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @PostMapping(consumes = "application/json")
    public ResponseEntity<Void> update(HttpServletRequest request) throws IOException, SQLException {
        var body = ToolApiRequests.read(request, Update.class, BODY_LIMIT);
        if (body.artworkId() < 1) throw new InputFieldException("/artworkId", "out_of_range");
        if (body.path() == null || body.path().isBlank()) throw new InputFieldException("/path", "required");
        if (body.path().length() > 4096) throw new InputFieldException("/path", "too_large");
        if (body.path().chars().anyMatch(Character::isISOControl)) throw new InputFieldException("/path", "invalid_format");
        try {
            Path.of(body.path());
        } catch (java.nio.file.InvalidPathException invalid) {
            throw new InputFieldException("/path", "invalid_format");
        }
        try (var connection = dataSource.getConnection()) {
            target.requireConnectionTarget(connection);
            tools.updateArtworkFolder(connection, target, download.getRootFolder(), body.artworkId(), body.moved(), body.path());
        }
        LOG.info("Artwork directory reference updated through HTTP artworkId={} moved={}", body.artworkId(), body.moved());
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(SQLException.class)
    public ResponseEntity<ApiErrorResponse> failed(SQLException failure, Locale locale) {
        String code = "02000".equals(failure.getSQLState()) ? "ARTWORK_NOT_FOUND" : "DIRECTORY_DATABASE_FAILED";
        String key = code.equals("ARTWORK_NOT_FOUND") ? "error.request.not-found"
                : DesktopDatabaseSettings.failureMessageKey(failure.getSQLState());
        LOG.warn("Directory HTTP operation failed code={} failureType={}", code, failure.getClass().getName());
        return ResponseEntity.status(code.equals("ARTWORK_NOT_FOUND") ? 404 : 503)
                .body(ApiErrorResponse.of(code, messages.get(locale, key)));
    }

    @ExceptionHandler({IllegalArgumentException.class, tools.jackson.core.JacksonException.class})
    public ResponseEntity<ApiErrorResponse> invalid(Exception failure, Locale locale) {
        return requests.invalid(failure, locale, BODY_LIMIT);
    }
}
