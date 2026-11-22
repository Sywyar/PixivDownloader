package top.sywyar.pixivdownload.author;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;
import top.sywyar.pixivdownload.plugin.api.web.HttpApiGroup;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.core.metadata.artwork.GalleryRepository;
import top.sywyar.pixivdownload.setup.guest.GuestWorkVisibilityScopeFactory;
import top.sywyar.pixivdownload.setup.guest.GuestAccessGuard;
import top.sywyar.pixivdownload.setup.guest.GuestInviteSession;

import java.util.List;
import java.util.Set;

@HttpApiGroup("core-library")
@RestController
@RequestMapping("/api/authors")
@RequiredArgsConstructor
public class AuthorController {

    private final AuthorService authorService;
    private final GalleryRepository galleryRepository;

    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @GetMapping
    public List<Author> getAuthors(HttpServletRequest httpRequest) {
        Set<Long> filter = resolveGuestFilter(httpRequest);
        return authorService.getAllAuthors(filter);
    }

    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @GetMapping("/paged")
    public AuthorService.PagedAuthors getPagedAuthors(
            @Parameter(example = "0", schema = @Schema(type = "integer", format = "int32", defaultValue = "0"))
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "20") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false, defaultValue = "name") String sort,
            HttpServletRequest httpRequest) {
        Set<Long> filter = resolveGuestFilter(httpRequest);
        return authorService.getPagedAuthorsWithArtworks(page, size, search, sort, filter);
    }

    private Set<Long> resolveGuestFilter(HttpServletRequest httpRequest) {
        GuestInviteSession session = GuestAccessGuard.extractSession(httpRequest);
        if (session == null) return null;
        return galleryRepository.findVisibleAuthorIds(GuestWorkVisibilityScopeFactory.artworkRestriction(session));
    }
}
