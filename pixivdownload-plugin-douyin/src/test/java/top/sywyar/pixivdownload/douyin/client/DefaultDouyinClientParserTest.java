package top.sywyar.pixivdownload.douyin.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.douyin.client.signature.DouyinSignedUriBuilder;
import top.sywyar.pixivdownload.douyin.model.DouyinCanonicalKind;
import top.sywyar.pixivdownload.douyin.model.DouyinAccount;
import top.sywyar.pixivdownload.douyin.model.DouyinAccountSource;
import top.sywyar.pixivdownload.douyin.model.DouyinMediaType;
import top.sywyar.pixivdownload.douyin.model.DouyinParsedKind;
import top.sywyar.pixivdownload.douyin.model.DouyinWorkKind;
import top.sywyar.pixivdownload.douyin.parse.DouyinUrlParser;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DefaultDouyinClient 抖音作品 JSON 解析")
class DefaultDouyinClientParserTest {

    @Test
    @DisplayName("解析公开视频候选")
    void parsesVideoWork() throws Exception {
        DefaultDouyinClient client = client("""
                {"aweme_detail":{"aweme_id":"7351","desc":"Video title","create_time":1710000000,
                "author":{"uid":"u1","nickname":"Author"},
                "video":{"bit_rate":[{"bit_rate":2000,"play_addr":{"url_list":["https://v3.douyinvod.com/video.mp4"],"data_size":10}}],
                "cover":{"url_list":["https://p3.douyinpic.com/cover.jpg"]}}}}
                """);

        var work = client.resolvePublicWork("https://www.douyin.com/video/7351", null);

        assertThat(work.id()).isEqualTo("7351");
        assertThat(work.title()).isEqualTo("Video title");
        assertThat(work.description()).isEqualTo("Video title");
        assertThat(work.itemTitle()).isNull();
        assertThat(work.caption()).isNull();
        assertThat(work.authorName()).isEqualTo("Author");
        assertThat(work.kind()).isEqualTo(DouyinWorkKind.VIDEO);
        assertThat(work.media()).singleElement()
                .satisfies(media -> {
                    assertThat(media.type()).isEqualTo(DouyinMediaType.VIDEO);
                    assertThat(media.url()).isEqualTo(URI.create("https://v3.douyinvod.com/video.mp4"));
                    assertThat(media.sizeBytes()).isEqualTo(10L);
                });
    }

    @Test
    @DisplayName("分别解析 desc、item_title 和 caption 字段")
    void parsesAwemeTextFieldsSeparately() throws Exception {
        DefaultDouyinClient client = client("""
                {"aweme_detail":{"aweme_id":"7360","desc":"Desc text","item_title":"Item text","caption":"Caption text",
                "share_info":{"share_title":"Share text"},
                "video":{"play_addr":{"url_list":["https://v3.douyinvod.com/text.mp4"]}}}}
                """);

        var work = client.resolvePublicWork("https://www.douyin.com/video/7360", null);

        assertThat(work.description()).isEqualTo("Desc text");
        assertThat(work.itemTitle()).isEqualTo("Item text");
        assertThat(work.caption()).isEqualTo("Caption text");
        assertThat(work.title()).isEqualTo("Item text");
    }

    @Test
    @DisplayName("展示标题按 item_title、分享标题、desc、caption 和 ID 兜底")
    void titleFallbackUsesDisplayPriority() throws Exception {
        assertThat(resolveTitle("""
                {"aweme_id":"8101","desc":"Desc title","item_title":"Item title","caption":"Caption title",
                "share_info":{"share_title":"Share title"},
                "video":{"play_addr":{"url_list":["https://v3.douyinvod.com/8101.mp4"]}}}
                """)).isEqualTo("Item title");
        assertThat(resolveTitle("""
                {"aweme_id":"8102","desc":"Desc title","caption":"Caption title",
                "share_info":{"share_title":"Share title"},
                "video":{"play_addr":{"url_list":["https://v3.douyinvod.com/8102.mp4"]}}}
                """)).isEqualTo("Share title");
        assertThat(resolveTitle("""
                {"aweme_id":"8103","desc":"Desc title","caption":"Caption title",
                "video":{"play_addr":{"url_list":["https://v3.douyinvod.com/8103.mp4"]}}}
                """)).isEqualTo("Desc title");
        assertThat(resolveTitle("""
                {"aweme_id":"8104","caption":"Caption title",
                "video":{"play_addr":{"url_list":["https://v3.douyinvod.com/8104.mp4"]}}}
                """)).isEqualTo("Caption title");
        assertThat(resolveTitle("""
                {"aweme_id":"8105",
                "video":{"play_addr":{"url_list":["https://v3.douyinvod.com/8105.mp4"]}}}
                """)).isEqualTo("8105");
    }

    @Test
    @DisplayName("解析图文图集图片候选")
    void parsesImageNote() throws Exception {
        DefaultDouyinClient client = client("""
                {"aweme_detail":{"aweme_id":"7352","desc":"Images","author":{"sec_uid":"sec","nickname":"Author"},
                "image_post_info":{"images":[
                {"watermark_free_download_url_list":["https://p3.douyinpic.com/a.jpg","https://p4.douyinpic.com/a.jpg"]},
                {"display_image":{"url_list":["https://p3.douyinpic.com/b.webp","https://p4.douyinpic.com/b.webp"]}}
                ]}}}
                """);

        var work = client.resolvePublicWork("https://www.douyin.com/note/7352", null);

        assertThat(work.kind()).isEqualTo(DouyinWorkKind.IMAGE_NOTE);
        assertThat(work.media()).hasSize(2);
        assertThat(work.media()).allMatch(media -> media.type() == DouyinMediaType.IMAGE);
        assertThat(work.media().get(1).extension()).isEqualTo("webp");
        assertThat(work.media().get(0).fallbackUrls())
                .containsExactly(URI.create("https://p4.douyinpic.com/a.jpg"));
        assertThat(work.media().get(1).fallbackUrls())
                .containsExactly(URI.create("https://p4.douyinpic.com/b.webp"));
    }

    @Test
    @DisplayName("实况照片严格在同一原始页索引配对并保留静态图位置")
    void pairsLivePhotoByOriginalNodeIndex() throws Exception {
        DefaultDouyinClient client = client("""
                {"aweme_detail":{"aweme_id":"7353","desc":"Live",
                "image_post_info":{"images":[{"display_image":{"url_list":["https://p3.douyinpic.com/a.jpg"]},
                "video":{}},
                {"display_image":{"url_list":["https://p3.douyinpic.com/b.jpg"]},
                "video":{"play_addr":{"url_list":["https://v3.douyinvod.com/live-b.mp4","https://v6.douyinvod.com/live-b.mp4"]}}},
                {"display_image":{"url_list":["https://p3.douyinpic.com/d.jpg"]}}]}}}
                """);

        var work = client.resolvePublicWork("https://www.douyin.com/gallery/7353", null);

        assertThat(work.kind()).isEqualTo(DouyinWorkKind.LIVE_PHOTO);
        assertThat(work.media()).extracting("id")
                .containsExactly("7353-p1", "7353-p2", "7353-live-p2", "7353-p3");
        assertThat(work.media()).extracting("fileNameStem")
                .containsExactly("7353-p01", "7353-p02", "7353-live-p02", "7353-p03");
        assertThat(work.media()).extracting("type")
                .containsExactly(
                        DouyinMediaType.IMAGE, DouyinMediaType.IMAGE,
                        DouyinMediaType.LIVE_PHOTO_VIDEO, DouyinMediaType.IMAGE);
        assertThat(work.media().get(2).url())
                .isEqualTo(URI.create("https://v3.douyinvod.com/live-b.mp4"));
        assertThat(work.media().get(2).fallbackUrls())
                .containsExactly(URI.create("https://v6.douyinvod.com/live-b.mp4"));
    }

    @Test
    @DisplayName("不同图片项的静态图与动态视频不得跨项拼成实况照片")
    void rejectsCrossItemLivePhotoPairing() {
        assertCode(() -> client("""
                        {"aweme_detail":{"aweme_id":"7361","desc":"Broken pair",
                        "image_post_info":{"images":[
                          {"display_image":{"url_list":["https://p3.douyinpic.com/a.jpg"]}},
                          {"video":{"play_addr":{"url_list":["https://v3.douyinvod.com/b.mp4"]}}}
                        ]}}}
                        """)
                        .resolvePublicWork("https://www.douyin.com/note/7361", null),
                DouyinClientErrorCode.MEDIA_URL_MISSING);
    }

