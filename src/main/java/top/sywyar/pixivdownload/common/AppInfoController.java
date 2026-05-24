package top.sywyar.pixivdownload.common;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AppInfoController {

    @GetMapping("/api/app/info")
    public AppInfoResponse info() {
        return new AppInfoResponse(
                AppInfo.NAME,
                AppVersion.getDisplayVersionOrDefault("unknown"),
                AppInfo.GITHUB_URL,
                AppInfo.RELEASES_URL,
                AppInfo.WIKI_URL,
                AppInfo.LICENSE_URL
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
