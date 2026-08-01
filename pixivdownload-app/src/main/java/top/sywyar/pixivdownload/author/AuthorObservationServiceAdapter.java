package top.sywyar.pixivdownload.author;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.core.artwork.download.ArtworkAuthorLookup;
import top.sywyar.pixivdownload.core.work.service.AuthorObservationService;

/**
 * 将核心作者事实写入端口适配到作者 owner 的业务服务。
 */
@Component
public class AuthorObservationServiceAdapter implements AuthorObservationService, ArtworkAuthorLookup {

    private final AuthorService authorService;

    public AuthorObservationServiceAdapter(AuthorService authorService) {
        this.authorService = authorService;
    }

    @Override
    public void observe(long authorId, String hintName) {
        authorService.observe(authorId, hintName);
    }

    @Override
    public void resolveMissing(long artworkId, String credential) {
        authorService.asyncLookupMissing(artworkId, credential);
    }
}