    @Test
    @DisplayName("两个完整实况照片组按图片与动态视频相邻顺序输出")
    void keepsEachLivePhotoPairAdjacent() throws Exception {
        var work = client("""
                {"aweme_detail":{"aweme_id":"7362","desc":"Two pairs",
                "image_post_info":{"images":[
                  {"display_image":{"url_list":["https://p3.douyinpic.com/a.jpg"]},
                   "video":{"play_addr":{"url_list":["https://v3.douyinvod.com/a.mp4"]}}},
                  {"display_image":{"url_list":["https://p3.douyinpic.com/b.jpg"]},
                   "video":{"play_addr":{"url_list":["https://v3.douyinvod.com/b.mp4"]}}}
                ]}}}
                """).resolvePublicWork("https://www.douyin.com/note/7362", null);

        assertThat(work.kind()).isEqualTo(DouyinWorkKind.LIVE_PHOTO);
        assertThat(work.media()).extracting("id").containsExactly(
                "7362-p1", "7362-live-p1", "7362-p2", "7362-live-p2");
    }

    @Test
    @DisplayName("图片项级动态视频地址仍与同项静态图配对")
    void pairsItemLevelLivePhotoVideoAddress() throws Exception {
        var work = client("""
                {"aweme_detail":{"aweme_id":"7363","desc":"Alias pair",
                "image_post_info":{"images":[
                  {"display_image":{"url_list":["https://p3.douyinpic.com/a.jpg"]},
                   "video_play_addr":{"url_list":["https://v3.douyinvod.com/a.mp4"]}}
                ]}}}
                """).resolvePublicWork("https://www.douyin.com/note/7363", null);

        assertThat(work.kind()).isEqualTo(DouyinWorkKind.LIVE_PHOTO);
        assertThat(work.media()).extracting("id", "type").containsExactly(
                org.assertj.core.groups.Tuple.tuple("7363-p1", DouyinMediaType.IMAGE),
                org.assertj.core.groups.Tuple.tuple("7363-live-p1", DouyinMediaType.LIVE_PHOTO_VIDEO));
    }

    @Test
    @DisplayName("声明了动态视频结构但没有有效地址时不得降级为普通图文")
    void rejectsLivePhotoMotionWithoutUsableUrl() {
        assertCode(() -> client("""
                        {"aweme_detail":{"aweme_id":"7364","desc":"Missing motion",
                        "image_post_info":{"images":[
                          {"display_image":{"url_list":["https://p3.douyinpic.com/a.jpg"]},
                           "video":{"play_addr":{"url_list":[]}}}
                        ]}}}
                        """)
                        .resolvePublicWork("https://www.douyin.com/note/7364", null),
                DouyinClientErrorCode.MEDIA_URL_MISSING);
    }

    @Test
    @DisplayName("多个图片数组别名共存时只解析首个非空数组")
    void usesFirstNonEmptyImageArrayWithoutDuplicates() throws Exception {
        DefaultDouyinClient client = client("""
                {"aweme_detail":{"aweme_id":"7354","desc":"Aliases",
                "image_post_info":{
                  "images":[{"display_image":{"url_list":["https://p3.douyinpic.com/canonical.jpg"]}}],
                  "image_list":[{"display_image":{"url_list":["https://p3.douyinpic.com/nested-alias.jpg"]}}]
                },
                "images":[{"display_image":{"url_list":["https://p3.douyinpic.com/top-images.jpg"]}}],
                "image_list":[{"display_image":{"url_list":["https://p3.douyinpic.com/top-list.jpg"]}}]}}
                """);

        var work = client.resolvePublicWork("https://www.douyin.com/note/7354", null);

        assertThat(work.media()).singleElement().satisfies(media -> {
            assertThat(media.id()).isEqualTo("7354-p1");
            assertThat(media.url()).isEqualTo(URI.create("https://p3.douyinpic.com/canonical.jpg"));
        });
    }

    @Test
    @DisplayName("规范图片数组为空时解析顶层 image_list 并保留页索引")
    void parsesTopLevelImageListAfterEmptyAliases() throws Exception {
        DefaultDouyinClient client = client("""
                {"aweme_detail":{"aweme_id":"7355","desc":"Top level list",
                "image_post_info":{"images":[],"image_list":[]},
                "images":[],
                "image_list":[{"display_image":{"url_list":["https://p3.douyinpic.com/top.jpg"]},
                "video":{"play_addr":{"url_list":["https://v3.douyinvod.com/top-live.mp4"]}}}]}}
                """);

        var work = client.resolvePublicWork("https://www.douyin.com/note/7355", null);

        assertThat(work.media()).extracting("id")
                .containsExactly("7355-p1", "7355-live-p1");
        assertThat(work.media()).extracting("type")
                .containsExactly(DouyinMediaType.IMAGE, DouyinMediaType.LIVE_PHOTO_VIDEO);
    }

    @Test
    @DisplayName("作品详情请求使用示例项目的完整参数与本地签名")
    void usesReferenceCompatibleSignedDetailApiBeforePageFallback() throws Exception {
        FakeRestTemplate rest = new FakeRestTemplate();
        rest.enqueue(200, """
                {"aweme_detail":{"aweme_id":"7358","desc":"Detail",
                "video":{"play_addr":{"url_list":["https://v3.douyinvod.com/detail.mp4"]}}}}
                """);
        DouyinUrlParser parser = new DouyinUrlParser();
        var client = new DefaultDouyinClient(parser, rest,
                (input, cookie) -> parser.parse(input).orElseThrow());

        var work = client.resolvePublicWork("https://www.douyin.com/video/7358", "msToken=fromCookie; ttwid=tt");

        assertThat(work.id()).isEqualTo("7358");
        assertThat(rest.requests()).singleElement()
                .satisfies(uri -> {
                    assertThat(uri.getPath()).isEqualTo("/aweme/v1/web/aweme/detail/");
                    assertThat(uri.getRawQuery())
                            .contains("aweme_id=7358", "msToken=fromCookie", "a_bogus=")
                            .contains("version_code=290100", "version_name=29.1.0")
                            .doesNotContain("X-Bogus=");
                });
        assertThat(rest.cookies()).containsExactly("msToken=fromCookie; ttwid=tt");
    }

    @Test
    @DisplayName("最小详情 API 不可用时回退到公开作品页")
    void fallsBackToPublicPageWhenMinimalDetailApiFails() throws Exception {
        FakeRestTemplate rest = new FakeRestTemplate();
        rest.enqueue(200, "<html>signature blocked</html>");
        rest.enqueue(200, page("""
                {"aweme_detail":{"aweme_id":"7359","desc":"Page fallback",
                "video":{"play_addr":{"url_list":["https://v3.douyinvod.com/page.mp4"]}}}}
                """));
        DouyinUrlParser parser = new DouyinUrlParser();
        var client = new DefaultDouyinClient(parser, rest,
                (input, cookie) -> parser.parse(input).orElseThrow());

        var work = client.resolvePublicWork("https://www.douyin.com/video/7359",
                "msToken=fromCookie; ttwid=tt; odin_tt=odin; passport_csrf_token=csrf");

        assertThat(work.id()).isEqualTo("7359");
        assertThat(rest.requests()).hasSize(2);
        assertThat(rest.requests().get(0).getRawQuery())
                .contains("msToken=fromCookie", "a_bogus=")
                .doesNotContain("X-Bogus=");
        assertThat(rest.requests().get(1).getPath()).isEqualTo("/video/7359");
    }

    @Test
    @DisplayName("API 返回 403 时立即停止且不发送第二个签名请求")
    void stopsImmediatelyAfterForbiddenApiResponse() {
        FakeRestTemplate rest = new FakeRestTemplate();
        rest.enqueue(403, "forbidden");
        DouyinUrlParser parser = new DouyinUrlParser();
        var client = new DefaultDouyinClient(parser, rest,
                (input, cookie) -> parser.parse(input).orElseThrow());

        assertCode(() -> client.resolvePublicWork(
                        "https://www.douyin.com/video/7361", "msToken=fromCookie; ttwid=tt"),
                DouyinClientErrorCode.HTTP_FORBIDDEN);

        assertThat(rest.requests()).hasSize(1);
    }

