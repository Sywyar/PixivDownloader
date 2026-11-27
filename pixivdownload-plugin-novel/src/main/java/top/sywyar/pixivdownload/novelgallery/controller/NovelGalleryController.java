package top.sywyar.pixivdownload.novelgallery.controller;

import top.sywyar.pixivdownload.core.web.HttpParameterException;
import top.sywyar.pixivdownload.core.work.query.WorkQuery;
import jakarta.validation.Valid;
import top.sywyar.pixivdownload.core.web.HttpIds;
import top.sywyar.pixivdownload.core.web.CsvIds;
import io.swagger.v3.oas.annotations.links.Link;
import io.swagger.v3.oas.annotations.links.LinkParameter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import jakarta.servlet.http.HttpServletRequest;
import top.sywyar.pixivdownload.core.web.KnownErrorCodes;
import top.sywyar.pixivdownload.core.web.MissingProgramPermissionException;
import top.sywyar.pixivdownload.plugin.api.web.ProgramIdentity;
import top.sywyar.pixivdownload.core.work.query.WorkSelectionException;
import top.sywyar.pixivdownload.novel.NovelPlugin;
import io.swagger.v3.oas.annotations.Parameter;
import top.sywyar.pixivdownload.plugin.api.web.HttpApiNullable;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.SchemaProperty;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.sywyar.pixivdownload.core.archive.UnsupportedArchiveFormatException;
import top.sywyar.pixivdownload.core.archive.ArchiveExportResult;
import top.sywyar.pixivdownload.core.work.model.WorkAssetFile;
import top.sywyar.pixivdownload.core.work.model.WorkRestriction;
import top.sywyar.pixivdownload.core.work.model.WorkTag;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.work.model.WorkVisibilityScope;
import top.sywyar.pixivdownload.core.work.query.SeriesNeighbors;
import top.sywyar.pixivdownload.core.work.service.WorkAssetService;
import top.sywyar.pixivdownload.core.work.service.WorkVisibilityService;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.novel.NovelSeriesService;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.novel.db.NovelDownloadedStatusRow;
import top.sywyar.pixivdownload.novel.db.NovelRecord;
import top.sywyar.pixivdownload.novel.db.NovelSeries;
import top.sywyar.pixivdownload.novelgallery.NovelBatchRequest;
import top.sywyar.pixivdownload.novelgallery.NovelBatchService;
import top.sywyar.pixivdownload.novelgallery.NovelGalleryService;
import top.sywyar.pixivdownload.novelgallery.NovelTagOption;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;
import top.sywyar.pixivdownload.plugin.api.web.HttpApiGroup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * {@code @RestController} 仅供 Spring MVC handler 检测；Bean 本身被
 * {@code @PluginManagedBean} 排除出根包扫描，由 {@code NovelPluginConfiguration} 提供。
 */
@PluginManagedBean
@HttpApiGroup("novel-library")
@RestController
@RequestMapping("/api/gallery")
@RequiredArgsConstructor
@Slf4j
public class NovelGalleryController {

