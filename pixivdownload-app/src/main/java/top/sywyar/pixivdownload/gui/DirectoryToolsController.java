package top.sywyar.pixivdownload.gui;

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

    @GetMapping
    public ResponseEntity<?> check(@RequestParam(defaultValue = "0") int offset,
                                   @RequestParam(defaultValue = "100") int limit, Locale locale) throws SQLException {
        if (offset < 0 || limit < 1 || limit > 1000) throw new IllegalArgumentException();
        if (!checking.compareAndSet(false, true)) return ResponseEntity.status(409)
                .body(ApiErrorResponse.of("DIRECTORY_CHECK_BUSY", messages.get(locale, "gui.message.backend-busy")));
        try (var connection = dataSource.getConnection()) {
            target.requireConnectionTarget(connection);
            return ResponseEntity.ok(tools.checkArtworkFolders(connection, target, download.getRootFolder(), offset, limit));
        } finally { checking.set(false); }
    }

    @PostMapping(consumes = "application/json")
    public ResponseEntity<Void> update(HttpServletRequest request) throws IOException, SQLException {
        var body = ToolApiRequests.read(request, Update.class, BODY_LIMIT);
        if (body.artworkId() < 1 || body.path() == null || body.path().isBlank()
                || body.path().length() > 4096 || body.path().chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException();
        Path.of(body.path());
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
