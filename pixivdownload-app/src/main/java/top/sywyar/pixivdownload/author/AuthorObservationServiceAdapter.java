package top.sywyar.pixivdownload.author;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.core.work.service.AuthorObservationService;

/**
 * 将核心作者事实写入端口适配到作者 owner 的业务服务。
 */
@Component
public class AuthorObservationServiceAdapter implements AuthorObservationService {

    private final AuthorService authorService;

    public AuthorObservationServiceAdapter(AuthorService authorService) {
        this.authorService = authorService;
    }

    @Override
    public void observe(long authorId, String hintName) {
        authorService.observe(authorId, hintName);
    }
}
