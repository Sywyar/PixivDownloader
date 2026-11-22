package top.sywyar.pixivdownload.gallery;

import jakarta.validation.Valid;
import top.sywyar.pixivdownload.core.web.HttpParameterException;
import top.sywyar.pixivdownload.core.web.HttpIds;
import top.sywyar.pixivdownload.core.web.CsvIds;
import io.swagger.v3.oas.annotations.links.Link;
import io.swagger.v3.oas.annotations.links.LinkParameter;
import io.swagger.v3.oas.annotations.media.SchemaProperty;
import jakarta.servlet.http.HttpServletRequest;
import top.sywyar.pixivdownload.core.web.KnownErrorCodes;
import top.sywyar.pixivdownload.core.web.MissingProgramPermissionException;
import top.sywyar.pixivdownload.plugin.api.web.ProgramIdentity;

import top.sywyar.pixivdownload.core.work.model.FileManagement;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.Parameter;
import top.sywyar.pixivdownload.plugin.api.web.HttpApiNullable;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.sywyar.pixivdownload.core.archive.UnsupportedArchiveFormatException;
import top.sywyar.pixivdownload.core.archive.ArchiveExportResult;
import top.sywyar.pixivdownload.core.work.model.WorkRestriction;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.work.model.WorkVisibilityScope;
import top.sywyar.pixivdownload.core.work.query.SeriesNeighbors;
import top.sywyar.pixivdownload.core.work.query.WorkQuery;
import top.sywyar.pixivdownload.core.work.service.WorkVisibilityService;
import top.sywyar.pixivdownload.gallery.web.GalleryArtworkResponse;
import top.sywyar.pixivdownload.gallery.web.GalleryPageResponse;
import top.sywyar.pixivdownload.gallery.web.GalleryTagOptionResponse;
import top.sywyar.pixivdownload.gallery.web.GalleryWorkQueryFactory;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;
import top.sywyar.pixivdownload.plugin.api.web.HttpApiGroup;
import java.util.*;

/**
 * {@code @RestController} 仅供 Spring MVC handler 检测；Bean 本身被
 * {@code @PluginManagedBean} 排除出根包扫描，由 {@link GalleryPluginConfiguration} 提供。
 */
@PluginManagedBean
@HttpApiGroup("gallery-gallery")
@RestController
@RequestMapping("/api/gallery")
@RequiredArgsConstructor
public class GalleryController {