    @Test
    @DisplayName("生产调用在连续请求中复用生成的 msToken 并同步发送 Cookie")
    void reusesGeneratedMsTokenInQueryAndCookieHeaders() throws Exception {
        FakeRestTemplate rest = new FakeRestTemplate();
        rest.enqueue(200, "{\"aweme_list\":[],\"has_more\":0,\"max_cursor\":\"0\"}");
        rest.enqueue(200, "{\"aweme_list\":[],\"has_more\":0,\"max_cursor\":\"0\"}");
        DouyinUrlParser parser = new DouyinUrlParser();
        var client = new DefaultDouyinClient(parser, rest,
                (input, cookie) -> parser.parse(input).orElseThrow());

        client.listUserWorksPage("sec-user", "0", 1, "ttwid=tt");
        client.listUserWorksPage("sec-user", "0", 1, "ttwid=tt");

        String firstToken = queryValue(rest.requests().get(0), "msToken");
        String secondToken = queryValue(rest.requests().get(1), "msToken");
        assertThat(firstToken.equals(secondToken)).isTrue();
        boolean cookiesMatch = rest.cookies().size() == 2
                && rest.cookies().get(0) != null
                && rest.cookies().get(1) != null
                && rest.cookies().get(0).endsWith("msToken=" + firstToken)
                && rest.cookies().get(1).endsWith("msToken=" + firstToken);
        assertThat(cookiesMatch).isTrue();
    }

    @Test
    @DisplayName("无媒体 URL、Cookie 过期、风控页与 unsupported 内容均有明确错误")
    void classifiesParserFailures() {
        assertCode(() -> client("""
                        {"aweme_detail":{"aweme_id":"7354","desc":"No media"}}
                        """, "{}")
                        .resolvePublicWork("https://www.douyin.com/video/7354", null),
                DouyinClientErrorCode.MEDIA_URL_MISSING);
        assertCode(() -> client("""
                        {"status_code":2483,"status_msg":"请先登录"}
                        """)
                        .resolvePublicWork("https://www.douyin.com/video/7355", "sid=expired"),
                DouyinClientErrorCode.COOKIE_EXPIRED);
        assertCode(() -> client("<html>验证码</html>", "<html>验证码</html>")
                        .resolvePublicWork("https://www.douyin.com/video/7356", null),
                DouyinClientErrorCode.LOGIN_OR_VERIFY_PAGE);
        assertCode(() -> client("")
                        .resolvePublicWork("https://www.douyin.com/music/123", null),
                DouyinClientErrorCode.UNSUPPORTED_CONTENT);
    }

    @Test
    @DisplayName("解析合集详情与分页作品")
    void parsesMixDetailAndAwemePage() throws Exception {
        FakeRestTemplate rest = new FakeRestTemplate();
        rest.enqueue(200, """
                {"status_code":0,"mix_info":{"mix_name":"Mix title","author":{"nickname":"Owner"}}}
                """);
        rest.enqueue(200, """
                {"status_code":0,"has_more":0,"max_cursor":0,"aweme_list":[
                {"aweme_id":"8001","desc":"Mix work","video":{"play_addr":{"url_list":["https://v3.douyinvod.com/mix.mp4"]}}}
                ]}
                """);
        var client = new DefaultDouyinClient(new DouyinUrlParser(), rest,
                (input, cookie) -> new DouyinUrlParser().parse(input).orElseThrow());

        var listing = client.listSeriesWorks("mix1", 1, 20, null);

        assertThat(listing.title()).isEqualTo("Mix title");
        assertThat(listing.ownerName()).isEqualTo("Owner");
        assertThat(listing.items()).singleElement()
                .satisfies(work -> {
                    assertThat(work.id()).isEqualTo("8001");
                    assertThat(work.collectionId()).isEqualTo("mix1");
                    assertThat(work.collectionTitle()).isEqualTo("Mix title");
                });
    }

    @Test
    @DisplayName("合集作品按真实游标与页大小请求并保留页内作品")
    void pagesMixWorksWithOpaqueCursor() throws Exception {
        FakeRestTemplate rest = new FakeRestTemplate();
        rest.enqueue(200, mixInfo());
        rest.enqueue(200, """
                {"status_code":0,"has_more":1,"max_cursor":"mix-next","total":9,"aweme_list":[
                  {"aweme_id":"8012","desc":"Mix page work",
                   "video":{"play_addr":{"url_list":["https://v3.douyinvod.com/8012.mp4"]}}}
                ]}
                """);

        var listing = client(rest).listSeriesWorksPage("mix1", "mix-current", 12, "sessionid=test");

        assertThat(listing.items()).extracting("id").containsExactly("8012");
        assertThat(listing.total()).isEqualTo(9);
        assertThat(listing.nextCursor()).isEqualTo("mix-next");
        assertThat(listing.hasMore()).isTrue();
        assertThat(rest.requests()).hasSize(2);
        assertThat(rest.requests().get(1).getRawQuery())
                .contains("mix_id=mix1", "cursor=mix-current", "count=12");
    }

    @Test
    @DisplayName("合集作品仍有下一页但游标未推进时明确失败")
    void rejectsStalledMixWorksCursorPage() {
        FakeRestTemplate rest = new FakeRestTemplate();
        rest.enqueue(200, mixInfo());
        rest.enqueue(200, """
                {"status_code":0,"has_more":1,"max_cursor":"mix-current","aweme_list":[
                  {"aweme_id":"8012","desc":"Mix page work",
                   "video":{"play_addr":{"url_list":["https://v3.douyinvod.com/8012.mp4"]}}}
                ]}
                """);

        assertThatThrownBy(() -> client(rest).listSeriesWorksPage("mix1", "mix-current", 12, null))
                .isInstanceOf(DouyinClientException.class)
                .extracting(error -> ((DouyinClientException) error).code())
                .isEqualTo(DouyinClientErrorCode.PAGINATION_STALLED);
    }

    @Test
    @DisplayName("合集逻辑分页以 20 条上游游标遍历并在耗尽前保持未知总数")
    void mixLogicalPagesTraverseUpstreamChunks() throws Exception {
        FakeRestTemplate firstPageRest = new FakeRestTemplate();
        firstPageRest.enqueue(200, mixInfo());
        firstPageRest.enqueue(200, mixPage(1, 20, true, 20));
        DefaultDouyinClient firstPageClient = client(firstPageRest);

        var firstPage = firstPageClient.listSeriesWorks("mix1", 1, 20, null);

        assertThat(firstPage.items()).hasSize(20);
        assertThat(firstPage.total()).isZero();
        assertThat(firstPage.lastPage()).isFalse();
        assertThat(firstPageRest.requests().get(1).getRawQuery()).contains("count=20");

        FakeRestTemplate lastPageRest = new FakeRestTemplate();
        lastPageRest.enqueue(200, mixInfo());
        lastPageRest.enqueue(200, mixPage(1, 20, true, 20));
        lastPageRest.enqueue(200, mixPage(21, 20, true, 40));
        lastPageRest.enqueue(200, mixPage(41, 5, false, 0));
        DefaultDouyinClient lastPageClient = client(lastPageRest);

        var lastPage = lastPageClient.listSeriesWorks("mix1", 3, 20, null);

        assertThat(lastPage.items()).extracting("id")
                .containsExactly("8041", "8042", "8043", "8044", "8045");
        assertThat(lastPage.total()).isEqualTo(45);
        assertThat(lastPage.lastPage()).isTrue();
        assertThat(lastPageRest.requests()).hasSize(4);
        assertThat(lastPageRest.requests().subList(1, 4))
                .allSatisfy(uri -> assertThat(uri.getRawQuery()).contains("count=20"));
    }

