package top.sywyar.pixivdownload.download.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class UgoiraMetaResponse {
    private final String zipUrl;
    private final List<Integer> delays;
}