    private final GalleryService galleryService;
    private final GalleryBatchService galleryBatchService;
    private final WorkVisibilityService workVisibilityService;

    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json",
            schema = @Schema(implementation = GalleryPageResponse.class), examples = @ExampleObject(value = """
                    {"content":[{"artworkId":42,"title":"Sea light","folder":"/srv/artworks/42","count":1,
                       "extensions":"jpg","time":1735689600000,"moved":false,"moveFolder":null,"moveTime":null,
                       "xRestrict":0,"isAi":false,"authorId":7,"authorName":"Aoi","description":"Light on the water.",
                       "fileName":null,"fileNameTemplate":null,"tags":[{"tagId":8,"name":"海","translatedName":"sea"}],
                       "seriesId":null,"seriesOrder":null,"seriesTitle":null,"deleted":false,"uploadTime":1735603200000,
                       "createTime":null,"updateTime":null,"reuploadTime":null,"fileManagement":"MANAGED"}],
                     "totalElements":1,"page":0,"size":24,"totalPages":1}
                    """)))
    @GetMapping("/artworks")
    public GalleryPageResponse listArtworks(
            @Parameter(example = "0", schema = @Schema(type = "integer", format = "int32", defaultValue = "0"))
            @RequestParam(required = false, defaultValue = "0") Integer page,
            @RequestParam(required = false, defaultValue = "24") Integer size,
            @Parameter(schema = @Schema(defaultValue = "date", examples = {"date", "uploadTime", "createTime", "updateTime", "reuploadTime", "artworkId", "imgs", "status", "authorId", "tags", "series"}))
            @RequestParam(required = false, defaultValue = "date") String sort,
            @Parameter(schema = @Schema(defaultValue = "desc", examples = {"asc", "desc"}))
            @RequestParam(required = false, defaultValue = "desc") String order,
            @RequestParam(required = false) String search,
            @Parameter(schema = @Schema(defaultValue = "all", examples = {"all", "title", "author", "id", "authorId", "desc", "tag", "tagExact"}))
            @RequestParam(required = false, defaultValue = "all") String searchType,
            @Parameter(schema = @Schema(defaultValue = "any", examples = {"any", "yes", "no", "r18", "r18g", "r18plus"}))
            @RequestParam(required = false, defaultValue = "any") String r18,
            @Parameter(schema = @Schema(defaultValue = "any", examples = {"any", "yes", "no"}))
            @RequestParam(required = false, defaultValue = "any") String ai,
            @Parameter(style = io.swagger.v3.oas.annotations.enums.ParameterStyle.FORM,
                    explode = io.swagger.v3.oas.annotations.enums.Explode.FALSE,
                    array = @io.swagger.v3.oas.annotations.media.ArraySchema(schema = @Schema(type = "string",
                            allowableValues = {"jpg", "jpeg", "png", "gif", "webp"})))
            @RequestParam(required = false) String format,
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
            @RequestParam(required = false) FileManagement fileManagement,
            WorkVisibilityScope visibilityScope) {

        WorkRestriction restriction = visibilityScope.restrictionFor(WorkType.ARTWORK);
        try {
            WorkQuery query = GalleryWorkQueryFactory.create(
                    page, size, sort, order, search, searchType, r18, ai,
                    format == null ? null : Arrays.asList(format.split(",", -1)),
                    HttpIds.queryIds("collectionIds", collectionIds),
                    HttpIds.queryIds("tagIds", tagIds),
                    HttpIds.queryIds("notTagIds", notTagIds),
                    HttpIds.queryIds("orTagIds", orTagIds),
                    HttpIds.queryIds("authorIds", authorIds, "authorId", authorId),
                    HttpIds.queryIds("notAuthorIds", notAuthorIds),
                    HttpIds.queryIds("orAuthorIds", orAuthorIds),
                    HttpIds.queryIds("seriesIds", seriesIds, "seriesId", seriesId),
                    HttpIds.queryIds("notSeriesIds", notSeriesIds),
                    restriction).withUploadTimeRange(uploadedFrom, uploadedBefore)
                    .withDownloadTimeRange(downloadedFrom, downloadedBefore).withSourceTimeField(sourceTimeField)
                    .withSourceTimeRanges(createdFrom, createdBefore, updatedFrom, updatedBefore, reuploadedFrom, reuploadedBefore)
                    .withFileManagement(fileManagement);
            return galleryService.query(query);
        } catch (WorkQuery.InvalidField failure) {
            throw new HttpParameterException("query", "formats".equals(failure.field()) ? "format" : failure.field(), "invalid_value");
        }
    }

    @GetMapping("/tags")
    public TagListResponse listTags(
            @RequestParam(required = false) String search,
            @RequestParam(required = false, defaultValue = "500") int limit,
            WorkVisibilityScope visibilityScope) {
        WorkRestriction restriction = visibilityScope.restrictionFor(WorkType.ARTWORK);
        List<GalleryTagOptionResponse> tags = galleryService.listTags(search, limit, restriction);
        return new TagListResponse(tags);
    }

    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", content = @Content)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/HttpParameterBindingError")))
    @KnownErrorCodes(status = "400", value = "error.request.param.invalid")
    @GetMapping("/tags/lookup")
    public ResponseEntity<GalleryTagOptionResponse> findTag(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String translatedName) {
        if ((name == null || name.isBlank()) && (translatedName == null || translatedName.isBlank()))
            throw new HttpParameterException("query", name != null || translatedName == null ? "name" : "translatedName", "required");
        GalleryTagOptionResponse tag = galleryService.findTag(name, translatedName);
        return tag == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(tag);
    }

    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", content = @Content)
    @GetMapping("/artwork/{artworkId}")
    public ResponseEntity<GalleryArtworkResponse> artwork(@PathVariable long artworkId,
                                                          WorkVisibilityScope visibilityScope) {
        workVisibilityService.requireVisible(visibilityScope, WorkType.ARTWORK, artworkId);
        GalleryArtworkResponse resp = galleryService.findArtwork(artworkId);
        return resp == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(resp);
    }

    @GetMapping("/artwork/{artworkId}/related")
    public ResponseEntity<List<GalleryArtworkResponse>> related(
            @PathVariable long artworkId,
            @RequestParam(defaultValue = "12") int limit,
            WorkVisibilityScope visibilityScope) {
        workVisibilityService.requireVisible(visibilityScope, WorkType.ARTWORK, artworkId);
        List<GalleryArtworkResponse> all = galleryService.related(artworkId, limit);
        return ResponseEntity.ok(filterForGuest(all, visibilityScope));
    }

    @GetMapping("/artwork/{artworkId}/by-author")
    public ResponseEntity<List<GalleryArtworkResponse>> byAuthor(
            @PathVariable long artworkId,
            @RequestParam(defaultValue = "12") int limit,
            WorkVisibilityScope visibilityScope) {
        workVisibilityService.requireVisible(visibilityScope, WorkType.ARTWORK, artworkId);
        List<GalleryArtworkResponse> all = galleryService.byAuthor(artworkId, limit);
        return ResponseEntity.ok(filterForGuest(all, visibilityScope));
    }

    @GetMapping("/artwork/{artworkId}/by-series")
    public ResponseEntity<List<GalleryArtworkResponse>> bySeries(
            @PathVariable long artworkId,
            @RequestParam(defaultValue = "30") int limit,
            WorkVisibilityScope visibilityScope) {
        workVisibilityService.requireVisible(visibilityScope, WorkType.ARTWORK, artworkId);
        List<GalleryArtworkResponse> all = galleryService.bySeries(artworkId, limit);
        return ResponseEntity.ok(filterForGuest(all, visibilityScope));
    }

    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json",
            schema = @Schema(implementation = SeriesNavResponse.class), examples = @ExampleObject(value = "{\"seriesId\":null,\"seriesTitle\":null,\"currentOrder\":null,\"prev\":null,\"next\":null}")))
    @GetMapping("/artwork/{artworkId}/series")
    public ResponseEntity<SeriesNavResponse> seriesNav(
            @PathVariable long artworkId,
            WorkVisibilityScope visibilityScope) {
        workVisibilityService.requireVisible(visibilityScope, WorkType.ARTWORK, artworkId);
        boolean restricted = visibilityScope.restrictionFor(WorkType.ARTWORK) != null;
        SeriesNeighbors neighbors = galleryService.seriesNeighbors(artworkId);
        if (neighbors == null) {
            return ResponseEntity.ok(new SeriesNavResponse(null, null, null, null, null));
        }
        SeriesNavResponse.NeighborView prev = neighbors.prev() == null
                ? null
                : (restricted && !workVisibilityService.isVisible(
                        visibilityScope, WorkType.ARTWORK, neighbors.prev().workId())
                    ? null
                    : new SeriesNavResponse.NeighborView(
                        neighbors.prev().workId(),
                        neighbors.prev().title(),
                        neighbors.prev().seriesOrder()));
        SeriesNavResponse.NeighborView next = neighbors.next() == null
                ? null
                : (restricted && !workVisibilityService.isVisible(
                        visibilityScope, WorkType.ARTWORK, neighbors.next().workId())
                    ? null
                    : new SeriesNavResponse.NeighborView(
                        neighbors.next().workId(),
                        neighbors.next().title(),
                        neighbors.next().seriesOrder()));
        return ResponseEntity.ok(new SeriesNavResponse(
                neighbors.seriesId(),
                neighbors.seriesTitle(),
                neighbors.currentOrder(),
                prev,
                next
        ));
    }

    public record SeriesNavResponse(@HttpApiNullable Long seriesId, @HttpApiNullable String seriesTitle, @HttpApiNullable Long currentOrder,
                                    @HttpApiNullable NeighborView prev, @HttpApiNullable NeighborView next) {
        public record NeighborView(long artworkId, String title, long seriesOrder) {}
    }

    /**
     * 按路径中的作品 ID 删除，不读取请求体。保留查重记录和外部引用的原件。
     */
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", content = @Content)
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string",
                    allowableValues = {"work.delete.file-failed", "work.delete.path-unsafe"}))))
    @KnownErrorCodes(status = "503", value = "DATABASE_UNAVAILABLE")
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @DeleteMapping("/artwork/{artworkId}")
    public ResponseEntity<DeleteResponse> deleteArtwork(@PathVariable long artworkId) {
        boolean deleted = galleryService.deleteArtwork(artworkId);
        if (!deleted) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(new DeleteResponse(1));
    }

    /** 批量删除作品。POST 不在访客白名单内，仅管理员可用。 */
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string",
                    allowableValues = "work.delete.path-unsafe"))))
    @KnownErrorCodes(status = "503", value = "DATABASE_UNAVAILABLE")
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = {"work.selection.confirm-all", "error.request.param.invalid"}))))
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = "application/json", schema = @Schema(implementation = ArtworkBatchRequest.class), examples = {
            @ExampleObject(name = "selectedArtworks", value = "{\"ids\":[42,51]}")
    }))
    @KnownErrorCodes(status = "400", value = "error.request.param.invalid")
    @PostMapping("/artworks/delete")
    public ResponseEntity<DeleteResponse> deleteArtworks(@Valid @RequestBody ArtworkBatchRequest request) {
        int deleted = galleryService.deleteArtworks(galleryBatchService.resolveArtworkIds(request, true));
        return ResponseEntity.ok(new DeleteResponse(deleted));
    }

    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = "application/json", schema = @Schema(allOf = ArtworkBatchRequest.class, requiredProperties = "collectionId"), examples = {
            @ExampleObject(name = "selectedArtworks", value = "{\"ids\":[42,51],\"collectionId\":3}"),
            @ExampleObject(name = "taggedArtworksExceptOne", value = "{\"mode\":\"filter\",\"filter\":{\"tagIds\":[8]},\"excludeIds\":[51],\"collectionId\":3}")
    }))
    @KnownErrorCodes(status = "400", value = "error.request.param.invalid")
    @PostMapping("/artworks/collect")
    public ResponseEntity<BatchCollectResponse> collectArtworks(
            @Valid @RequestBody ArtworkBatchRequest request) {
        if (request.collectionId() == null) throw new HttpParameterException("body", "/collectionId", "required");
        List<Long> ids = galleryBatchService.resolveArtworkIds(request, false);
        int changed = galleryBatchService.collectArtworks(ids, request.collectionId());
        return ResponseEntity.ok(new BatchCollectResponse(ids.size(), changed));
    }

    /** 批量导出作品。打包方式 / 格式 / 是否导出后删除由请求体决定。 */
    @ApiResponse(responseCode = "200", links = {
            @Link(name = "inspectArchive", operationRef = "#/paths/~1api~1archive~1status~1%7Btoken%7D/get",
                    parameters = @LinkParameter(name = "token", expression = "$response.body#/archiveToken")),
            @Link(name = "downloadArchive", operationRef = "#/paths/~1api~1archive~1download~1%7Btoken%7D/get",
                    parameters = @LinkParameter(name = "token", expression = "$response.body#/archiveToken"))
    }, useReturnTypeSchema = true)
    @ApiResponse(responseCode = "204", content = @Content)
    @Operation(extensions = @Extension(properties = @ExtensionProperty(name = "x-program-permissions", parseValue = true, value = """
            {"route":"gallery:export","submit":[{"permission":"gallery:delete","when":{"keyFrom":"request#/deleteAfter","value":true},
            "reportedIn":{"status":403,"pointer":"/programPermission"}}]}
            """)))
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = {"work.selection.confirm-all", "error.request.param.invalid", UnsupportedArchiveFormatException.CODE}))))
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = "application/json", schema = @Schema(implementation = ArtworkBatchRequest.class), examples = {
            @ExampleObject(name = "selectedArtworksById", value = "{\"ids\":[42,51],\"groupBy\":\"id\",\"format\":\"zip\"}"),
            @ExampleObject(name = "imagesByAuthor", value = "{\"mode\":\"filter\",\"filter\":{\"authorIds\":[7],\"formats\":[\"jpg\",\"png\"]},\"excludeIds\":[51],\"format\":\"zip\",\"deleteAfter\":false}")
    }))
    @KnownErrorCodes(status = "400", value = "error.request.param.invalid")
    @PostMapping("/artworks/export")
    public ResponseEntity<BatchExportResponse> exportArtworks(
            @Valid @RequestBody ArtworkBatchRequest request, HttpServletRequest httpRequest) {
        boolean deleteAfter = request != null && request.deletesAfterExport();
        if (deleteAfter && ProgramIdentity.from(httpRequest.getAttribute(ProgramIdentity.REQUEST_ATTRIBUTE))
                .filter(program -> !program.permits(GalleryPlugin.ID, "delete")).isPresent()) {
            throw new MissingProgramPermissionException(GalleryPlugin.ID + ":delete");
        }
        ArchiveExportResult result = galleryBatchService.exportArtworks(
                galleryBatchService.resolveArtworkIds(request, deleteAfter),
                request == null ? null : request.groupBy(),
                request == null ? null : request.format(),
                deleteAfter);
        if (result.emptyArchive()) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(new BatchExportResponse(
                result.archiveToken(), result.archiveExpireSeconds(), result.workCount(), result.fileCount()));
    }

    public record DeleteResponse(int deleted) {}

    public record TagListResponse(List<GalleryTagOptionResponse> tags) {}

    public record BatchCollectResponse(int count, int changed) {}

    public record BatchExportResponse(String archiveToken, long archiveExpireSeconds, int count, int fileCount) {}

    private List<GalleryArtworkResponse> filterForGuest(List<GalleryArtworkResponse> items,
                                                        WorkVisibilityScope visibilityScope) {
        if (items == null || items.isEmpty()
                || visibilityScope.restrictionFor(WorkType.ARTWORK) == null) {
            return items;
        }
        List<GalleryArtworkResponse> out = new ArrayList<>(items.size());
        for (GalleryArtworkResponse item : items) {
            if (item == null) continue;
            if (workVisibilityService.isVisible(
                    visibilityScope, WorkType.ARTWORK, item.artworkId())) {
                out.add(item);
            }
        }
        return out;
    }

}