    @Test
    @DisplayName("合集分页拒绝重复游标并越过游标前进的空页")
    void mixPaginationRejectsStalledCursorAndContinuesPastAdvancingEmptyPage() throws Exception {
        FakeRestTemplate stalledRest = new FakeRestTemplate();
        stalledRest.enqueue(200, mixInfo());
        stalledRest.enqueue(200, mixPage(1, 1, true, 7));
        stalledRest.enqueue(200, mixPage(2, 1, true, 7));

        assertThatThrownBy(() -> client(stalledRest).listSeriesWorks("mix1", 2, 1, null))
                .isInstanceOf(DouyinClientException.class)
                .extracting(error -> ((DouyinClientException) error).code())
                .isEqualTo(DouyinClientErrorCode.PAGINATION_STALLED);
        assertThat(stalledRest.requests()).hasSize(3);

        FakeRestTemplate emptyRest = new FakeRestTemplate();
        emptyRest.enqueue(200, mixInfo());
        emptyRest.enqueue(200, mixPage(1, 0, true, 9));
        emptyRest.enqueue(200, mixPage(2, 1, false, 0));

        assertThat(client(emptyRest).listSeriesWorks("mix1", 1, 20, null).items())
                .extracting("id")
                .containsExactly("8002");
        assertThat(emptyRest.requests()).hasSize(3);
    }

    @Test
    @DisplayName("短链先经 resolver 展开后再解析最终 URL")
    void resolvesShortLinkBeforeParsing() throws Exception {
        var rest = new FakeRestTemplate();
        rest.enqueue(200, """
                {"aweme_detail":{"aweme_id":"7357","desc":"Short",
                "video":{"play_addr":{"url_list":["https://v3.douyinvod.com/short.mp4"]}}}}
                """);
        DouyinUrlParser parser = new DouyinUrlParser();
        var client = new DefaultDouyinClient(parser, rest,
                (input, cookie) -> parser.parse("https://www.douyin.com/video/7357").orElseThrow());

        var work = client.resolvePublicWork("https://v.douyin.com/AbCd/", null);

        assertThat(work.id()).isEqualTo("7357");
    }

    @Test
    @DisplayName("下载入口把短链规范化为 aweme_id 并携带预解析作品")
    void resolvesCanonicalDownloadForShortLink() throws Exception {
        var rest = new FakeRestTemplate();
        rest.enqueue(200, """
                {"aweme_detail":{"aweme_id":"7357","desc":"Short",
                "video":{"play_addr":{"url_list":["https://v3.douyinvod.com/short.mp4"]}}}}
                """);
        DouyinUrlParser parser = new DouyinUrlParser();
        var client = new DefaultDouyinClient(parser, rest,
                (input, cookie) -> parser.parse("https://www.douyin.com/video/7357").orElseThrow());

        var canonical = client.resolveDownload("https://v.douyin.com/AbCd/", null);

        assertThat(canonical.kind()).isEqualTo(DouyinCanonicalKind.SINGLE_WORK);
        assertThat(canonical.stableId()).isEqualTo("7357");
        assertThat(canonical.stableKey()).isEqualTo("work:7357");
        assertThat(canonical.canonicalUrl()).isEqualTo("https://www.douyin.com/video/7357");
        assertThat(canonical.preResolvedWork().id()).isEqualTo("7357");
    }

    @Test
    @DisplayName("下载入口对合集使用稳定合集 ID")
    void resolvesCanonicalDownloadForCollection() throws Exception {
        var rest = new FakeRestTemplate();
        DouyinUrlParser parser = new DouyinUrlParser();
        var client = new DefaultDouyinClient(parser, rest,
                (input, cookie) -> parser.parse("https://www.douyin.com/mix/12345").orElseThrow());

        var canonical = client.resolveDownload("https://v.douyin.com/MixShort/", null);

        assertThat(canonical.kind()).isEqualTo(DouyinCanonicalKind.COLLECTION);
        assertThat(canonical.stableId()).isEqualTo("12345");
        assertThat(canonical.stableKey()).isEqualTo("collection:12345");
        assertThat(canonical.preResolvedWork()).isNull();
        assertThat(rest.requests()).isEmpty();
    }

    @Test
    @DisplayName("用户作品使用 sec_uid 与不透明游标分页并保留下一游标")
    void listsUserWorksWithOpaqueCursor() throws Exception {
        FakeRestTemplate rest = new FakeRestTemplate();
        rest.enqueue(200, """
                {"status_code":0,"has_more":1,"max_cursor":"opaque-2","aweme_list":[
                  {"aweme_id":"9101","desc":"User work","author":{"sec_uid":"sec-demo","nickname":"作者"},
                   "video":{"play_addr":{"url_list":["https://v3.douyinvod.com/9101.mp4"]}}}
                ]}
                """);

        var listing = client(rest).listUserWorksPage("sec-demo", "opaque-1", 24, "sessionid=test");

        assertThat(listing.items()).extracting("id").containsExactly("9101");
        assertThat(listing.hasMore()).isTrue();
        assertThat(listing.nextCursor()).isEqualTo("opaque-2");
        assertThat(rest.requests()).singleElement().satisfies(uri -> {
            assertThat(uri.getPath()).isEqualTo("/aweme/v1/web/aweme/post/");
            assertThat(uri.getRawQuery()).contains(
                    "sec_user_id=sec-demo",
                    "max_cursor=opaque-1",
                    "locate_query=false",
                    "show_live_replay_strategy=1",
                    "need_time_list=1",
                    "time_list_query=0",
                    "whale_cut_token=",
                    "cut_version=1",
                    "publish_video_strategy_type=2");
        });
    }

    @Test
    @DisplayName("用户作品仍有下一页但游标未推进时明确失败")
    void rejectsStalledUserWorksCursor() {
        FakeRestTemplate rest = new FakeRestTemplate();
        rest.enqueue(200, """
                {"status_code":0,"has_more":1,"max_cursor":"opaque-1","aweme_list":[
                  {"aweme_id":"9101","desc":"User work",
                   "video":{"play_addr":{"url_list":["https://v3.douyinvod.com/9101.mp4"]}}}
                ]}
                """);

        assertThatThrownBy(() -> client(rest).listUserWorksPage("sec-demo", "opaque-1", 24, null))
                .isInstanceOf(DouyinClientException.class)
                .extracting(error -> ((DouyinClientException) error).code())
                .isEqualTo(DouyinClientErrorCode.PAGINATION_STALLED);
    }

    @Test
    @DisplayName("用户逻辑分页越过游标前进但没有可下载作品的中间页")
    void userLogicalPaginationContinuesPastAdvancingEmptyPage() throws Exception {
        FakeRestTemplate rest = new FakeRestTemplate();
        rest.enqueue(200, """
                {"status_code":0,"has_more":1,"max_cursor":"next-page","aweme_list":[]}
                """);
        rest.enqueue(200, """
                {"status_code":0,"has_more":0,"max_cursor":"done","aweme_list":[
                  {"aweme_id":"9102","desc":"User work",
                   "video":{"play_addr":{"url_list":["https://v3.douyinvod.com/9102.mp4"]}}}
                ]}
                """);

        var listing = client(rest).listUserWorks("sec-demo", 0, 1, null);

        assertThat(listing.items()).extracting("id").containsExactly("9102");
        assertThat(rest.requests()).hasSize(2);
    }

    @Test
    @DisplayName("用户深页逻辑预览固定使用上游批量避免逐件重放游标")
    void userLogicalPaginationUsesUpstreamBatchSize() throws Exception {
        FakeRestTemplate rest = new FakeRestTemplate();
        rest.enqueue(200, userPage(1, 20, true, "20"));
        rest.enqueue(200, userPage(21, 1, false, "done"));

        var listing = client(rest).listUserWorks("sec-demo", 20, 1, null);

        assertThat(listing.items()).extracting("id").containsExactly("9021");
        assertThat(rest.requests()).hasSize(2)
                .allSatisfy(uri -> assertThat(uri.getRawQuery()).contains("count=20"));
    }

