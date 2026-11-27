package top.sywyar.pixivdownload.pixivcontent;

import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.HttpMethod;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;
import top.sywyar.pixivdownload.plugin.api.web.ProgramPermission;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Describes the read-only content routes and their program permissions. */
public final class PixivContentPlugin implements PixivFeaturePlugin {

    public static final String ID = "pixiv-content";
    public static final String API = "/api/pixiv-content";
    private static final ProgramPermission METADATA = new ProgramPermission("metadata.read", "permission.metadata.read");
    private static final ProgramPermission IMAGE = new ProgramPermission("image.read", "permission.image.read");
    private static final ProgramPermission PERSONAL = new ProgramPermission("personal.read", "permission.personal.read");
    private static final ProgramPermission NOVEL = new ProgramPermission("novel.read", "permission.novel.read");
    private static final ProgramPermission ANIMATION = new ProgramPermission("animation.read", "permission.animation.read");

    @Override
    public String id() { return ID; }

    @Override
    public String displayName() { return "plugin.name"; }

    @Override
    public String description() { return "plugin.summary"; }

    @Override
    public String iconKey() { return "gallery"; }

    @Override
    public String colorToken() { return "blue"; }

    @Override
    public PluginKind kind() { return PluginKind.FEATURE; }

    @Override
    public List<I18nContribution> i18n() {
        return List.of(new I18nContribution(ID, "i18n.web.pixiv-content"));
    }

    @Override
    public List<WebRouteContribution> routes() {
        return List.of(get("/search", METADATA), get("/rankings", METADATA),
                get("/artworks/*", METADATA), get("/artworks/*/pages/*/image", IMAGE),
                get("/users/*", METADATA), get("/users/*/artworks", METADATA), get("/series/*", METADATA),
                get("/artworks/*/related", METADATA), get("/artworks/*/comments", METADATA),
                get("/users/*/series", METADATA), get("/users/*/avatar", IMAGE), get("/tags/suggest", METADATA),
                get("/me", PERSONAL), get("/me/bookmarks", PERSONAL), get("/me/follow-latest", PERSONAL), get("/me/following", PERSONAL),
                get("/me/collections", PERSONAL), get("/me/collections/*/works", PERSONAL),
                get("/novels/search", NOVEL), get("/novels/*", NOVEL), get("/novel-series/*", NOVEL),
                get("/novels/rankings", NOVEL), get("/users/*/novels", NOVEL),
                get("/novels/*/cover", IMAGE), get("/novels/*/images/*", IMAGE),
                get("/artworks/*/animation", ANIMATION), get("/users/search", METADATA), get("/tags/search", METADATA),
                get("/search/users-and-tags", METADATA), get("/users/*/bookmarks", METADATA), get("/me/bookmark-tags", PERSONAL), get("/me/watchlist", PERSONAL),
                get("/artworks/*/comments/*/replies", METADATA), get("/novels/*/comments", NOVEL),
                get("/novels/*/comments/*/replies", NOVEL), get("/novels/*/related", NOVEL), get("/novel-series/search", NOVEL),
                get("/collections/search", METADATA), get("/users/*/collections", METADATA), get("/collections/*/works", METADATA),
                get("/discover", METADATA), get("/me/recommendations", PERSONAL), get("/latest", METADATA));
    }

    private static WebRouteContribution get(String path, ProgramPermission permission) {
        return new WebRouteContribution(API + path, AccessPolicy.ADMIN, Set.of(HttpMethod.GET), false)
                .withProgramPermissions(Map.of(HttpMethod.GET, permission));
    }
}
