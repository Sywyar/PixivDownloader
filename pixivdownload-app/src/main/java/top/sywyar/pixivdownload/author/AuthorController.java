package top.sywyar.pixivdownload.author;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.core.metadata.artwork.GalleryRepository;
import top.sywyar.pixivdownload.core.metadata.GuestRestriction;
import top.sywyar.pixivdownload.setup.guest.GuestAccessGuard;
import top.sywyar.pixivdownload.setup.guest.GuestInviteSession;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/authors")
@RequiredArgsConstructor
public class AuthorController {

    private final AuthorService authorService;
    private final GalleryRepository galleryRepository;

    @GetMapping
    public List<Author> getAuthors(HttpServletRequest httpRequest) {
        Set<Long> filter = resolveGuestFilter(httpRequest);
        return authorService.getAllAuthors(filter);
    }

    @GetMapping("/paged")
    public AuthorService.PagedAuthors getPagedAuthors(
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
        return galleryRepository.findVisibleAuthorIds(GuestRestriction.from(session));
    }
}
