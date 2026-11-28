package top.sywyar.pixivdownload.plugin;

import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import top.sywyar.pixivdownload.config.credential.AcquisitionCredentialLibrary;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxClient;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxException;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxFailure;
import top.sywyar.pixivdownload.core.pixiv.media.PixivMediaClient;
import top.sywyar.pixivdownload.core.pixiv.media.PixivMediaStream;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.work.model.WorkVisibilityScope;
import top.sywyar.pixivdownload.core.work.service.WorkVisibilityDeniedException;
import top.sywyar.pixivdownload.core.work.service.WorkVisibilityService;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginLifecycleService;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.PluginEnabledSnapshot;
import top.sywyar.pixivdownload.setup.SetupService;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"setup.browser.auto-open=false", "update.auto-check=false", "database.provider=database-sqlite"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ContextConfiguration(initializers = PixivContentAccessTest.Bootstrap.class)
@TestExecutionListeners(listeners = PixivContentAccessTest.RestorePaths.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
@DirtiesContext
class PixivContentAccessTest {
    private static final String ID = "pixiv-content";
    private static final String API = "/api/pixiv-content";
    private static final String IMAGE = API + "/artworks/42/pages/0/image";
    private static final String IMAGE_TEMPLATE = API + "/artworks/{id}/pages/{pageIndex}/image";
    private static final String SELECTED = "X-Acquisition-Credentials";
    private static final String FIXTURE_COOKIE = "PHPSESSID=21_fixture";
    private static final String MISMATCHED_COOKIE = "PHPSESSID=22_fixture";
    private static final String COLLECTION_ID = "999999999999999999999";
    private static final AtomicInteger MANGA_PAGE_REQUESTS = new AtomicInteger();
    private static final String NOVEL_CARD = """
            {"id":"88","title":"Story","userId":"3","userName":"Author","textCount":12}
            """;
    private static final String ARTWORK_CARD = """
            {"id":"42","title":"Page","userId":"3","userName":"Artist","illustType":1,"pageCount":2}
            """;
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO5W49kAAAAASUVORK5CYII=");
    private static final Map<String, String> previousProperties = new HashMap<>();

    @TempDir static Path home;
    @Autowired MockMvc mvc;
    @Autowired SetupService setup;
    @Autowired ObjectMapper json;
    @Autowired PluginLifecycleService lifecycle;
    @Autowired AcquisitionCredentialLibrary library;

    @TestBean(methodName = "ajax") PixivAjaxClient pixivAjaxProxyClient;
    @TestBean(methodName = "media") PixivMediaClient pixivMediaStreamService;
    @TestBean(methodName = "visibility") WorkVisibilityService guestWorkVisibilityService;

    static WorkVisibilityService visibility() {
        return new WorkVisibilityService() {
            @Override public void requireVisible(WorkVisibilityScope scope, WorkType type, long id) {
                if (!isVisible(scope, type, id)) throw new WorkVisibilityDeniedException(type, id);
            }
            @Override public boolean isVisible(WorkVisibilityScope scope, WorkType type, long id) {
                return type == WorkType.ARTWORK ? id != 77 : type != WorkType.NOVEL || id != 99;
            }
        };
    }

    private static String body(String data) { return "{\"error\":false,\"body\":" + data + "}"; }

    static PixivAjaxClient ajax() {
        return (uri, cookie) -> switch (uri.getPath()) {
            case "/ajax/illust/42/recommend/init" -> {
                assertThat(uri.getQuery()).isEqualTo("limit=5");
                String hidden = ARTWORK_CARD.replace("\"42\"", "\"77\"");
                yield body("{\"illusts\":[" + hidden + "," + ARTWORK_CARD + "]}");
            }
            case "/ajax/illusts/comments/roots" -> {
                assertThat(uri.getQuery()).contains("illust_id=42", "offset=20", "limit=20");
                yield body("""
                        {"comments":[{"id":"9","userId":"2","userName":"Artist","comment":null,"stampId":"5",
                         "commentDate":"2026-02-03 12:04","hasReplies":true}],"hasNext":false}
                        """);
            }
            case "/ajax/search/users" -> {
                assertThat(uri.getRawQuery()).contains("nick=Cat%20Artist", "s_mode=s_usr_full", "p=2");
                yield body("""
                        {"page":{"userIds":["3"],"total":11},"users":[{"userId":"3","name":"Cat Artist","comment":"draws"}]}
                        """);
            }
            case "/ajax/search/top/Cat Artist" -> {
                assertThat(cookie).isNull();
                yield body("{\"relatedTags\":[\"cat\"],\"tagTranslation\":{}}");
            }
            case "/ajax/user/3/illustmanga/tag", "/ajax/user/3/novels/tag" -> {
                assertThat(uri.getRawQuery()).contains("tag=ink%2Cpen", "offset=0");
                String card = uri.getPath().contains("illustmanga") ? ARTWORK_CARD : NOVEL_CARD;
                yield body("{\"total\":1,\"works\":[" + card.replace("}", ",\"tags\":[\"ink,pen\",\"paper\"]}") + "]}");
            }
            case "/ajax/user/3/illusts/bookmarks" -> {
                assertThat(uri.getQuery()).contains("rest=show", "offset=0");
                yield body("{\"works\":[" + ARTWORK_CARD + "],\"total\":1}");
            }
            case "/ajax/user/21/illusts/bookmark/tags" -> {
                assertThat(cookie).isEqualTo(FIXTURE_COOKIE);
                yield body("{\"public\":[{\"tag\":\"public\",\"cnt\":2}],\"private\":[{\"tag\":\"secret\",\"cnt\":4}]}");
            }
            case "/ajax/watch_list/novel" -> {
                assertThat(cookie).isEqualTo(FIXTURE_COOKIE);
                assertThat(uri.getQuery()).contains("new=1", "p=2");
                yield body("{\"page\":{\"watchedSeriesIds\":[24],\"total\":11,\"maxPage\":3}}");
            }
            case "/ajax/top/illust" -> {
                assertThat(cookie).isEqualTo(FIXTURE_COOKIE);
                yield body("{\"page\":{\"recommend\":{\"ids\":[42]}},\"thumbnails\":{\"illust\":[" + ARTWORK_CARD + "]}}");
            }
            case "/ajax/novel/new" -> {
                assertThat(cookie).isEqualTo(FIXTURE_COOKIE);
                assertThat(uri.getQuery()).contains("lastId=108", "limit=20", "r18=false");
                yield body("{\"novels\":[" + NOVEL_CARD + "],\"lastId\":\"88\"}");
            }
            case "/ajax/user/21" -> {
                assertThat(cookie).isEqualTo(FIXTURE_COOKIE);
                yield body("{\"userId\":21,\"name\":\"Test account\",\"comment\":\"About\",\"premium\":true}");
            }
            case "/ajax/user/3" -> body("{\"userId\":3,\"name\":\"Artist\",\"imageBig\":\"https://i.pximg.net/avatar.png\"}");
            case "/ajax/user/5" -> body("{\"userId\":6,\"name\":\"Mismatch\"}");
            case "/ajax/user/6" -> body("{\"userId\":6,\"name\":\"Truncated\",\"imageBig\":\"https://i.pximg.net/truncated.png\"}");
            case "/ajax/illust/57" -> body("""
                    {"id":"57","title":"Manga","userId":"3","userName":"Artist","illustType":1,"pageCount":30,
                     "xRestrict":0,"aiType":1,"createDate":"2026-11-03T10:00:00+09:00","uploadDate":"2026-11-05T10:00:00+09:00",
                     "bookmarkCount":12,"description":"A story","tags":{"tags":[]}}
                    """);
            case "/ajax/illust/57/pages" -> {
                MANGA_PAGE_REQUESTS.incrementAndGet();
                String page = """
                        {"width":480,"height":640,"urls":{"regular":"https://i.pximg.net/page.png","original":"https://i.pximg.net/page.png"}}
                        """;
                yield body("[" + String.join(",", Collections.nCopies(30, page)) + "]");
            }
            case "/ajax/illust/42/pages" -> """
                    {"error":false,"body":[{"width":1,"height":1,"urls":{"regular":"https://i.pximg.net/page.png","original":"https://i.pximg.net/page.png"}}]}
                    """;
            case "/ajax/search/artworks/cat" -> """
                    {"error":false,"body":{"illustManga":{"data":[],"total":0,"lastPage":1}}}
                    """;
            case "/ajax/search/manga/cat" -> {
                assertThat(uri.getQuery()).contains("type=manga");
                yield body("{\"manga\":{\"data\":[" + ARTWORK_CARD + "],\"total\":1,\"lastPage\":1}}");
            }
            case "/ajax/search/novels/cat" -> body("{\"novel\":{\"data\":[{\"isMasked\":true}," + NOVEL_CARD
                    + "],\"total\":2,\"lastPage\":1}}");
            case "/ajax/ranking/novel" -> body("""
                    {"date":"11/26/2026","start":null,"end":null,"display_a":{"mode":"daily","page":1,"next":null,
                     "rank_a":[{"rank":1,"id":"88","title":"Story","user_id":"3","user_name":"Author","character_count":"12"}]}}
                    """);
            case "/ajax/user/3/profile/all" -> body("""
                    {"novels":{"88":null},"mangaSeries":[],"novelSeries":[{"id":"24","userId":"3","title":"Series",
                     "cover":{"urls":{"240mw":"https://i.pximg.net/series.png"}}}]}
                    """);
            case "/ajax/user/3/novels" -> body("{\"88\":" + NOVEL_CARD + "}");
            case "/ajax/novel/88" -> body("""
                    {"id":"88","title":"Story","userId":"3","userName":"Author","language":"ja",
                     "tags":{"tags":[{"tag":"ink"}]},"content":"[newpage]正文[uploadedimage:16][pixivimage:42][pixivimage:77]",
                     "url":"https://i.pximg.net/novel-cover.png","coverUrl":"https://i.pximg.net/novel-cover.png",
                     "textEmbeddedImages":{"16":{"novelImageId":"16","urls":{"original":"https://i.pximg.net/novel-illustration.png"}}}}
                    """);
            case "/ajax/novel/88/insert_illusts" -> body("""
                    {"42":{"id":42,"page":1,"visible":true},"77":{"visible":false}}
                    """);
            case "/ajax/novel/series/24" -> body("""
                    {"id":24,"title":"Series","userId":"3","userName":"Author","caption":"About","publishedContentCount":1}
                    """);
            case "/ajax/novel/series_content/24" -> body("""
                    {"page":{"seriesContents":[{"id":88,"userId":3,"title":"Story",
                    "series":{"id":24,"viewableType":0,"contentOrder":1}}]}}
                    """);
            case "/ajax/user/self" -> {
                assertThat(cookie).isIn(FIXTURE_COOKIE, MISMATCHED_COOKIE);
                yield "{\"userData\":{\"id\":21}}";
            }
            case "/ajax/user/21/novels/bookmarks" -> {
                assertThat(cookie).isEqualTo(FIXTURE_COOKIE);
                yield body("{\"works\":[" + NOVEL_CARD + "],\"total\":1}");
            }
            case "/ajax/follow_latest/illust" -> {
                assertThat(cookie).isEqualTo(FIXTURE_COOKIE);
                yield body("{\"page\":{\"ids\":[42],\"isLastPage\":true},\"thumbnails\":{\"illust\":[" + ARTWORK_CARD + "]}}");
            }
            case "/ajax/user/21/following" -> {
                assertThat(cookie).isEqualTo(FIXTURE_COOKIE);
                yield body("{\"total\":1,\"users\":[{\"userId\":\"7\",\"userName\":\"Artist\",\"userComment\":\"About me\"}]}");
            }
            case "/ajax/user/21/profile/all" -> {
                assertThat(cookie).isEqualTo(FIXTURE_COOKIE);
                yield body("{\"collectionIds\":[\"" + COLLECTION_ID + "\"]}");
            }
            case "/ajax/user/21/profile/collections" -> body("""
                    {"works":[{"id":"%s","title":"Shelf","thumbnailImageUrl":"https://i.pximg.net/cover.jpg"}]}
                    """.formatted(COLLECTION_ID));
            case "/ajax/collection/" + COLLECTION_ID -> body("""
                    {"data":{"detail":{"tiles":[
                      {"type":"Work","status":"Active","workType":"illust","workId":"42"},
                      {"type":"Work","status":"Active","workType":"novel","workId":"88"}]}},
                     "thumbnails":{"illust":[%s],"novel":[%s]}}
                    """.formatted(ARTWORK_CARD, NOVEL_CARD));
            case "/rpc/cps.php" -> {
                assertThat(cookie).isNull();
                assertThat(uri.getRawQuery()).matches(".*keyword=(a%2Bb%20%26%20c|Cat%20Artist)(&.*)?");
                yield "{\"candidates\":[{\"tag_name\":\"ink\",\"tag_translation\":\"Ink\"}]}";
            }
            case "/ranking.php" -> {
                assertThat(uri.getQuery()).contains("format=json");
                if (uri.getQuery().contains("date=")) throw new PixivAjaxException(PixivAjaxFailure.HTTP_STATUS, 429);
                if (uri.getQuery().contains("mode=daily_r18")) {
                    assertThat(cookie).isEqualTo(FIXTURE_COOKIE);
                    yield "{\"date\":\"20261001\",\"mode\":\"daily_r18\",\"page\":1,\"contents\":[],\"next\":false}";
                }
                yield "{\"date\":\"20240102\",\"mode\":\"daily\",\"page\":1,\"contents\":[],\"next\":false}";
            }
            case "/ajax/illust/42/ugoira_meta" -> body("""
                    {"src":"https://i.pximg.net/test.zip","mime_type":"image/png",
                    "frames":[{"file":"0.png","delay":17},{"file":"1.png","delay":41}]}
                    """);
            default -> throw new AssertionError("Unexpected Pixiv request " + uri.getPath());
        };
    }

    static PixivMediaClient media() {
        return (source, referer, maximumBytes) -> {
            assertThat(maximumBytes).isPositive();
            String page = referer.toString();
            if ("/truncated.png".equals(source.getPath())) {
                // 声明长度大于实际正文：回传开始后才发现截断。
                return new PixivMediaStream(new ByteArrayInputStream(PNG), PNG.length + 100L, maximumBytes);
            }
            if ("/avatar.png".equals(source.getPath())) {
                assertThat(page).isEqualTo("https://www.pixiv.net/users/3");
            } else if (List.of("/novel-cover.png", "/novel-illustration.png").contains(source.getPath())) {
                assertThat(page).isEqualTo("https://www.pixiv.net/novel/show.php?id=88");
            } else if (source.toString().equals("https://i.pximg.net/test.zip")) {
                assertThat(page).isEqualTo("https://www.pixiv.net/artworks/42");
                return new PixivMediaStream(new ByteArrayInputStream(animationArchive()), -1, maximumBytes);
            } else {
                assertThat(source.toString()).isEqualTo("https://i.pximg.net/page.png");
            }
            return new PixivMediaStream(new ByteArrayInputStream(PNG), PNG.length, maximumBytes);
        };
    }

    private static byte[] animationArchive() {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (int i = 0; i < 2; i++) {
                var frame = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
                frame.setRGB(0, 0, i == 0 ? 0xff0000 : 0x0000ff);
                zip.putNextEntry(new ZipEntry(i + ".png"));
                ImageIO.write(frame, "png", zip);
                zip.closeEntry();
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return bytes.toByteArray();
    }

    static class Bootstrap implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override public void initialize(ConfigurableApplicationContext context) {
            try {
                for (String kind : List.of("config", "state", "data", "plugins", "instance")) {
                    String key = "pixivdownload." + kind + "-dir";
                    String path = Files.createDirectories(home.resolve(kind)).toString();
                    previousProperties.put(key, System.setProperty(key, path));
                    TestPropertyValues.of(key + "=" + path).applyTo(context);
                }
                Path plugins = home.resolve("plugins");
                PluginTestProvenance.stageSqliteProvider(plugins);
                Path artifact = plugins.resolve(ID + ".jar");
                String packaged = System.getProperty(ID + ".plugin.jar");
                if (packaged == null) {
                    PluginTestArtifacts.zipDirectoryAsJar(Path.of(System.getProperty(ID + ".plugin.classes")), artifact);
                } else {
                    Files.copy(Path.of(packaged), artifact);
                }
                PluginTestProvenance.writeVerifiedLocalUpload(plugins, artifact);
                PluginTestProvenance.registerBootstrapSession(context, PluginEnabledSnapshot.empty(),
                        PluginTestProvenance.verifier());
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }
    }

    static class RestorePaths extends AbstractTestExecutionListener {
        @Override public int getOrder() { return 0; }
        @Override public void afterTestClass(TestContext ignored) {
            previousProperties.forEach((key, value) -> {
                if (value == null) System.clearProperty(key); else System.setProperty(key, value);
            });
        }
    }

    @Test void separateGrantsExposeContentAndWithdrawWithThePlugin() throws Exception {
        setup.init("content-test", "fixture-password", "solo");
        var session = new Cookie("pixiv_session", setup.createSession(false));
        String metadata = issue(session, "metadata.read");
        String images = issue(session, "image.read");

        JsonNode openApi = catalog(session);
        JsonNode paths = openApi.required("paths");
        for (String path : List.of("/search", "/rankings", "/artworks/{id}", "/users/{id}",
                "/users/{id}/artworks", "/series/{id}", "/users/{id}/series", "/artworks/{id}/related",
                "/artworks/{id}/comments", "/artworks/{id}/comments/{commentId}/replies", "/tags/suggest", "/tags/search",
                "/users/search", "/search/users-and-tags", "/users/{id}/bookmarks", "/collections/search", "/users/{id}/collections",
                "/collections/{collectionId}/works", "/discover", "/latest")) {
            assertThat(paths.required(API + path).required("get").required("x-program-permission").asString())
                    .isEqualTo(ID + ":metadata.read");
        }
        for (String path : List.of("/artworks/{id}/related", "/novels/{id}/related")) {
            assertThat(paths.required(API + path).required("get").requiredAt("/responses/410").toString())
                    .contains("PIXIV_RECOMMENDATION_EXPIRED");
        }
        for (String path : List.of("/search", "/novels/search", "/novel-series/search")) {
            assertThat(paths.required(API + path).required("get").requiredAt("/responses/403").toString())
                    .contains("PIXIV_PREMIUM_REQUIRED");
        }
        for (String route : List.of("/users/{id}/artworks", "/users/{id}/novels")) {
            var author = paths.required(API + route).required("get");
            var tag = author.required("parameters").valueStream().filter(p -> "tag".equals(p.path("name").asString())).findFirst().orElseThrow();
            assertThat(tag.requiredAt("/schema/type").asString()).isEqualTo("array");
            assertThat(author.requiredAt("/responses/422").toString()).contains("PIXIV_TAG_SEARCH_TOO_BROAD");
        }
        var operation = paths.required(IMAGE_TEMPLATE).required("get");
        assertThat(operation.required("x-program-permission").asString()).isEqualTo(ID + ":image.read");
        assertThat(operation.requiredAt("/responses/200/content/image~1png/schema/format").asString())
                .isEqualTo("binary");
        JsonNode ranking = paths.required(API + "/rankings").required("get");
        assertThat(ranking.at("/responses/429/content/application~1json/schema").toString())
                .contains("\"PIXIV_RATE_LIMITED\"");
        assertThat(ranking.at("/responses/502/content/application~1json/schema").toString())
                .contains("\"PIXIV_UNAVAILABLE\"");
        for (String path : List.of("/novels/search", "/novels/rankings", "/users/{id}/novels", "/novels/{id}", "/novel-series/{id}",
                "/novel-series/search", "/novels/{id}/comments", "/novels/{id}/comments/{commentId}/replies", "/novels/{id}/related")) {
            assertThat(paths.required(API + path).required("get").required("x-program-permission").asString())
                    .isEqualTo(ID + ":novel.read");
        }
        for (String path : List.of("/me/bookmarks", "/me/follow-latest", "/me/following", "/me/collections",
                "/me/collections/{collectionId}/works", "/me", "/me/watchlist", "/me/bookmark-tags", "/me/recommendations")) {
            var personal = paths.required(API + path).required("get");
            assertThat(personal.required("x-program-permission").asString()).isEqualTo(ID + ":personal.read");
            assertThat(personal.required("parameters").valueStream().map(node -> node.path("name").asString()).toList())
                    .contains("X-Acquisition-Credential", SELECTED);
        }
        var animation = paths.required(API + "/artworks/{id}/animation").required("get");
        for (String path : List.of("/novels/{id}/cover", "/novels/{id}/images/{imageId}", "/users/{id}/avatar")) {
            var media = paths.required(API + path).required("get");
            assertThat(media.required("x-program-permission").asString()).isEqualTo(ID + ":image.read");
            assertThat(media.requiredAt("/responses/200/content/image~1png/schema/format").asString()).isEqualTo("binary");
        }
        assertThat(animation.required("x-program-permission").asString()).isEqualTo(ID + ":animation.read");
        assertThat(animation.requiredAt("/responses/200/content/image~1apng/schema/format").asString())
                .isEqualTo("binary");

        mvc.perform(get(IMAGE)).andExpect(status().isUnauthorized());
        mvc.perform(get(IMAGE).secure(true).header("Authorization", metadata))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.programPermission").value(ID + ":image.read"));
        mvc.perform(get(API + "/search").param("word", "cat").secure(true).header("Authorization", images))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.programPermission").value(ID + ":metadata.read"));
        mvc.perform(get(API + "/search").param("word", "cat").secure(true).header("Authorization", metadata))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isArray());
        mvc.perform(get(API + "/search").param("word", "cat").param("type", "manga").secure(true).header("Authorization", metadata))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].type").value("manga"));
        mvc.perform(get(API + "/rankings").secure(true).header("Authorization", metadata))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rankingDate").value("2024-01-02"));
        var root = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        var captured = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        captured.start();
        root.addAppender(captured);
        try {
            mvc.perform(get(API + "/rankings").param("date", "2024-01-01").secure(true).header("Authorization", metadata)
                            .header("X-Acquisition-Credential", FIXTURE_COOKIE))
                    .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("PIXIV_RATE_LIMITED"));
            mvc.perform(get(API + "/users/5").secure(true).header("Authorization", metadata))
                    .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("PIXIV_RESPONSE_INVALID"));
            mvc.perform(get(API + "/users/6/avatar").secure(true).header("Authorization", images));
        } finally {
            root.detachAppender(captured);
        }
        // 上游失败在默认级别留下固定英文诊断：路由模板、数字路径 ID、耗时、错误码、状态与源码位置，
        // 不含查询参数或凭据。
        var failures = captured.list.stream()
                .filter(event -> event.getFormattedMessage().startsWith("Pixiv content request failed")).toList();
        assertThat(failures).hasSize(2).allSatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
            assertThat(event.getFormattedMessage()).containsPattern("elapsedMs=\\d+").contains(" at=")
                    .doesNotContain("2024", FIXTURE_COOKIE, "PHPSESSID");
        });
        assertThat(failures.get(0).getFormattedMessage()).contains("operation=" + API + "/rankings ",
                "code=PIXIV_RATE_LIMITED", "upstreamStatus=429", "at=ContentQueries.");
        assertThat(failures.get(1).getFormattedMessage()).contains("operation=" + API + "/users/{id} id=5 ",
                "code=PIXIV_RESPONSE_INVALID", "at=ContentQueries.user:");
        // 回传开始后才发现截断时由插件记录一次中止原因，宿主不再记成服务器读写错误。
        assertThat(captured.list).filteredOn(event -> event.getFormattedMessage().startsWith("Pixiv content stream aborted"))
                .singleElement().satisfies(event -> {
                    assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
                    assertThat(event.getFormattedMessage()).contains("operation=" + API + "/users/{id}/avatar id=6 ",
                            "reason=media_transfer_failed", "bytesSent=" + PNG.length);
                });
        assertThat(captured.list).noneMatch(event -> event.getLevel() == ch.qos.logback.classic.Level.ERROR);
        mvc.perform(get(IMAGE).param("size", "original").secure(true).header("Authorization", images))
                .andExpect(status().isOk()).andExpect(content().contentType("image/png")).andExpect(content().bytes(PNG));

        verifyDiscovery(metadata, images);
        verifyNovels(session, metadata, images);
        verifyMangaPages(metadata, images);
        verifyPersonal(session, metadata, openApi);
        verifyAnimation(session, images);

        lifecycle.stop(ID);
        mvc.perform(get(IMAGE).secure(true).header("Authorization", images)).andExpect(status().isNotFound());
        assertThat(catalog(session).required("paths").propertyNames()).noneMatch(path -> path.startsWith(API + "/"));
    }

    private void verifyDiscovery(String metadata, String images) throws Exception {
        mvc.perform(get(API + "/artworks/42/related").param("limit", "5").secure(true).header("Authorization", metadata))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(42));
        mvc.perform(get(API + "/artworks/42/related").param("limit", "49").secure(true).header("Authorization", metadata))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("limit"));
        for (String subpath : List.of("related", "comments")) {
            mvc.perform(get(API + "/artworks/77/" + subpath).secure(true).header("Authorization", metadata))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get(API + "/artworks/42/comments").param("page", "2").secure(true).header("Authorization", metadata))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].stampId").value(5))
                .andExpect(jsonPath("$.items[0].hasReplies").value(true)).andExpect(jsonPath("$.hasNext").value(false));
        mvc.perform(get(API + "/users/3/series").param("type","novel").secure(true).header("Authorization", metadata))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].detailPath").value(API + "/novel-series/24"))
                .andExpect(jsonPath("$.items[0].coverUrl").value("https://i.pximg.net/series.png"));
        mvc.perform(get(API + "/tags/suggest").param("word", "a+b & c").secure(true).header("Authorization", metadata)
                        .header("X-Acquisition-Credential", FIXTURE_COOKIE))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].translation").value("Ink"));
        mvc.perform(get(API + "/users/3").secure(true).header("Authorization", metadata))
                .andExpect(status().isOk()).andExpect(jsonPath("$.avatarPath").value(API + "/users/3/avatar"));
        mvc.perform(get(API + "/users/3/avatar").secure(true).header("Authorization", images))
                .andExpect(status().isOk()).andExpect(content().contentType("image/png")).andExpect(content().bytes(PNG));
        mvc.perform(get(API + "/users/3/avatar").secure(true).header("Authorization", metadata)).andExpect(status().isForbidden());
        mvc.perform(get(API + "/rankings").param("mode", "monthly").param("content", "ugoira")
                        .secure(true).header("Authorization", metadata))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("content"));
        mvc.perform(get(API + "/search").param("word", "cat").param("startDate","2026-11-20").param("endDate","2026-11-01")
                        .secure(true).header("Authorization", metadata))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("endDate"));
    }

    private void verifyMangaPages(String metadata, String images) throws Exception {
        int initial = MANGA_PAGE_REQUESTS.get();
        mvc.perform(get(API + "/artworks/57").secure(true).header("Authorization", metadata))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pages[29].height").value(640))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.description").value("A story")).andExpect(jsonPath("$.bookmarkCount").value(12))
                .andExpect(jsonPath("$.aiType").value(1)).andExpect(jsonPath("$.xRestrict").value(0))
                .andExpect(jsonPath("$.createdAt").value("2026-11-03T01:00:00Z"));
        for (int i = 0; i < 30; i++) {
            mvc.perform(get(API + "/artworks/57/pages/" + i + "/image").secure(true).header("Authorization", images))
                    .andExpect(status().isOk()).andExpect(content().bytes(PNG));
        }
        assertThat(MANGA_PAGE_REQUESTS.get() - initial).isEqualTo(1);
        mvc.perform(get(API + "/artworks/57/pages/0/image").secure(true).header("Authorization", images)
                        .header("X-Acquisition-Credential", FIXTURE_COOKIE))
                .andExpect(status().isOk()).andExpect(content().bytes(PNG));
        assertThat(MANGA_PAGE_REQUESTS.get() - initial).isEqualTo(2);
    }

    private void verifyNovels(Cookie session, String oldGrant, String images) throws Exception {
        String novels = issue(session, "novel.read");
        for (String path : List.of("/novels/search", "/novels/88", "/novel-series/24", "/novels/rankings", "/users/3/novels")) {
            mvc.perform(get(API + path).secure(true).header("Authorization", oldGrant))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.programPermission").value(ID + ":novel.read"));
        }
        mvc.perform(get(API + "/novels/search").param("word", "cat").secure(true).header("Authorization", novels))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].id").value(88));
        mvc.perform(get(API + "/users/3/novels").secure(true).header("Authorization", novels))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].detailPath").value(API + "/novels/88"));
        mvc.perform(get(API + "/novels/rankings").secure(true).header("Authorization", novels))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rankingDate").value("2026-11-26"))
                .andExpect(jsonPath("$.items[0].rank").value(1)).andExpect(jsonPath("$.items[0].novel.id").value(88));
        mvc.perform(get(API + "/novels/88").secure(true).header("Authorization", novels))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.contentFormat").value("pixiv-novel-text"))
                .andExpect(jsonPath("$.content").value("[newpage]正文[uploadedimage:16][pixivimage:42][pixivimage:77]"))
                .andExpect(jsonPath("$.images.length()").value(2))
                .andExpect(jsonPath("$.images[0].marker").value("[uploadedimage:16]"))
                .andExpect(jsonPath("$.images[0].imagePath").value(API + "/novels/88/images/16"))
                .andExpect(jsonPath("$.images[1].imagePath").value(IMAGE))
                .andExpect(jsonPath("$.coverPath").value(API + "/novels/88/cover"));
        for (String path : List.of("/novels/88/cover", "/novels/88/images/16")) {
            mvc.perform(get(API + path).secure(true).header("Authorization", novels))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.programPermission").value(ID + ":image.read"));
            mvc.perform(get(API + path).secure(true).header("Authorization", images))
                    .andExpect(status().isOk()).andExpect(content().contentType("image/png")).andExpect(content().bytes(PNG));
        }
        mvc.perform(get(API + "/novels/88/images/17").secure(true).header("Authorization", images))
                .andExpect(status().isNotFound());
        for (String path : List.of("/novels/99/cover", "/novels/99/images/16", "/artworks/77/pages/0/image")) {
            mvc.perform(get(API + path).secure(true).header("Authorization", images)).andExpect(status().isForbidden());
        }
        mvc.perform(get(API + "/novels/99").secure(true).header("Authorization", novels))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("guest.invite.novel.forbidden"));
        mvc.perform(get(API + "/novel-series/24").secure(true).header("Authorization", novels))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.items[0].order").value(1)).andExpect(jsonPath("$.items[0].novel.id").value(88));
    }

    private void verifyPersonal(Cookie session, String oldGrant, JsonNode openApi) throws Exception {
        var entry = new AcquisitionCredentialLibrary.Entry("sample", "Sample", true, FIXTURE_COOKIE);
        library.save(new AcquisitionCredentialLibrary.Update(library.read().revision(), List.of(entry)));
        String personal = issue(session, "personal.read");
        for (String path : List.of("/me/bookmarks", "/me/follow-latest", "/me/following", "/me/collections",
                "/me/collections/" + COLLECTION_ID + "/works")) {
            ResultMatcher badRequestSchema = matchesBadRequestSchema(openApi, API + path.replace(COLLECTION_ID, "{collectionId}"));
            mvc.perform(get(API + path).secure(true).header("Authorization", oldGrant))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.programPermission").value(ID + ":personal.read"));
            mvc.perform(get(API + path).secure(true).header("Authorization", personal))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ACQUISITION_CREDENTIAL_REQUIRED"))
                    .andExpect(badRequestSchema);
            mvc.perform(get(API + path).param("page", "0").secure(true)
                            .header("Authorization", personal).header("X-Acquisition-Credential", FIXTURE_COOKIE))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("error.request.param.invalid"))
                    .andExpect(jsonPath("$.field").value("page")).andExpect(jsonPath("$.reason").value("out_of_range"))
                    .andExpect(badRequestSchema);
            mvc.perform(get(API + path).secure(true).header("Authorization", personal).header(SELECTED, "sample"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.programPermission").value("core:credentials.use"));
        }
        String selected = issue(session, "personal.read", "core:credentials.use");
        mvc.perform(get(API + "/me/bookmark-tags").param("rest","hide").secure(true).header("Authorization",selected).header(SELECTED,"sample"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].tag").value("secret")).andExpect(jsonPath("$.items[0].count").value(4));
        mvc.perform(get(API + "/me/watchlist").param("page","2").param("type","novel").secure(true)
                        .header("Authorization",selected).header(SELECTED,"sample")).andExpect(status().isOk())
                .andExpect(jsonPath("$.hasNext").value(true)).andExpect(jsonPath("$.items[0].detailPath").value(API + "/novel-series/24"));
        mvc.perform(get(API + "/me/recommendations").secure(true).header("Authorization",selected).header(SELECTED,"sample"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(42));
        var publicRead = issue(session, "metadata.read");
        mvc.perform(get(API + "/me/watchlist").secure(true).header("Authorization",publicRead)).andExpect(status().isForbidden());
        mvc.perform(get(API + "/users/search").param("word","Cat Artist").param("exact","true").param("page","2")
                        .secure(true).header("Authorization", publicRead).header("X-Acquisition-Credential", FIXTURE_COOKIE))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].name").value("Cat Artist"));
        mvc.perform(get(API + "/search/users-and-tags").param("word", "Cat Artist").param("exact", "true")
                        .param("userPage", "2").secure(true).header("Authorization", publicRead)
                        .header("X-Acquisition-Credential", FIXTURE_COOKIE))
                .andExpect(status().isOk()).andExpect(jsonPath("$.users.page").value(2))
                .andExpect(jsonPath("$.users.items[0].id").value(3)).andExpect(jsonPath("$.tags.page").value(1))
                .andExpect(jsonPath("$.tags.items[1].name").value("cat"));
        mvc.perform(get(API + "/search/users-and-tags").param("word", "Cat Artist").param("tagPage", "0")
                        .secure(true).header("Authorization", publicRead))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("tagPage"));
        mvc.perform(get(API + "/users/3/artworks").param("tag", "ink,pen", "paper").secure(true).header("Authorization", publicRead))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].id").value(42));
        mvc.perform(get(API + "/users/3/novels").param("tag", "ink,pen").secure(true).header("Authorization", issue(session, "novel.read")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(88));
        mvc.perform(get(API + "/users/3/artworks").param("tag", "").secure(true).header("Authorization", publicRead))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("tag"));
        mvc.perform(get(API + "/users/3/bookmarks").secure(true).header("Authorization",publicRead)
                        .header("X-Acquisition-Credential",FIXTURE_COOKIE))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(42));
        mvc.perform(get(API + "/latest").param("type","novel").param("cursor","108").secure(true)
                        .header("Authorization",publicRead).header("X-Acquisition-Credential",FIXTURE_COOKIE))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nextCursor").value(88));
        mvc.perform(get(API + "/rankings").param("mode","daily_r18").secure(true).header("Authorization",publicRead))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ACQUISITION_CREDENTIAL_REQUIRED"));
        mvc.perform(get(API + "/rankings").param("mode","daily_r18").secure(true)
                        .header("Authorization",publicRead).header("X-Acquisition-Credential",FIXTURE_COOKIE))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("daily_r18"));
        mvc.perform(get(API + "/search").param("word","cat").param("minBookmarks","100").secure(true).header("Authorization",publicRead))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ACQUISITION_CREDENTIAL_REQUIRED"));
        mvc.perform(get(API + "/search").param("word","cat").param("minBookmarks","100").secure(true)
                        .header("Authorization",publicRead).header("X-Acquisition-Credential",FIXTURE_COOKIE))
                .andExpect(status().isOk());
        mvc.perform(get(API + "/me").secure(true).header("Authorization", oldGrant)).andExpect(status().isForbidden());
        mvc.perform(get(API + "/me").secure(true).header("Authorization", personal))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ACQUISITION_CREDENTIAL_REQUIRED"));
        mvc.perform(get(API + "/me").secure(true).header("Authorization", personal)
                        .header("X-Acquisition-Credential", MISMATCHED_COOKIE))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PIXIV_ACCESS_DENIED"));
        mvc.perform(get(API + "/me").secure(true).header("Authorization", selected).header(SELECTED, "sample"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.id").value(21)).andExpect(jsonPath("$.name").value("Test account"));
        mvc.perform(get(API + "/me/bookmarks").param("type", "novel").secure(true)
                        .header("Authorization", selected).header(SELECTED, "sample"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.items[0].detailPath").value(API + "/novels/88"));
        mvc.perform(get(API + "/me/follow-latest").secure(true)
                        .header("Authorization", personal).header("X-Acquisition-Credential", FIXTURE_COOKIE))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.items[0].id").value(42)).andExpect(jsonPath("$.hasNext").value(false));
        mvc.perform(get(API + "/me/following").secure(true).header("Authorization", selected).header(SELECTED, "sample"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(7));
        mvc.perform(get(API + "/me/collections").secure(true).header("Authorization", selected).header(SELECTED, "sample"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(COLLECTION_ID));
        mvc.perform(get(API + "/me/collections/" + COLLECTION_ID + "/works").secure(true)
                        .header("Authorization", selected).header(SELECTED, "sample"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].type").value("manga"))
                .andExpect(jsonPath("$.items[1].type").value("novel"));
        mvc.perform(get(API + "/me/collections/123/works").secure(true).header("Authorization", selected).header(SELECTED, "sample"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PIXIV_CONTENT_NOT_FOUND"));
    }

    private ResultMatcher matchesBadRequestSchema(JsonNode openApi, String path) {
        var response = openApi.required("paths").required(path).required("get")
                .requiredAt("/responses/400/content/application~1json/schema");
        var document = json.createObjectNode();
        document.set("components", openApi.required("components"));
        document.set("allOf", json.createArrayNode().add(response));
        var schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(document);
        return result -> {
            var body = json.readTree(result.getResponse().getContentAsByteArray());
            assertThat(schema.validate(body)).as("%s HTTP 400 (%s)", path, body.required("code").asString()).isEmpty();
        };
    }

    private void verifyAnimation(Cookie session, String imageGrant) throws Exception {
        String path = API + "/artworks/42/animation";
        mvc.perform(get(path).secure(true).header("Authorization", imageGrant))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.programPermission").value(ID + ":animation.read"));
        byte[] bytes = mvc.perform(get(path).secure(true).header("Authorization", issue(session, "animation.read")))
                .andExpect(status().isOk()).andExpect(content().contentType("image/apng"))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andReturn().getResponse().getContentAsByteArray();
        var input = ByteBuffer.wrap(bytes);
        assertThat(input.getLong()).isEqualTo(0x89504e470d0a1a0aL);
        int controls = 0;
        boolean sawAnimationControl = false;
        while (input.hasRemaining()) {
            int size = input.getInt();
            int type = input.getInt();
            if (type == 0x6163544c) { // acTL
                assertThat(input.getInt(input.position())).isEqualTo(2);
                sawAnimationControl = true;
            }
            if (type == 0x6663544c) controls++; // fcTL
            input.position(input.position() + size + Integer.BYTES);
        }
        assertThat(sawAnimationControl).isTrue();
        assertThat(controls).isEqualTo(2);
    }

    private JsonNode catalog(Cookie session) throws Exception {
        byte[] document = mvc.perform(get("/api/openapi.json").cookie(session)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        return json.readTree(document);
    }

    private String issue(Cookie session, String... permissions) throws Exception {
        var grants = Arrays.stream(permissions).map(p -> p.contains(":") ? p : ID + ":" + p).toList();
        var policy = Map.of("name", String.join(" + ", permissions), "permissions", grants, "anyIp", true,
                "networks", List.of());
        byte[] result = mvc.perform(post("/api/security/program-keys").cookie(session).header("Origin", "http://localhost")
                        .contentType("application/json").content(json.writeValueAsBytes(policy)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsByteArray();
        return "Bearer " + json.readTree(result).required("secret").asString();
    }
}