    @Test
    @DisplayName("关键词搜索只发送真实关键词并解析 aweme_info 游标页")
    void searchesKeywordWithCursorPage() throws Exception {
        FakeRestTemplate rest = new FakeRestTemplate();
        rest.enqueue(200, """
                {"status_code":0,"has_more":1,"cursor":48,"data":[
                  {"aweme_info":{"aweme_id":"9201","desc":"Search work",
                   "video":{"play_addr":{"url_list":["https://v3.douyinvod.com/9201.mp4"]}}}}
                ]}
                """);

        var listing = client(rest).searchWorksPage("猫", "24", 24, "sessionid=test");

        assertThat(listing.items()).extracting("id").containsExactly("9201");
        assertThat(listing.nextCursor()).isEqualTo("48");
        assertThat(rest.requests()).singleElement().satisfies(uri -> {
            assertThat(uri.getPath()).isEqualTo("/aweme/v1/web/general/search/single/");
            assertThat(uri.getRawQuery()).contains(
                    "search_channel=aweme_video_web",
                    "keyword=%E7%8C%AB",
                    "sort_type=0",
                    "publish_time=0",
                    "offset=24")
                    .doesNotContain("a_bogus=", "X-Bogus=");
        });
    }

    @Test
    @DisplayName("关键词搜索已识别的空数组或 null 保持合法空结果")
    void keepsRecognizedEmptySearchPage() throws Exception {
        for (String body : List.of(
                "{\"status_code\":0,\"has_more\":0,\"cursor\":0,\"data\":[]}",
                "{\"status_code\":0,\"has_more\":0,\"cursor\":0,\"data\":null}")) {
            FakeRestTemplate rest = new FakeRestTemplate();
            rest.enqueue(200, body);

            var listing = client(rest).searchWorksPage("猫", "0", 24, "sessionid=test");

            assertThat(listing.items()).isEmpty();
            assertThat(listing.hasMore()).isFalse();
        }
    }

    @Test
    @DisplayName("关键词搜索响应缺少已知结果数组时明确报告结构异常")
    void rejectsUnknownSearchResponseStructure() {
        FakeRestTemplate rest = new FakeRestTemplate();
        rest.enqueue(200, """
                {"status_code":0,"has_more":0,"cursor":0,"unexpected":[]}
                """);

        assertCodeName(() -> client(rest).searchWorksPage("猫", "0", 24, "sessionid=test"),
                "RESPONSE_STRUCTURE_UNRECOGNIZED");
    }

    @Test
    @DisplayName("关键词搜索 verify_check 空结果明确报告验证拦截")
    void rejectsSearchNilVerifyCheckAsRiskResponse() {
        FakeRestTemplate rest = new FakeRestTemplate();
        rest.enqueue(200, """
                {"status_code":0,"has_more":0,"cursor":0,"data":[],
                 "search_nil_info":{"search_nil_type":"verify_check",
                 "search_nil_item":"verify_check","text_type":9}}
                """);

        assertCodeName(() -> client(rest).searchWorksPage("猫", "0", 24, "sessionid=test"),
                "LOGIN_OR_VERIFY_PAGE");
    }

    @Test
    @DisplayName("用户作品响应缺少已知识别数组时明确报告结构异常")
    void rejectsUnknownUserWorksResponseStructure() {
        FakeRestTemplate rest = new FakeRestTemplate();
        rest.enqueue(200, "{\"status_code\":0,\"unexpected\":[]}");

        assertCodeName(() -> client(rest).listUserWorksPage(
                        "sec-user-1", "0", 20, "sessionid=test"),
                "RESPONSE_STRUCTURE_UNRECOGNIZED");
    }

    @Test
    @DisplayName("账号喜欢作品响应缺少已知识别数组时明确报告结构异常")
    void rejectsUnknownLikedWorksResponseStructure() {
        FakeRestTemplate rest = new FakeRestTemplate();
        rest.enqueue(200, "{\"status_code\":0,\"unexpected\":[]}");

        assertCodeName(() -> client(rest).listAccountWorksPage(
                        favoriteAccount(), DouyinAccountSource.LIKED_WORKS,
                        "0", 20, "sessionid=test"),
                "RESPONSE_STRUCTURE_UNRECOGNIZED");
    }

    @Test
    @DisplayName("音乐作品响应缺少已知识别数组时明确报告结构异常")
    void rejectsUnknownMusicWorksResponseStructure() {
        FakeRestTemplate rest = new FakeRestTemplate();
        rest.enqueue(200, "{\"status_code\":0,\"unexpected\":[]}");

        assertCodeName(() -> client(rest).listMusicWorksPage(
                        "music-1", "0", 20, "sessionid=test"),
                "RESPONSE_STRUCTURE_UNRECOGNIZED");
    }

    @Test
    @DisplayName("收藏合集列表响应缺少已知识别数组时明确报告结构异常")
    void rejectsUnknownFavoriteCollectionsResponseStructure() {
        FakeRestTemplate rest = new FakeRestTemplate();
        rest.enqueue(200, "{\"status_code\":0,\"unexpected\":[]}");

        assertCodeName(() -> client(rest).listFavoriteCollections(
                        "0", 20, "sessionid=test"),
                "RESPONSE_STRUCTURE_UNRECOGNIZED");
    }

    @Test
    @DisplayName("合集详情缺少已知识别对象时明确报告结构异常")
    void rejectsUnknownMixInfoResponseStructure() {
        FakeRestTemplate rest = new FakeRestTemplate();
        rest.enqueue(200, "{\"status_code\":0,\"unexpected\":{}}");

        assertCodeName(() -> client(rest).listSeriesWorksPage(
                        "mix-1", "0", 20, "sessionid=test"),
                "RESPONSE_STRUCTURE_UNRECOGNIZED");
    }

    @Test
    @DisplayName("合集游标页响应缺少已知识别数组时明确报告结构异常")
    void rejectsUnknownSeriesPageResponseStructure() {
        FakeRestTemplate rest = new FakeRestTemplate();
        rest.enqueue(200, mixInfo());
        rest.enqueue(200, "{\"status_code\":0,\"unexpected\":[]}");

        assertCodeName(() -> client(rest).listSeriesWorksPage(
                        "mix-1", "0", 20, "sessionid=test"),
                "RESPONSE_STRUCTURE_UNRECOGNIZED");
    }

    @Test
    @DisplayName("合集页码接口响应缺少已知识别数组时明确报告结构异常")
    void rejectsUnknownSeriesLogicalPageResponseStructure() {
        FakeRestTemplate rest = new FakeRestTemplate();
        rest.enqueue(200, mixInfo());
        rest.enqueue(200, "{\"status_code\":0,\"unexpected\":[]}");

        assertCodeName(() -> client(rest).listSeriesWorks(
                        "mix-1", 1, 20, "sessionid=test"),
                "RESPONSE_STRUCTURE_UNRECOGNIZED");
    }

    @Test
    @DisplayName("非搜索来源已识别的空数组仍保持合法空结果")
    void keepsRecognizedEmptyNonSearchListings() throws Exception {
        assertThat(client("{\"status_code\":0,\"has_more\":0,\"aweme_list\":[]}")
                .listUserWorksPage("sec-user-1", "0", 20, "sessionid=test").items())
                .isEmpty();
        assertThat(client("{\"status_code\":0,\"has_more\":0,\"aweme_list\":[]}")
                .listMusicWorksPage("music-1", "0", 20, "sessionid=test").items())
                .isEmpty();
        assertThat(client("{\"status_code\":0,\"has_more\":0,\"mix_list\":[]}")
                .listFavoriteCollections("0", 20, "sessionid=test").items())
                .isEmpty();
    }

    @Test
    @DisplayName("非搜索作品列表候选全部不可下载时明确报告过滤异常")
    void rejectsNonSearchListingWhenAllCandidatesAreFiltered() {
        FakeRestTemplate rest = new FakeRestTemplate();
        rest.enqueue(200, """
                {"status_code":0,"has_more":0,"aweme_list":[
                  {"aweme_id":"filtered-user-work","desc":"Missing media"}
                ]}
                """);

        assertCodeName(() -> client(rest).listUserWorksPage(
                        "sec-user-1", "0", 20, "sessionid=test"),
                "RESPONSE_CANDIDATES_FILTERED");
    }

    @Test
    @DisplayName("收藏合集候选全部缺少稳定 ID 时明确报告过滤异常")
    void rejectsFavoriteCollectionCandidatesWithoutStableId() {
        FakeRestTemplate rest = new FakeRestTemplate();
        rest.enqueue(200, """
                {"status_code":0,"has_more":0,"mix_list":[
                  {"mix_name":"Missing id"}
                ]}
                """);

        assertCodeName(() -> client(rest).listFavoriteCollections(
                        "0", 20, "sessionid=test"),
                "RESPONSE_CANDIDATES_FILTERED");
    }

