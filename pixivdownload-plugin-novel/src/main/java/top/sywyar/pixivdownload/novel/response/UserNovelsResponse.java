package top.sywyar.pixivdownload.novel.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class UserNovelsResponse {
    private final List<String> ids;
}
