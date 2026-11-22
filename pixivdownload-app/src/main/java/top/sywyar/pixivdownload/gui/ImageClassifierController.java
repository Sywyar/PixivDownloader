package top.sywyar.pixivdownload.gui;

import top.sywyar.pixivdownload.common.InvalidPage;
import top.sywyar.pixivdownload.core.web.HttpParameterException;
import top.sywyar.pixivdownload.plugin.api.contract.InputFieldException;

import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.Parameter;
import top.sywyar.pixivdownload.plugin.api.web.HttpApiGroup;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.sywyar.pixivdownload.common.PlainFilePathGuard;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.download.ArtworkMoveService;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.work.service.WorkFileLock;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.Locale;

/** 分类操作使用服务器文件系统，共用桌面复制、回滚和目标编号逻辑。 */
@HttpApiGroup("core-classifier")
@RestController
@RequestMapping("/api/tools/classifier")
public final class ImageClassifierController {
    private static final int BODY_LIMIT = 64 * 1024;
    private static final int DIRECTORY_LIMIT = 100_000;
    private static final int PATH_CHARACTER_LIMIT = 16 * 1024 * 1024;
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ImageClassifierController.class);
    private final DesktopUiTools tools = new DesktopUiTools(() -> "");
    private final DownloadConfig download;
    private final ArtworkMoveService moves;
    private final ToolApiRequests requests;
    private final AppMessages messages;

    public ImageClassifierController(DownloadConfig download, ArtworkMoveService moves,
                                     ToolApiRequests requests, AppMessages messages) {
        this.download = download; this.moves = moves; this.requests = requests; this.messages = messages;
    }

    public record Page(List<String> paths, int total) {}
    public record Move(String source, String target, long artworkId) {}
    public record Moved(String destination, boolean sourceRemoved, boolean recorded, List<String> issues) {}

    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "500", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @GetMapping("/settings")
    public DesktopUiHost.ImageClassifierSettings settings() throws IOException {
        var settings = tools.loadImageClassifierSettings(download.getRootFolder());
        return new DesktopUiHost.ImageClassifierSettings(settings.defaultFolder(), settings.showSkipButton(),
                safeUrl(settings.serverUrl()), settings.targets());
    }

    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = "application/json", schema = @Schema(implementation = DesktopUiHost.ImageClassifierSettings.class)))
    @ApiResponse(responseCode = "204", content = @Content)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "413", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ToolApiRequests.BodyLimit.class)))
    @ApiResponse(responseCode = "500", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @PutMapping(value = "/settings", consumes = "application/json")
    public ResponseEntity<Void> settings(HttpServletRequest request) throws IOException {
        var settings = ToolApiRequests.read(request, DesktopUiHost.ImageClassifierSettings.class, BODY_LIMIT);
        if (settings.targets().size() > 20) throw new InputFieldException("/targets", "too_large");
        validatePathText(settings.defaultFolder(), true, "body", "/defaultFolder");
        if (!settings.serverUrl().equals(safeUrl(settings.serverUrl()))) throw new InputFieldException("/serverUrl", "invalid_format");
        for (int i = 0; i < settings.targets().size(); i++) {
            var target = settings.targets().get(i);
            String pointer = "/targets/" + i;
            validatePathText(target.folder(), true, "body", pointer + "/folder");
            if (target.remark() == null) throw new InputFieldException(pointer + "/remark", "required");
            if (target.remark().length() > 4096) throw new InputFieldException(pointer + "/remark", "too_large");
        }
        tools.saveImageClassifierSettings(download.getRootFolder(), settings);
        LOG.info("Classifier settings saved through HTTP targets={}", settings.targets().size());
        return ResponseEntity.noContent().build();
    }

    @Parameter(name = "offset", in = ParameterIn.QUERY, schema = @Schema(type = "integer", format = "int32", defaultValue = "0", minimum = "0"))
    @Parameter(name = "limit", in = ParameterIn.QUERY, schema = @Schema(type = "integer", format = "int32", defaultValue = "100", minimum = "1", maximum = "1000"))
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "500", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @GetMapping("/folders")
    public Page folders(@RequestParam String path, @RequestParam(defaultValue = "0") int offset,
                        @RequestParam(defaultValue = "100") int limit) throws IOException {
        InvalidPage.check(offset, limit, 1000, null);
        return page(tools.listClassifierPaths(directory(path(path, "query", "path")), true, DIRECTORY_LIMIT, PATH_CHARACTER_LIMIT), offset, limit);
    }

    @Parameter(name = "offset", in = ParameterIn.QUERY, schema = @Schema(type = "integer", format = "int32", defaultValue = "0", minimum = "0"))
    @Parameter(name = "limit", in = ParameterIn.QUERY, schema = @Schema(type = "integer", format = "int32", defaultValue = "100", minimum = "1", maximum = "1000"))
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "500", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @GetMapping("/images")
    public Page images(@RequestParam String path, @RequestParam(defaultValue = "0") int offset,
                       @RequestParam(defaultValue = "100") int limit) throws IOException {
        InvalidPage.check(offset, limit, 1000, null);
        return page(tools.listClassifierPaths(directory(path(path, "query", "path")), false, DIRECTORY_LIMIT, PATH_CHARACTER_LIMIT), offset, limit);
    }

    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/octet-stream", schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "500", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @GetMapping("/image")
    public ResponseEntity<org.springframework.core.io.InputStreamResource> image(@RequestParam String path) throws IOException {
        Path file = path(path, "query", "path");
        if (!DesktopUiTools.isSupportedImage(file)) throw new HttpParameterException("query", "path", "unsupported");
        PlainFilePathGuard.requirePlainRegularFile(file);
        return ResponseEntity.ok().header("Content-Type", "application/octet-stream")
                .header("Content-Disposition", "attachment").header("Cache-Control", "no-store")
                .header("X-Content-Type-Options", "nosniff").header("Content-Security-Policy", "sandbox")
                .body(new org.springframework.core.io.InputStreamResource(Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)));
    }

    @ApiResponse(responseCode = "204", content = @Content)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "500", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @DeleteMapping("/folders")
    public ResponseEntity<Void> deleteEmpty(@RequestParam String path) throws IOException {
        Path directory = directory(path(path, "query", "path"));
        try (var children = Files.newDirectoryStream(directory)) {
            if (children.iterator().hasNext()) throw new DirectoryNotEmptyException(directory.toString());
        }
        Files.delete(directory);
        return ResponseEntity.noContent().build();
    }

    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = "application/json", schema = @Schema(implementation = Move.class)))
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "413", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ToolApiRequests.BodyLimit.class)))
    @ApiResponse(responseCode = "500", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @PostMapping(value = "/moves", consumes = "application/json")
    public Moved move(HttpServletRequest request) throws IOException {
        var body = ToolApiRequests.read(request, Move.class, BODY_LIMIT);
        if (body.artworkId() < 1) throw new InputFieldException("/artworkId", "out_of_range");
        Path source = path(body.source(), "body", "/source");
        Path target = path(body.target(), "body", "/target");
        if (source.startsWith(target) || target.startsWith(source)) throw new InputFieldException("/target", "invalid_value");
        try (var work = WorkFileLock.acquire(WorkType.ARTWORK, body.artworkId())) {
            directory(source);
            var images = tools.listClassifierPaths(source, false, DIRECTORY_LIMIT, PATH_CHARACTER_LIMIT);
            if (images.isEmpty()) throw new IllegalArgumentException("CLASSIFIER_EMPTY");
            PlainFilePathGuard.requirePlainParent(target.resolve(".classifier-path-check"), true);
            Path destination = tools.classifyImageFolder(source, images, body.artworkId(), target, null,
                    (failure, folder) -> false);
            boolean removed = !Files.exists(source, LinkOption.NOFOLLOW_LINKS);
            boolean recorded = false;
            try {
                recorded = moves.recordMove(body.artworkId(), destination.toString(), System.currentTimeMillis(), target.toString());
            } catch (RuntimeException failed) {
                LOG.warn("Classifier move recording failed artworkId={} type={}", body.artworkId(), failed.getClass().getSimpleName());
            }
            var issues = new java.util.ArrayList<String>();
            if (!removed) issues.add("CLASSIFIER_SOURCE_REMAINS");
            if (!recorded) issues.add("CLASSIFIER_RECORD_NOT_CONFIRMED");
            LOG.info("Classifier files moved artworkId={} sourceRemoved={} recorded={}", body.artworkId(), removed, recorded);
            return new Moved(destination.toString(), removed, recorded, List.copyOf(issues));
        }
    }

    private static Path directory(Path directory) throws IOException {
        PlainFilePathGuard.requirePlainParent(directory.resolve(".classifier-path-check"), false);
        int count = 0;
        long characters = 0;
        try (var children = Files.newDirectoryStream(directory)) {
            for (Path child : children) {
                if (++count > DIRECTORY_LIMIT || (characters += child.toString().length()) > PATH_CHARACTER_LIMIT)
                    throw new IllegalArgumentException("CLASSIFIER_DIRECTORY_LIMIT");
                if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS))
                    PlainFilePathGuard.requirePlainParent(child.resolve(".classifier-path-check"), false);
                else PlainFilePathGuard.requirePlainRegularFile(child);
            }
        }
        return directory;
    }

    private static Path path(String value, String in, String field) {
        validatePathText(value, false, in, field);
        Path path;
        try {
            path = Path.of(value);
        } catch (InvalidPathException invalid) {
            throw new HttpParameterException(in, field, "invalid_format");
        }
        if (!path.isAbsolute()) throw new HttpParameterException(in, field, "invalid_format");
        return path.normalize();
    }
    private static void validatePathText(String value, boolean emptyAllowed, String in, String field) {
        if (value == null || !emptyAllowed && value.isBlank()) throw new HttpParameterException(in, field, "required");
        if (value.length() > 4096) throw new HttpParameterException(in, field, "too_large");
        if (value.chars().anyMatch(Character::isISOControl)) throw new HttpParameterException(in, field, "invalid_format");
    }
    private static Page page(List<Path> paths, int offset, int limit) {
        return new Page(paths.stream().skip(offset).limit(limit).map(Path::toString).toList(), paths.size());
    }
    private static String safeUrl(String value) {
        if (value == null || value.isBlank()) return "";
        try {
            var uri = java.net.URI.create(value);
            if (!List.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null) return "";
            return new java.net.URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), null, null).toString();
        } catch (IllegalArgumentException | java.net.URISyntaxException invalid) { return ""; }
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<ApiErrorResponse> failed(IOException failure, Locale locale) {
        String code = failure instanceof DesktopUiTools.ClassificationBusyException ? "CLASSIFIER_BUSY"
                : failure instanceof FileAlreadyExistsException ? "CLASSIFIER_TARGET_EXISTS"
                : failure instanceof DirectoryNotEmptyException ? "CLASSIFIER_DIRECTORY_NOT_EMPTY"
                : failure instanceof NoSuchFileException ? "CLASSIFIER_PATH_NOT_FOUND"
                : failure instanceof AccessDeniedException ? "CLASSIFIER_ACCESS_DENIED" : "CLASSIFIER_IO_FAILED";
        int status = code.equals("CLASSIFIER_PATH_NOT_FOUND") ? 404
                : code.equals("CLASSIFIER_IO_FAILED") ? 500 : 409;
        LOG.warn("Classifier HTTP operation failed code={} type={}", code, failure.getClass().getSimpleName());
        return ResponseEntity.status(status).body(ApiErrorResponse.of(code, messages.get(locale, "classifier.api." + code)));
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpParameterException.class, tools.jackson.core.JacksonException.class})
    public ResponseEntity<ApiErrorResponse> invalid(Exception failure, Locale locale) {
        if ("CLASSIFIER_EMPTY".equals(failure.getMessage()) || "CLASSIFIER_DIRECTORY_LIMIT".equals(failure.getMessage()))
            return ResponseEntity.badRequest().body(ApiErrorResponse.of(failure.getMessage(),
                    messages.get(locale, "classifier.api." + failure.getMessage(), DIRECTORY_LIMIT, PATH_CHARACTER_LIMIT)));
        return requests.invalid(failure, locale, BODY_LIMIT);
    }
}