    @Test
    @DisplayName("合集页码候选全部不可下载时明确报告过滤异常")
    void rejectsSeriesLogicalPageWhenAllCandidatesAreFiltered() {
        FakeRestTemplate rest = new FakeRestTemplate();
        rest.enqueue(200, mixInfo());
        rest.enqueue(200, """
                {"status_code":0,"has_more":0,"aweme_list":[
                  {"aweme_id":"filtered-series-work","desc":"Missing media"}
                ]}
                """);

        assertCodeName(() -> client(rest).listSeriesWorks(
                        "mix-1", 1, 20, "sessionid=test"),
                "RESPONSE_CANDIDATES_FILTERED");
    }

    @Test
    @DisplayName("关键词搜索上游返回空响应体时明确报告签名受阻")
    void rejectsEmptySearchResponseBody() {
        FakeRestTemplate rest = new FakeRestTemplate();
        rest.enqueue(200, "");
        rest.enqueue(200, "");
        rest.enqueue(200, "");

        assertCodeName(() -> client(rest).searchWorksPage("猫", "0", 24, "sessionid=test"),
                "SIGNATURE_REQUIRED");
        assertThat(rest.requests()).hasSize(3);
    }

    @Test
    @DisplayName("空响应会按 1 秒和 2 秒间隔重建无签名请求并在第三次成功")
    void retriesEmptyApiResponsesWithFreshUnsignedRequests() throws Exception {
        FakeRestTemplate rest = new FakeRestTemplate();
        rest.enqueue(200, "");
        rest.enqueue(200, "");
        rest.enqueue(200, "{\"status_code\":0,\"has_more\":0,\"cursor\":0,\"data\":[]}");
        DouyinUrlParser parser = new DouyinUrlParser();
        java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
        DouyinSignedUriBuilder signer = new DouyinSignedUriBuilder() {
            @Override
            public SignedRequest unsignedRequest(String path, java.util.Map<String, ?> params, String cookie) {
                int attempt = requests.incrementAndGet();
                return new SignedRequest(URI.create("https://www.douyin.com" + path + "?attempt=" + attempt),
                        cookie);
            }
        };
        List<Long> delays = new ArrayList<>();
        var client = new DefaultDouyinClient(parser, rest,
                (input, cookie) -> parser.parse(input).orElseThrow(), signer, delays::add);

        var listing = client.searchWorksPage("猫", "0", 24, "sessionid=test");

        assertThat(listing.items()).isEmpty();
        assertThat(requests).hasValue(3);
        assertThat(rest.requests()).extracting(URI::getRawQuery)
                .containsExactly("attempt=1", "attempt=2", "attempt=3");
        assertThat(delays).containsExactly(1_000L, 2_000L);
    }

    @Test
    @DisplayName("网络异常会重试并可在后续请求恢复")
    void retriesNetworkFailures() throws Exception {
        FakeRestTemplate rest = new FakeRestTemplate();
        rest.enqueueNetworkFailure();
        rest.enqueueNetworkFailure();
        rest.enqueue(200, "{\"status_code\":0,\"has_more\":0,\"cursor\":0,\"data\":[]}");

        var listing = client(rest).searchWorksPage("猫", "0", 24, "sessionid=test");

        assertThat(listing.items()).isEmpty();
        assertThat(rest.requests()).hasSize(3);
    }

    @Test
    @DisplayName("关键词搜索候选全部无法形成可下载作品时明确报告过滤异常")
    void rejectsSearchPageWhenAllCandidatesAreFiltered() {
        FakeRestTemplate rest = new FakeRestTemplate();
        rest.enqueue(200, """
                {"status_code":0,"has_more":0,"cursor":0,"data":[
                  {"aweme_info":{"aweme_id":"9202","desc":"Missing media"}}
                ]}
                """);

        assertCodeName(() -> client(rest).searchWorksPage("猫", "0", 24, "sessionid=test"),
                "RESPONSE_CANDIDATES_FILTERED");
    }

    @Test
    @DisplayName("关键词搜索按 HTTP 状态保留可诊断错误类别")
    void classifiesSearchHttpStatusFamilies() {
        assertSearchHttpCode(401, "COOKIE_EXPIRED");
        assertSearchHttpCode(404, "UPSTREAM_NOT_FOUND");
        assertSearchHttpCode(400, "UPSTREAM_CLIENT_ERROR");
        assertSearchHttpCode(503, "UPSTREAM_SERVER_ERROR");
    }

    @Test
    @DisplayName("关键词页码偏移使用长整型计算避免极值溢出")
    void calculatesSearchOffsetWithoutIntegerOverflow() throws Exception {
        FakeRestTemplate rest = new FakeRestTemplate();
        rest.enqueue(200, "{\"status_code\":0,\"has_more\":0,\"data\":[]}");

        client(rest).searchPublic("cat", Integer.MAX_VALUE, 100, "sessionid=test");

        assertThat(rest.requests()).singleElement().satisfies(uri ->
                assertThat(uri.getRawQuery()).contains("offset=214748364600"));
    }

    @Test
    @DisplayName("音乐来源分页下载关联作品而不冒充音乐音频")
    void listsMusicRelatedWorks() throws Exception {
        FakeRestTemplate rest = new FakeRestTemplate();
        rest.enqueue(200, """
                {"status_code":0,"has_more":0,"cursor":0,"aweme_list":[
                  {"aweme_id":"9301","desc":"Music work",
                   "video":{"play_addr":{"url_list":["https://v3.douyinvod.com/9301.mp4"]}}}
                ]}
                """);

        var listing = client(rest).listMusicWorksPage("music-1", "0", 20, "sessionid=test");

        assertThat(listing.items()).extracting("id").containsExactly("9301");
        assertThat(listing.items().get(0).media()).allSatisfy(media ->
                assertThat(media.type().name()).doesNotContain("AUDIO"));
        assertThat(rest.requests().get(0).getPath()).isEqualTo("/aweme/v1/web/music/aweme/");
    }

    @Test
    @DisplayName("音乐来源仍有下一页但游标未推进时明确失败")
    void rejectsStalledMusicWorksCursor() {
        FakeRestTemplate rest = new FakeRestTemplate();
        rest.enqueue(200, """
                {"status_code":0,"has_more":1,"cursor":"music-current","aweme_list":[
                  {"aweme_id":"9302","desc":"Music work",
                   "video":{"play_addr":{"url_list":["https://v3.douyinvod.com/9302.mp4"]}}}
                ]}
                """);

        assertThatThrownBy(() -> client(rest).listMusicWorksPage("music-1", "music-current", 20, null))
                .isInstanceOf(DouyinClientException.class)
                .extracting(error -> ((DouyinClientException) error).code())
                .isEqualTo(DouyinClientErrorCode.PAGINATION_STALLED);
    }

    @Test
    @DisplayName("账号探活产生非敏感身份并驱动喜欢作品与已收藏合集端点")
    void resolvesAccountAndListsAuthenticatedSources() throws Exception {
        FakeRestTemplate rest = new FakeRestTemplate();
        String account = """
                {"status_code":0,"user":{"uid":"uid-1","sec_uid":"sec-1", "nickname":"我", "unique_id":"mine"}}
                """;
        rest.enqueue(200, account);
        rest.enqueue(200, account);
        rest.enqueue(200, """
                {"status_code":0,"has_more":0,"max_cursor":0,"aweme_list":[
                  {"aweme_id":"9401","desc":"Liked",
                   "video":{"play_addr":{"url_list":["https://v3.douyinvod.com/9401.mp4"]}}}
                ]}
                """);
        rest.enqueue(200, """
                {"status_code":0,"has_more":0,"cursor":0,"mix_list":[
                  {"mix_id":"mix-1","mix_name":"收藏合集","aweme_count":3,
                   "author":{"uid":"uid-2","nickname":"作者"}}
                ]}
                """);
        DefaultDouyinClient client = client(rest);

        assertThat(client.resolveAccount("sessionid=test").accountKey()).isEqualTo("uid-1");
        assertThat(client.listAccountWorksPage(DouyinAccountSource.LIKED_WORKS, "0", 20,
                "sessionid=test").items()).extracting("id").containsExactly("9401");
        assertThat(client.listFavoriteCollections("0", 20, "sessionid=test").items())
                .singleElement().satisfies(item -> {
                    assertThat(item.id()).isEqualTo("mix-1");
                    assertThat(item.workCount()).isEqualTo(3);
                });
        assertThat(rest.requests()).extracting(URI::getPath).contains(
                "/aweme/v1/web/user/profile/self/",
                "/aweme/v1/web/aweme/favorite/",
                "/aweme/v1/web/mix/listcollection/");
    }

