package top.sywyar.pixivdownload.download.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class UserMetaResponse {
    private final String name;
    private final String userId;
}
