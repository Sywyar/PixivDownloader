package top.sywyar.pixivdownload.download.response.user;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class UserArtworksResponse {
    private final List<String> ids;
}