    @Test
    @DisplayName("收藏作品通过签名 POST 端点按上游游标分页")
    void pagesFavoriteWorksThroughSignedPostEndpoint() throws Exception {
        FakeRestTemplate rest = new FakeRestTemplate();
        rest.enqueue(200, """
                {"status_code":0,"has_more":1,"cursor":"favorite-next","total":7,"aweme_list":[
                  {"aweme_id":"9501","desc":"Favorite A",
                   "video":{"play_addr":{"url_list":["https://v3.douyinvod.com/9501.mp4"]}}},
                  {"aweme_id":"9502","desc":"Favorite B",
                   "video":{"play_addr":{"url_list":["https://v3.douyinvod.com/9502.mp4"]}}}
                ]}
                """);

        var listing = client(rest).listAccountWorksPage(
                favoriteAccount(), DouyinAccountSource.FAVORITE_WORKS,
                "favorite-current", 12, "sessionid=test");

        assertThat(listing.items()).extracting("id").containsExactly("9501", "9502");
        assertThat(listing.total()).isEqualTo(7);
        assertThat(listing.nextCursor()).isEqualTo("favorite-next");
        assertThat(listing.hasMore()).isTrue();
        assertThat(listing.ownerId()).isEqualTo("uid-1");
        assertThat(listing.ownerName()).isEqualTo("Me");
        assertThat(rest.requests()).singleElement().satisfies(uri -> {
            assertThat(uri.getPath()).isEqualTo("/aweme/v1/web/aweme/listcollection/");
            assertThat(uri.getRawQuery())
                    .contains("cursor=favorite-current", "count=12", "a_bogus=");
        });
        assertThat(rest.methods()).containsExactly(HttpMethod.POST);
        assertThat(rest.cookies()).singleElement().satisfies(cookie ->
                assertThat(cookie).contains("sessionid=test", "msToken="));
    }

    @Test
    @DisplayName("收藏作品已识别的空数组或 null 列表保持合法空结果")
    void keepsRecognizedEmptyFavoriteWorks() throws Exception {
        for (String body : List.of(
                "{\"status_code\":0,\"has_more\":0,\"cursor\":0,\"aweme_list\":[]}",
                "{\"status_code\":0,\"has_more\":0,\"cursor\":0,\"aweme_list\":null}")) {
            FakeRestTemplate rest = new FakeRestTemplate();
            rest.enqueue(200, body);

            var listing = client(rest).listAccountWorksPage(
                    favoriteAccount(), DouyinAccountSource.FAVORITE_WORKS, "0", 20, null);

            assertThat(listing.items()).isEmpty();
            assertThat(listing.hasMore()).isFalse();
            assertThat(rest.methods()).containsExactly(HttpMethod.POST);
        }
    }

    @Test
    @DisplayName("收藏作品响应缺少已知作品数组时明确失败")
    void rejectsFavoriteWorksResponseWithoutKnownArray() {
        FakeRestTemplate rest = new FakeRestTemplate();
        rest.enqueue(200, "{\"status_code\":0,\"has_more\":0,\"cursor\":0}");

        assertCodeName(() -> client(rest).listAccountWorksPage(
                        favoriteAccount(), DouyinAccountSource.FAVORITE_WORKS, "0", 20, null),
                "RESPONSE_STRUCTURE_UNRECOGNIZED");
    }

    @Test
    @DisplayName("收藏作品候选缺少可下载媒体时明确失败")
    void rejectsFavoriteWorksCandidatesWithoutDownloadableMedia() {
        FakeRestTemplate rest = new FakeRestTemplate();
        rest.enqueue(200, """
                {"status_code":0,"has_more":0,"cursor":0,
                 "aweme_list":[{"aweme_id":"9503","desc":"Missing media"}]}
                """);

        assertCodeName(() -> client(rest).listAccountWorksPage(
                        favoriteAccount(), DouyinAccountSource.FAVORITE_WORKS, "0", 20, null),
                "RESPONSE_CANDIDATES_FILTERED");
    }

    @Test
    @DisplayName("收藏作品仍有下一页但游标未推进时明确失败")
    void rejectsStalledFavoriteWorksCursor() {
        FakeRestTemplate rest = new FakeRestTemplate();
        rest.enqueue(200, """
                {"status_code":0,"has_more":1,"cursor":"favorite-current","aweme_list":[
                  {"aweme_id":"9504","desc":"Favorite",
                   "video":{"play_addr":{"url_list":["https://v3.douyinvod.com/9504.mp4"]}}}
                ]}
                """);

        assertCodeName(() -> client(rest).listAccountWorksPage(
                        favoriteAccount(), DouyinAccountSource.FAVORITE_WORKS,
                        "favorite-current", 20, null),
                "PAGINATION_STALLED");
    }

    @Test
    @DisplayName("收藏作品按 HTTP 状态保留可诊断错误类别")
    void classifiesFavoriteWorksHttpStatusFamilies() {
        assertFavoriteHttpCode(401, "COOKIE_EXPIRED");
        assertFavoriteHttpCode(404, "UPSTREAM_NOT_FOUND");
        assertFavoriteHttpCode(429, "RATE_LIMITED");
        assertFavoriteHttpCode(503, "UPSTREAM_SERVER_ERROR");
    }

    @Test
    @DisplayName("账号作品仍有下一页但游标未推进时明确失败")
    void rejectsStalledAccountWorksCursor() {
        FakeRestTemplate rest = new FakeRestTemplate();
        rest.enqueue(200, """
                {"status_code":0,"user":{"uid":"uid-1","sec_uid":"sec-1","nickname":"我"}}
                """);
        rest.enqueue(200, """
                {"status_code":0,"has_more":1,"max_cursor":"account-current","aweme_list":[
                  {"aweme_id":"9402","desc":"Liked",
                   "video":{"play_addr":{"url_list":["https://v3.douyinvod.com/9402.mp4"]}}}
                ]}
                """);

        assertThatThrownBy(() -> client(rest).listAccountWorksPage(
                DouyinAccountSource.LIKED_WORKS, "account-current", 20, "sessionid=test"))
                .isInstanceOf(DouyinClientException.class)
                .extracting(error -> ((DouyinClientException) error).code())
                .isEqualTo(DouyinClientErrorCode.PAGINATION_STALLED);
    }

    @Test
    @DisplayName("收藏合集按真实游标与页大小请求并保留下一游标")
    void pagesFavoriteCollectionsWithOpaqueCursor() throws Exception {
        FakeRestTemplate rest = new FakeRestTemplate();
        rest.enqueue(200, """
                {"status_code":0,"has_more":1,"cursor":"collection-next","total":8,"mix_list":[
                  {"mix_id":"mix-2","mix_name":"收藏合集二","aweme_count":5}
                ]}
                """);

        var listing = client(rest).listFavoriteCollections("collection-current", 12, "sessionid=test");

        assertThat(listing.items()).extracting("id").containsExactly("mix-2");
        assertThat(listing.total()).isEqualTo(8);
        assertThat(listing.nextCursor()).isEqualTo("collection-next");
        assertThat(listing.hasMore()).isTrue();
        assertThat(rest.requests()).singleElement().satisfies(uri -> {
            assertThat(uri.getPath()).isEqualTo("/aweme/v1/web/mix/listcollection/");
            assertThat(uri.getRawQuery()).contains("cursor=collection-current", "count=12");
        });
        assertThat(rest.methods()).containsExactly(HttpMethod.GET);
    }

