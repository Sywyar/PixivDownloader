package top.sywyar.pixivdownload.gallery.web;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * gallery 作品列表的分页 HTTP 投影。
 */
public record GalleryPageResponse(
        List<GalleryArtworkResponse> content,
        long totalElements,
        int page,
        @Schema(minimum = "1", maximum = "200") int size,
        int totalPages) {

    public GalleryPageResponse {
        content = content == null ? List.of() : List.copyOf(content);
    }
}