    private final NovelGalleryService novelGalleryService;
    private final NovelBatchService novelBatchService;
    private final NovelSeriesService novelSeriesService;
    private final NovelDatabase novelDatabase;
    private final WorkAssetService workAssetService;
    private final WorkVisibilityService workVisibilityService;
    private final MessageResolver messages;

    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json",
            schema = @Schema(implementation = NovelGalleryService.PagedNovels.class), examples = @ExampleObject(value = """
                    {"content":[{"novelId":42,"title":"Sea light","folder":"/srv/novels/42","extensions":"txt","time":1735689600000,
                       "xRestrict":0,"isAi":false,"authorId":7,"authorName":"Aoi","description":"A short story.",
                       "seriesId":null,"seriesOrder":null,"wordCount":860,"textLength":4300,"readingTimeSeconds":180,
                       "pageCount":1,"isOriginal":true,"xLanguage":"en","tags":[{"tagId":8,"name":"海","translatedName":"sea"}],
                       "coverExt":null,"embeddedImageIds":[],"translatedLanguages":[],"uploadTime":1735603200000,
                       "createTime":null,"updateTime":null,"reuploadTime":null}],
                     "totalElements":1,"page":0,"size":24,"totalPages":1}
                    """)))
    @GetMapping("/novels")
    public NovelGalleryService.PagedNovels listNovels(
            @Parameter(example = "0", schema = @Schema(type = "integer", format = "int32", defaultValue = "0"))
            @RequestParam(required = false, defaultValue = "0") Integer page,
            @RequestParam(required = false, defaultValue = "24") Integer size,
            @Parameter(schema = @Schema(defaultValue = "date", examples = {"date", "uploadTime", "createTime", "updateTime", "reuploadTime", "novelId", "series", "wordCount"}))
            @RequestParam(required = false, defaultValue = "date") String sort,
            @Parameter(schema = @Schema(defaultValue = "desc", examples = {"asc", "desc"}))
            @RequestParam(required = false, defaultValue = "desc") String order,
            @RequestParam(required = false) String search,
            @Parameter(schema = @Schema(defaultValue = "all", examples = {"all", "title", "author", "id", "authorId", "desc", "tag", "tagExact", "content"}))
            @RequestParam(required = false, defaultValue = "all") String searchType,
            @Parameter(schema = @Schema(defaultValue = "any", examples = {"any", "yes", "no", "r18", "r18g", "r18plus"}))
            @RequestParam(required = false, defaultValue = "any") String r18,
            @Parameter(schema = @Schema(defaultValue = "any", examples = {"any", "yes", "no"}))
            @RequestParam(required = false, defaultValue = "any") String ai,
            @CsvIds @RequestParam(required = false) String collectionIds,
            @CsvIds @RequestParam(required = false) String tagIds,
            @CsvIds @RequestParam(required = false) String notTagIds,
            @CsvIds @RequestParam(required = false) String orTagIds,
            @CsvIds @RequestParam(required = false) String authorIds,
            @CsvIds @RequestParam(required = false) String notAuthorIds,
            @CsvIds @RequestParam(required = false) String orAuthorIds,
            @RequestParam(required = false) Long authorId,
            @CsvIds @RequestParam(required = false) String seriesIds,
            @CsvIds @RequestParam(required = false) String notSeriesIds,
            @RequestParam(required = false) Long seriesId,
            @RequestParam(required = false) Long uploadedFrom,
            @RequestParam(required = false) Long uploadedBefore,
            @RequestParam(required = false) Long downloadedFrom,
            @RequestParam(required = false) Long downloadedBefore,
            @Parameter(schema = @Schema(allowableValues = {"uploadTime", "createTime", "updateTime", "reuploadTime"}))
            @RequestParam(required = false) String sourceTimeField,
            @RequestParam(required = false) Long createdFrom,
            @RequestParam(required = false) Long createdBefore,
            @RequestParam(required = false) Long updatedFrom,
            @RequestParam(required = false) Long updatedBefore,
            @RequestParam(required = false) Long reuploadedFrom,
            @RequestParam(required = false) Long reuploadedBefore,
            WorkVisibilityScope visibilityScope) {
        try {
            return novelGalleryService.query(new NovelGalleryService.NovelGalleryQuery(
                    Math.max(0, page), Math.max(1, Math.min(size, 200)),
                    sort, order, search, NovelGalleryService.normalizeSearchType(searchType), r18, ai,
                    HttpIds.queryIds("collectionIds", collectionIds),
                    HttpIds.queryIds("tagIds", tagIds), HttpIds.queryIds("notTagIds", notTagIds), HttpIds.queryIds("orTagIds", orTagIds),
                    HttpIds.queryIds("authorIds", authorIds, "authorId", authorId), HttpIds.queryIds("notAuthorIds", notAuthorIds), HttpIds.queryIds("orAuthorIds", orAuthorIds),
                    HttpIds.queryIds("seriesIds", seriesIds, "seriesId", seriesId), HttpIds.queryIds("notSeriesIds", notSeriesIds),
                    visibilityScope.restrictionFor(WorkType.NOVEL), uploadedFrom, uploadedBefore, downloadedFrom, downloadedBefore, sourceTimeField, createdFrom, createdBefore, updatedFrom, updatedBefore, reuploadedFrom, reuploadedBefore));
        } catch (WorkQuery.InvalidField failure) {
            throw new HttpParameterException("query", failure.field(), "invalid_value");
        }
    }

    @GetMapping("/novels/authors")
    public NovelGalleryService.PagedAuthors listAuthors(
            @Parameter(example = "0", schema = @Schema(type = "integer", format = "int32", defaultValue = "0"))
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "24") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false, defaultValue = "name") String sort,
            WorkVisibilityScope visibilityScope) {
        return novelGalleryService.getPagedAuthorsWithNovels(page, size, search, sort,
                visibilityScope.restrictionFor(WorkType.NOVEL));
    }

    @GetMapping("/novels/series")
    public NovelGalleryService.PagedSeries listNovelSeries(
            @Parameter(example = "0", schema = @Schema(type = "integer", format = "int32", defaultValue = "0"))
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "24") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false, defaultValue = "title") String sort,
            WorkVisibilityScope visibilityScope) {
        return novelGalleryService.getPagedSeriesWithNovels(page, size, search, sort,
                visibilityScope.restrictionFor(WorkType.NOVEL));
    }

    @GetMapping("/novels/tags")
    public NovelTagListResponse listNovelTags(
            @RequestParam(required = false) String search,
            @RequestParam(required = false, defaultValue = "500") int limit,
            WorkVisibilityScope visibilityScope) {
        return new NovelTagListResponse(novelGalleryService.listTags(
                search,
                limit,
                visibilityScope.restrictionFor(WorkType.NOVEL)));
    }

    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", content = @Content)
    @GetMapping("/novel/{novelId}")
    public ResponseEntity<NovelGalleryService.NovelView> findNovel(@PathVariable long novelId,
                                                                    WorkVisibilityScope visibilityScope) {
        workVisibilityService.requireVisible(visibilityScope, WorkType.NOVEL, novelId);
        NovelGalleryService.NovelView view = novelGalleryService.find(novelId);
        return view == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(view);
    }

    /**
     * 删除单本小说（含磁盘文件与全部 DB 留存数据）。仅管理员可用：{@code /api/gallery/} 由
     * {@code AuthFilter} 按 monitor 语义保护，访客邀请白名单只放行 GET，DELETE 永远命中管理员校验。
     */
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", content = @Content)
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = {"work.delete.file-failed", "work.delete.path-unsafe", "work.delete.ownership-incomplete"}))))
    @KnownErrorCodes(status = "503", value = "DATABASE_UNAVAILABLE")
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @DeleteMapping("/novel/{novelId}")
    public ResponseEntity<DeleteResponse> deleteNovel(@PathVariable long novelId) {
        boolean deleted = novelGalleryService.deleteNovel(novelId);
        if (!deleted) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(new DeleteResponse(1));
    }

    /** 批量删除小说。POST 不在访客白名单内，仅管理员可用。 */
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = "work.delete.path-unsafe"))))
    @KnownErrorCodes(status = "503", value = "DATABASE_UNAVAILABLE")
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = {"work.selection.confirm-all", "error.request.param.invalid"}))))
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = "application/json", schema = @Schema(implementation = NovelBatchRequest.class), examples = {
            @ExampleObject(name = "selectedNovels", value = "{\"ids\":[42,51]}")
    }))
    @KnownErrorCodes(status = "400", value = "error.request.param.invalid")
    @PostMapping("/novels/delete")
    public ResponseEntity<DeleteResponse> deleteNovels(@Valid @RequestBody NovelBatchRequest request) {
        int deleted = novelGalleryService.deleteNovels(novelBatchService.resolveNovelIds(request, true));
        return ResponseEntity.ok(new DeleteResponse(deleted));
    }

    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = "application/json", schema = @Schema(allOf = NovelBatchRequest.class, requiredProperties = "collectionId"), examples = {
            @ExampleObject(name = "selectedNovels", value = "{\"ids\":[42,51],\"collectionId\":3}"),
            @ExampleObject(name = "seriesExceptOneNovel", value = "{\"mode\":\"filter\",\"filter\":{\"seriesIds\":[12]},\"excludeIds\":[51],\"collectionId\":3}")
    }))
    @KnownErrorCodes(status = "400", value = "error.request.param.invalid")
    @PostMapping("/novels/collect")
    public ResponseEntity<BatchCollectResponse> collectNovels(
            @Valid @RequestBody NovelBatchRequest request) {
        if (request.collectionId() == null) throw new HttpParameterException("body", "/collectionId", "required");
        List<Long> ids = novelBatchService.resolveNovelIds(request, false);
        int changed = novelBatchService.collectNovels(ids, request.collectionId());
        return ResponseEntity.ok(new BatchCollectResponse(ids.size(), changed));
    }

    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = "application/json", schema = @Schema(implementation = NovelBatchRequest.class), examples = {
            @ExampleObject(name = "selectedNovels", value = "{\"ids\":[42,51]}"),
            @ExampleObject(name = "seriesExceptOneNovel", value = "{\"mode\":\"filter\",\"filter\":{\"seriesIds\":[12]},\"excludeIds\":[51]}")
    }))
    @KnownErrorCodes(status = "400", value = "error.request.param.invalid")
    @PostMapping("/novels/export/options")
    public NovelBatchService.ExportOptions exportOptions(@Valid @RequestBody NovelBatchRequest request) {
        return novelBatchService.exportOptions(novelBatchService.resolveNovelIds(request, false));
    }

    /** 按选定版本批量导出小说归档。 */
    @ApiResponse(responseCode = "204", content = @Content)
    @ApiResponse(responseCode = "200", links = {
            @Link(name = "inspectArchive", operationRef = "#/paths/~1api~1archive~1status~1%7Btoken%7D/get",
                    parameters = @LinkParameter(name = "token", expression = "$response.body#/archiveToken")),
            @Link(name = "downloadArchive", operationRef = "#/paths/~1api~1archive~1download~1%7Btoken%7D/get",
                    parameters = @LinkParameter(name = "token", expression = "$response.body#/archiveToken"))
    }, content = @Content(mediaType = "application/json", schema = @Schema(implementation = BatchExportResponse.class)))
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = {"work.selection.confirm-all", "NOVEL_EXPORT_REJECTED", "NOVEL_EXPORT_INVALID_REQUEST", UnsupportedArchiveFormatException.CODE}))))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = {"NOVEL_EXPORT_REJECTED", "work.delete.ownership-incomplete"}))))
    @ApiResponse(responseCode = "413", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = "NOVEL_EXPORT_REJECTED"))))
    @ApiResponse(responseCode = "500", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = "NOVEL_EXPORT_FAILED"))))
    @Operation(extensions = @Extension(properties = @ExtensionProperty(name = "x-program-permissions", parseValue = true, value = """
            {"route":"novel:library.export","submit":[{"permission":"novel:library.delete","when":{"keyFrom":"request#/deleteAfter","value":true},
            "reportedIn":{"status":403,"pointer":"/programPermission"}}]}
            """)))
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = "application/json", schema = @Schema(implementation = NovelBatchRequest.class), examples = {
            @ExampleObject(name = "originalsBySeries", value = "{\"mode\":\"filter\",\"filter\":{\"seriesIds\":[12]},\"excludeIds\":[51],\"format\":\"zip\",\"deleteAfter\":false}"),
            @ExampleObject(name = "selectedNovelsById", value = "{\"ids\":[42,51],\"groupBy\":\"id\",\"format\":\"zip\"}"),
            @ExampleObject(name = "translatedSelection", value = "{\"ids\":[42,51],\"variant\":\"en@ai\",\"format\":\"zip\",\"deleteAfter\":false}")
    }))
    @KnownErrorCodes(status = "400", value = "error.request.param.invalid")
    @PostMapping("/novels/export")
    public ResponseEntity<?> exportNovels(
            @Valid @RequestBody NovelBatchRequest request, HttpServletRequest httpRequest) {
        boolean deleteAfter = request != null && request.deletesAfterExport();
        if (deleteAfter && ProgramIdentity.from(httpRequest.getAttribute(ProgramIdentity.REQUEST_ATTRIBUTE))
                .filter(program -> !program.permits(NovelPlugin.ID, "library.delete")).isPresent()) {
            throw new MissingProgramPermissionException(NovelPlugin.ID + ":library.delete");
        }
        String runId = java.util.UUID.randomUUID().toString();
        long started = System.nanoTime();
        log.info("Novel archive requested: runId={}, translated={}", runId,
                request != null && request.variant() != null && !request.variant().isBlank());
        try {
            ArchiveExportResult result = novelBatchService.exportNovels(
                    novelBatchService.resolveNovelIds(request, deleteAfter),
                    request == null ? null : request.groupBy(),
                    request == null ? null : request.format(),
                    deleteAfter,
                    request == null ? null : request.variant());
            log.info("Novel archive submitted: runId={}, works={}, files={}, outcome={}, durationMs={}",
                    runId, result.workCount(), result.fileCount(), result.emptyArchive() ? "empty" : "accepted",
                    (System.nanoTime() - started) / 1_000_000);
            if (result.emptyArchive()) {
                return ResponseEntity.noContent().build();
            }
            return ResponseEntity.ok(new BatchExportResponse(
                    result.archiveToken(), result.archiveExpireSeconds(), result.workCount(), result.fileCount()));
        } catch (HttpParameterException | WorkSelectionException | UnsupportedArchiveFormatException
                 | top.sywyar.pixivdownload.core.work.service.WorkDeletionException validation) {
            throw validation;
        } catch (NovelBatchService.ExportRejected rejected) {
            log.info("Novel archive rejected: runId={}, reason={}", runId, rejected.reason());
            int status = switch (rejected.reason()) {
                case "unavailable" -> 409;
                case "too-large" -> 413;
                default -> 400;
            };
            return ResponseEntity.status(status).body(ApiErrorResponse.of(
                    "NOVEL_EXPORT_REJECTED", messages.get("novel.export." + rejected.reason())));
        } catch (IllegalArgumentException invalid) {
            log.info("Novel archive rejected: runId={}, reason=invalid_request", runId);
            return ResponseEntity.badRequest().body(ApiErrorResponse.of(
                    "NOVEL_EXPORT_INVALID_REQUEST", messages.get("novel.export.invalid-request")));
        } catch (IOException | RuntimeException failure) {
            log.error("Novel archive failed: runId={}, exceptionType={}, durationMs={}", runId,
                    failure.getClass().getSimpleName(), (System.nanoTime() - started) / 1_000_000);
            return ResponseEntity.internalServerError().body(ApiErrorResponse.of(
                    "NOVEL_EXPORT_FAILED", messages.get("novel.export.failed")));
        }
    }

    public record DeleteResponse(int deleted) {}

    public record NovelTagListResponse(List<NovelTagOption> tags) {}

    public record BatchCollectResponse(int count, int changed) {}

    public record BatchExportResponse(String archiveToken, long archiveExpireSeconds, int count, int fileCount) {}

    @GetMapping("/novel/{novelId}/by-series")
    public ResponseEntity<List<NovelGalleryService.NovelView>> bySeries(
            @PathVariable long novelId,
            @RequestParam(defaultValue = "30") int limit,
            WorkVisibilityScope visibilityScope) {
        workVisibilityService.requireVisible(visibilityScope, WorkType.NOVEL, novelId);
        NovelGalleryService.NovelView current = novelGalleryService.find(novelId);
        if (current == null || current.seriesId() == null || current.seriesId() <= 0) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(filterForGuest(
                novelGalleryService.bySeries(current.seriesId(), limit),
                visibilityScope));
    }

    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json",
            schema = @Schema(implementation = NovelSeriesNavResponse.class), examples = @ExampleObject(value = "{\"seriesId\":null,\"seriesTitle\":null,\"currentOrder\":null,\"prev\":null,\"next\":null}")))
    @GetMapping("/novel/{novelId}/series")
    public ResponseEntity<NovelSeriesNavResponse> seriesNav(
            @PathVariable long novelId,
            @RequestParam(required = false) String lang,
            @RequestParam(required = false) String variant,
            WorkVisibilityScope visibilityScope) {
        lang = variant == null ? lang : variant;
        workVisibilityService.requireVisible(visibilityScope, WorkType.NOVEL, novelId);
        SeriesNeighbors n = novelGalleryService.seriesNeighbors(novelId);
        if (n == null) {
            return ResponseEntity.ok(new NovelSeriesNavResponse(null, null, null, null, null));
        }
        // 选定了内容语言时优先用译后系列名 / 译后章节标题，缺失回退原文，与详情页主标题语义一致。
        String langCode = (lang == null || lang.isBlank()) ? null : lang.trim();
        String seriesTitle = n.seriesTitle();
        if (langCode != null) {
            String translatedSeries = novelDatabase.getSeriesTitleTranslation(n.seriesId(), langCode);
            if (translatedSeries != null && !translatedSeries.isBlank()) {
                seriesTitle = translatedSeries;
            }
        }
        return ResponseEntity.ok(new NovelSeriesNavResponse(
                n.seriesId(), seriesTitle, n.currentOrder(),
                visibleNeighbor(n.prev(), visibilityScope, langCode),
                visibleNeighbor(n.next(), visibilityScope, langCode)
        ));
    }

    private NeighborView visibleNeighbor(SeriesNeighbors.Neighbor neighbor,
                                         WorkVisibilityScope visibilityScope, String langCode) {
        if (neighbor == null) {
            return null;
        }
        if (!workVisibilityService.isVisible(visibilityScope, WorkType.NOVEL, neighbor.workId())) {
            return null;
        }
        String title = neighbor.title();
        if (langCode != null) {
            String translated = novelDatabase.getTranslationTitle(neighbor.workId(), langCode);
            if (translated != null && !translated.isBlank()) {
                title = translated;
            }
        }
        return new NeighborView(neighbor.workId(), title, neighbor.seriesOrder());
    }

    private List<NovelGalleryService.NovelView> filterForGuest(
            List<NovelGalleryService.NovelView> items,
            WorkVisibilityScope visibilityScope) {
        if (items == null || items.isEmpty()) {
            return items;
        }
        return items.stream()
                .filter(item -> workVisibilityService.isVisible(
                        visibilityScope, WorkType.NOVEL, item.novelId()))
                .toList();
    }

    @ApiResponse(responseCode = "200", content = @Content(mediaType = "image/*", schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(responseCode = "404", content = @Content)
    @GetMapping("/novel/{novelId}/cover")
    public ResponseEntity<byte[]> getNovelCover(@PathVariable long novelId,
                                                WorkVisibilityScope visibilityScope) throws IOException {
        workVisibilityService.requireVisible(visibilityScope, WorkType.NOVEL, novelId);
        WorkAssetFile cover = workAssetService.thumbnail(WorkType.NOVEL, novelId, 0).orElse(null);
        if (cover == null) {
            return ResponseEntity.notFound().build();
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(mimeFor(cover.extension())));
        headers.setCacheControl(CacheControl.maxAge(7, TimeUnit.DAYS).cachePublic());
        return ResponseEntity.ok().headers(headers).body(Files.readAllBytes(cover.path()));
    }

    /**
     * 本地小说正文（原始 Pixiv markup，来自 {@code novels.raw_content}）。
     * 详情页据此在不访问 Pixiv 的前提下渲染正文；本地不存在该小说时 404。
     * 传入 {@code variant} 时返回该语言与引擎的译文（缺失则回退原文）；{@code lang} 兼容旧译文链接。
     */
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", content = @Content)
    @GetMapping("/novel/{novelId}/content")
    public ResponseEntity<NovelContentResponse> getNovelContent(
            @PathVariable long novelId,
            @RequestParam(required = false) String lang,
            @RequestParam(required = false) String variant,
            WorkVisibilityScope visibilityScope) {
        lang = variant == null ? lang : variant;
        workVisibilityService.requireVisible(visibilityScope, WorkType.NOVEL, novelId);
        NovelRecord rec = novelDatabase.getNovel(novelId);
        if (rec == null) {
            return ResponseEntity.notFound().build();
        }
        String original = rec.rawContent() == null ? "" : rec.rawContent();
        if (lang != null && !lang.isBlank()) {
            String langCode = lang.trim();
            String translated = novelDatabase.getTranslationContent(novelId, langCode);
            String translatedTitle = novelDatabase.getTranslationTitle(novelId, langCode);
            String translatedDescription = novelDatabase.getTranslationDescription(novelId, langCode);
            boolean hasBody = translated != null && !translated.isBlank();
            boolean hasTitle = translatedTitle != null && !translatedTitle.isBlank();
            boolean hasDescription = translatedDescription != null && !translatedDescription.isBlank();
            // 即便只译了标题 / 简介（正文为空），也要返回 translated=true 让前端展示译后元数据，
            // 正文则回退到原文 markup（与「未勾选正文翻译」语义一致）。
            if (hasBody || hasTitle || hasDescription) {
                return ResponseEntity.ok(new NovelContentResponse(
                        hasBody ? translated : original, langCode, true,
                        translatedTitle, translatedDescription));
            }
        }
        return ResponseEntity.ok(new NovelContentResponse(original, null, false, null, null));
    }

    /**
     * 给定一批小说 ID 与译文身份，批量返回同一语言与引擎下已存在的译文标题。
     */
    @GetMapping("/novel/translated-titles")
    public ResponseEntity<TranslatedTitlesResponse> translatedTitles(
            @RequestParam(required = false) String lang,
            @RequestParam(required = false) String variant,
            @CsvIds @RequestParam(required = false) String ids,
            WorkVisibilityScope visibilityScope) {
        lang = variant == null ? lang : variant;
        if (lang == null || lang.isBlank() || ids == null || ids.isBlank()) {
            return ResponseEntity.ok(new TranslatedTitlesResponse(Map.of()));
        }
        Map<Long, String> out = new java.util.LinkedHashMap<>();
        String langCode = lang.trim();
        for (long novelId : HttpIds.queryIds("ids", ids)) {
            if (!workVisibilityService.isVisible(visibilityScope, WorkType.NOVEL, novelId)) continue;
            String translated = novelDatabase.getTranslationTitle(novelId, langCode);
            if (translated != null && !translated.isBlank()) {
                out.put(novelId, translated);
            }
        }
        return ResponseEntity.ok(new TranslatedTitlesResponse(out));
    }

    /**
     * 内嵌图片字节流，路径: {novelFolder}/embed_{imageId}.{ext}。
     * imageId 来自 [uploadedimage:id] 占位符；不存在时 404。
     */
    @ApiResponse(responseCode = "200", content = @Content(mediaType = "image/*", schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(responseCode = "404", content = @Content)
    @GetMapping("/novel/{novelId}/embed/{imageId}")
    public ResponseEntity<byte[]> getNovelEmbeddedImage(@PathVariable long novelId,
                                                        @PathVariable String imageId,
                                                        WorkVisibilityScope visibilityScope) throws IOException {
        workVisibilityService.requireVisible(visibilityScope, WorkType.NOVEL, novelId);
        if (imageId == null || imageId.isBlank() || !imageId.matches("[0-9A-Za-z_-]{1,40}")) {
            return ResponseEntity.notFound().build();
        }
        NovelRecord rec = novelDatabase.getNovel(novelId);
        if (rec == null) return ResponseEntity.notFound().build();
        String ext = novelDatabase.getNovelImageExt(novelId, imageId);
        if (ext == null || ext.isBlank()) return ResponseEntity.notFound().build();
        Path file = Paths.get(rec.folder(), "embed_" + imageId + "." + ext);
        if (!Files.isRegularFile(file)) {
            return ResponseEntity.notFound().build();
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(mimeFor(ext)));
        headers.setCacheControl(CacheControl.maxAge(7, TimeUnit.DAYS).cachePublic());
        return ResponseEntity.ok().headers(headers).body(Files.readAllBytes(file));
    }

    private static String mimeFor(String ext) {
        return switch (ext.toLowerCase(Locale.ROOT)) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "webp" -> "image/webp";
            case "gif" -> "image/gif";
            default -> "application/octet-stream";
        };
    }

    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", content = @Content)
    @GetMapping("/novel/series/{seriesId}")
    public ResponseEntity<NovelSeriesDetailResponse> getSeries(@PathVariable long seriesId,
                                                               @RequestParam(required = false) String lang,
                                                               @RequestParam(required = false) String variant,
                                                               WorkVisibilityScope visibilityScope) {
        lang = variant == null ? lang : variant;
        Set<Long> filter = resolveGuestNovelSeriesFilter(visibilityScope);
        if (filter != null && !filter.contains(seriesId)) {
            return ResponseEntity.notFound().build();
        }
        NovelSeries series = novelDatabase.getSeries(seriesId);
        if (series == null) {
            return ResponseEntity.notFound().build();
        }
        String langCode = (lang == null || lang.isBlank()) ? null : lang.trim();
        String translatedTitle = langCode == null
                ? null
                : novelDatabase.getSeriesTitleTranslation(seriesId, langCode);
        String translatedDescription = langCode == null
                ? null
                : novelDatabase.getSeriesDescriptionTranslation(seriesId, langCode);
        return ResponseEntity.ok(toDetailResponse(series, translatedTitle, translatedDescription));
    }

    @ApiResponse(responseCode = "200", content = @Content(mediaType = "image/*", schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(responseCode = "404", content = @Content)
    @GetMapping("/novel/series/{seriesId}/cover")
    public ResponseEntity<byte[]> getSeriesCover(@PathVariable long seriesId,
                                                 WorkVisibilityScope visibilityScope) throws IOException {
        Set<Long> filter = resolveGuestNovelSeriesFilter(visibilityScope);
        if (filter != null && !filter.contains(seriesId)) {
            return ResponseEntity.notFound().build();
        }
        NovelSeries series = novelDatabase.getSeries(seriesId);
        if (series == null || series.coverExt() == null || series.coverExt().isBlank()) {
            return ResponseEntity.notFound().build();
        }
        // cover_folder 写入时已 toAbsolutePath().normalize()；缺省回退到当前 rootFolder 下的标准目录。
        Path folder = (series.coverFolder() != null && !series.coverFolder().isBlank())
                ? Path.of(series.coverFolder())
                : novelSeriesService.resolveCoverDir(seriesId);
        Path file = folder.resolve("cover." + series.coverExt());
        if (!Files.isRegularFile(file)) {
            return ResponseEntity.notFound().build();
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(mimeFor(series.coverExt())));
        headers.setCacheControl(CacheControl.maxAge(7, TimeUnit.DAYS).cachePublic());
        return ResponseEntity.ok().headers(headers).body(Files.readAllBytes(file));
    }

    private Set<Long> resolveGuestNovelSeriesFilter(WorkVisibilityScope visibilityScope) {
        WorkRestriction restriction = visibilityScope.restrictionFor(WorkType.NOVEL);
        if (restriction == null) return null;
        return novelGalleryService.visibleSeriesIds(restriction);
    }

    @PostMapping("/novels/downloaded-batch")
    public ResponseEntity<NovelDownloadedBatchResponse> downloadedBatch(
            @RequestBody NovelDownloadedBatchRequest request,
            WorkVisibilityScope visibilityScope) {
        List<Long> ids = request == null || request.novelIds() == null ? List.of() : request.novelIds();
        boolean includeDeleted = request != null
                && request.includeDeleted()
                && visibilityScope.restrictionFor(WorkType.NOVEL) == null;
        List<Long> downloadedIds = new ArrayList<>();
        List<Long> deletedIds = new ArrayList<>();
        for (NovelDownloadedStatusRow status : novelDatabase.getDownloadedStatuses(ids)) {
            if (status.deleted()) {
                if (includeDeleted) deletedIds.add(status.novelId());
            } else if (workAssetService.hasCompleteFiles(WorkType.NOVEL, status.novelId())) {
                downloadedIds.add(status.novelId());
            }
        }
        return ResponseEntity.ok(new NovelDownloadedBatchResponse(downloadedIds, deletedIds));
    }

    public record NovelDownloadedBatchRequest(List<Long> novelIds, boolean includeDeleted) {
        public NovelDownloadedBatchRequest(List<Long> novelIds) {
            this(novelIds, false);
        }
    }

    public record NovelDownloadedBatchResponse(List<Long> novelIds, List<Long> deletedNovelIds) {
        public NovelDownloadedBatchResponse(List<Long> novelIds) {
            this(novelIds, List.of());
        }
    }

    public record NovelContentResponse(String content, @HttpApiNullable String lang, boolean translated,
                                       @HttpApiNullable String translatedTitle, @HttpApiNullable String translatedDescription) {}

    public record TranslatedTitlesResponse(Map<Long, String> titles) {}

    public record NovelSeriesNavResponse(@HttpApiNullable Long seriesId, @HttpApiNullable String seriesTitle,
            @HttpApiNullable Long currentOrder,
                                         @HttpApiNullable NeighborView prev, @HttpApiNullable NeighborView next) {}

    public record NeighborView(long novelId, String title, long seriesOrder) {}

    public record NovelSeriesDetailResponse(long seriesId, String title, @HttpApiNullable Long authorId, long updatedTime,
                                            @HttpApiNullable String description, @HttpApiNullable String coverExt,
                                            @HttpApiNullable String coverFolder,
                                            List<WorkTag> tags, List<String> translatedLanguages,
                                            @HttpApiNullable String translatedTitle, @HttpApiNullable String translatedDescription) {}

    private NovelSeriesDetailResponse toDetailResponse(NovelSeries series, String translatedTitle,
                                                       String translatedDescription) {
        return new NovelSeriesDetailResponse(
                series.seriesId(),
                series.title(),
                series.authorId(),
                series.updatedTime(),
                series.description(),
                series.coverExt(),
                series.coverFolder(),
                novelDatabase.getNovelSeriesTags(series.seriesId()),
                novelDatabase.getSeriesTranslatedLangs(series.seriesId()),
                translatedTitle,
                translatedDescription);
    }
}