    @Test
    @DisplayName("收藏合集作品数在整型边界内稳定截断")
    void clampsFavoriteCollectionWorkCounts() throws Exception {
        FakeRestTemplate rest = new FakeRestTemplate();
        rest.enqueue(200, """
                {"status_code":0,"has_more":0,"cursor":"done","mix_list":[
                  {"mix_id":"negative","aweme_count":-1},
                  {"mix_id":"huge","aweme_count":9223372036854775807}
                ]}
                """);

        var items = client(rest).listFavoriteCollections("0", 12, "sessionid=test").items();

        assertThat(items).extracting("id", "workCount")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("negative", 0),
                        org.assertj.core.groups.Tuple.tuple("huge", Integer.MAX_VALUE));
    }

    @Test
    @DisplayName("收藏合集仍有下一页但游标缺失时明确失败")
    void rejectsMissingFavoriteCollectionCursor() {
        FakeRestTemplate rest = new FakeRestTemplate();
        rest.enqueue(200, """
                {"status_code":0,"has_more":1,"mix_list":[
                  {"mix_id":"mix-2","mix_name":"收藏合集二","aweme_count":5}
                ]}
                """);

        assertThatThrownBy(() -> client(rest).listFavoriteCollections("collection-current", 12, null))
                .isInstanceOf(DouyinClientException.class)
                .extracting(error -> ((DouyinClientException) error).code())
                .isEqualTo(DouyinClientErrorCode.PAGINATION_STALLED);
    }

    private static DefaultDouyinClient client(String... bodies) {
        FakeRestTemplate rest = new FakeRestTemplate();
        for (String body : bodies) {
            rest.enqueue(200, body);
        }
        DouyinUrlParser parser = new DouyinUrlParser();
        return new DefaultDouyinClient(parser, rest,
                (input, cookie) -> parser.parse(input).orElseThrow(),
                new DouyinSignedUriBuilder(), ignored -> {
                });
    }

    private static DefaultDouyinClient client(FakeRestTemplate rest) {
        DouyinUrlParser parser = new DouyinUrlParser();
        return new DefaultDouyinClient(parser, rest,
                (input, cookie) -> parser.parse(input).orElseThrow(),
                new DouyinSignedUriBuilder(), ignored -> {
                });
    }

    private static String mixInfo() {
        return """
                {"status_code":0,"mix_info":{"mix_name":"Mix title","author":{"nickname":"Owner"}}}
                """;
    }

    private static String mixPage(int first, int count, boolean hasMore, long nextCursor) {
        StringBuilder items = new StringBuilder();
        for (int index = 0; index < count; index++) {
            if (!items.isEmpty()) {
                items.append(',');
            }
            int id = 8000 + first + index;
            items.append("{\"aweme_id\":\"").append(id)
                    .append("\",\"desc\":\"Work ").append(id)
                    .append("\",\"video\":{\"play_addr\":{\"url_list\":[\"https://v3.douyinvod.com/")
                    .append(id).append(".mp4\"]}}}");
        }
        return "{\"status_code\":0,\"has_more\":" + (hasMore ? 1 : 0)
                + ",\"max_cursor\":" + nextCursor + ",\"aweme_list\":[" + items + "]}";
    }

    private static String userPage(int first, int count, boolean hasMore, String nextCursor) {
        StringBuilder items = new StringBuilder();
        for (int index = 0; index < count; index++) {
            if (!items.isEmpty()) {
                items.append(',');
            }
            int id = 9000 + first + index;
            items.append("{\"aweme_id\":\"").append(id)
                    .append("\",\"desc\":\"Work ").append(id)
                    .append("\",\"video\":{\"play_addr\":{\"url_list\":[\"https://v3.douyinvod.com/")
                    .append(id).append(".mp4\"]}}}");
        }
        return "{\"status_code\":0,\"has_more\":" + (hasMore ? 1 : 0)
                + ",\"max_cursor\":\"" + nextCursor + "\",\"aweme_list\":[" + items + "]}";
    }

    private static String resolveTitle(String awemeJson) throws Exception {
        return client("{\"aweme_detail\":" + awemeJson + "}")
                .resolvePublicWork("https://www.douyin.com/video/title-test", null)
                .title();
    }

    private static String page(String json) {
        String encoded = URLEncoder.encode(json.replace("\n", ""), StandardCharsets.UTF_8);
        return "<html><script id=\"RENDER_DATA\" type=\"application/json\">" + encoded + "</script></html>";
    }

    private static void assertCode(ThrowingRunnable action, DouyinClientErrorCode code) {
        assertThatThrownBy(action::run)
                .isInstanceOf(DouyinClientException.class)
                .extracting(error -> ((DouyinClientException) error).code())
                .isEqualTo(code);
    }

    private static DouyinAccount favoriteAccount() {
        return new DouyinAccount("uid-1", "sec-1", "Me", "mine");
    }

    private static void assertCodeName(ThrowingRunnable action, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOf(DouyinClientException.class)
                .extracting(error -> ((DouyinClientException) error).code().name())
                .isEqualTo(code);
    }

    private static void assertSearchHttpCode(int status, String code) {
        FakeRestTemplate rest = new FakeRestTemplate();
        int attempts = status == 429 || status >= 500 ? 3 : 1;
        for (int attempt = 0; attempt < attempts; attempt++) {
            rest.enqueue(status, "{}");
        }
        assertCodeName(() -> client(rest).searchWorksPage("猫", "0", 24, "sessionid=test"), code);
        assertThat(rest.requests()).hasSize(attempts);
    }

    private static void assertFavoriteHttpCode(int status, String code) {
        FakeRestTemplate rest = new FakeRestTemplate();
        int attempts = status == 429 || status >= 500 ? 3 : 1;
        for (int attempt = 0; attempt < attempts; attempt++) {
            rest.enqueue(status, "{}");
        }
        assertCodeName(() -> client(rest).listAccountWorksPage(
                favoriteAccount(), DouyinAccountSource.FAVORITE_WORKS, "0", 20, null), code);
        assertThat(rest.requests()).hasSize(attempts);
        assertThat(rest.methods()).containsOnly(HttpMethod.POST).hasSize(attempts);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static final class FakeRestTemplate extends RestTemplate {
        private final Queue<Object> responses = new ArrayDeque<>();
        private final List<URI> requests = new ArrayList<>();
        private final List<String> cookies = new ArrayList<>();
        private final List<HttpMethod> methods = new ArrayList<>();

        void enqueue(int status, String body) {
            responses.add(new ResponseEntity<>(
                    body.getBytes(StandardCharsets.UTF_8),
                    new HttpHeaders(),
                    HttpStatusCode.valueOf(status)));
        }

        void enqueueNetworkFailure() {
            responses.add(new ResourceAccessException("synthetic network failure"));
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> ResponseEntity<T> exchange(URI url, HttpMethod method, HttpEntity<?> requestEntity,
                                              Class<T> responseType) {
            requests.add(url);
            methods.add(method);
            cookies.add(requestEntity == null
                    ? null
                    : requestEntity.getHeaders().getFirst(HttpHeaders.COOKIE));
            Object next = responses.isEmpty()
                    ? new ResponseEntity<>("{}".getBytes(StandardCharsets.UTF_8), HttpStatusCode.valueOf(200))
                    : responses.remove();
            if (next instanceof RuntimeException failure) {
                throw failure;
            }
            ResponseEntity<byte[]> response = (ResponseEntity<byte[]>) next;
            if (response.getStatusCode().isError()) {
                throw HttpClientErrorException.create(
                        response.getStatusCode(),
                        "mock",
                        response.getHeaders(),
                        response.getBody(),
                        StandardCharsets.UTF_8);
            }
            return new ResponseEntity<>((T) response.getBody(), response.getHeaders(), response.getStatusCode());
        }

        List<URI> requests() {
            return requests;
        }

        List<String> cookies() {
            return cookies;
        }

        List<HttpMethod> methods() {
            return methods;
        }
    }

    private static String queryValue(URI uri, String name) {
        for (String part : uri.getRawQuery().split("&")) {
            int equals = part.indexOf('=');
            if (equals > 0 && name.equals(part.substring(0, equals))) {
                return URLDecoder.decode(part.substring(equals + 1), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("Required query parameter is missing");
    }
}
