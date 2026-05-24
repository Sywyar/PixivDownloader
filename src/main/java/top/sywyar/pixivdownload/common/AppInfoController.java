package top.sywyar.pixivdownload.common;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AppInfoController {

    private static final String GITHUB_URL = "https://github.com/Sywyar/PixivDownloader";
    private static final String RELEASES_URL = GITHUB_URL + "/releases";
    private static final String WIKI_URL = GITHUB_URL + "/wiki";
    private static final String LICENSE_URL = GITHUB_URL + "/blob/master/LICENSE";

    @GetMapping("/api/app/info")
    public AppInfoResponse info() {
        return new AppInfoResponse(
                "PixivDownload",
                AppVersion.getDisplayVersionOrDefault("unknown"),
                GITHUB_URL,
                RELEASES_URL,
                WIKI_URL,
                LICENSE_URL
        );
    }

    public record AppInfoResponse(
            String name,
            String version,
            String githubUrl,
            String releasesUrl,
            String wikiUrl,
            String licenseUrl) {
    }
}
