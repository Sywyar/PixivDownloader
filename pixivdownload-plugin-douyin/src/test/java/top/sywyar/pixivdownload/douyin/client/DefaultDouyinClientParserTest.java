package top.sywyar.pixivdownload.douyin.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.douyin.model.DouyinCanonicalKind;
import top.sywyar.pixivdownload.douyin.model.DouyinMediaType;
import top.sywyar.pixivdownload.douyin.model.DouyinParsedKind;
import top.sywyar.pixivdownload.douyin.model.DouyinWorkKind;
import top.sywyar.pixivdownload.douyin.parse.DouyinUrlParser;

import java.net.URI;
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
    @DisplayName("实况照片按原始页索引配对并保留稀疏静态图位置")
    void pairsLivePhotoByOriginalNodeIndex() throws Exception {
        DefaultDouyinClient client = client("""
                {"aweme_detail":{"aweme_id":"7353","desc":"Live",
                "image_post_info":{"images":[{"display_image":{"url_list":["https://p3.douyinpic.com/a.jpg"]},
                "video":{}},
                {"display_image":{"url_list":["https://p3.douyinpic.com/b.jpg"]},
                "video":{"play_addr":{"url_list":["https://v3.douyinvod.com/live-b.mp4","https://v6.douyinvod.com/live-b.mp4"]}}},
                {"video":{"play_addr":{"url_list":["https://v3.douyinvod.com/live-c.mp4"]}}},
                {"display_image":{"url_list":["https://p3.douyinpic.com/d.jpg"]}}]}}}
                """);

        var work = client.resolvePublicWork("https://www.douyin.com/gallery/7353", null);

        assertThat(work.kind()).isEqualTo(DouyinWorkKind.LIVE_PHOTO);
        assertThat(work.media()).extracting("id")
                .containsExactly("7353-p1", "7353-p2", "7353-live-p2", "7353-live-p3", "7353-p4");
        assertThat(work.media()).extracting("fileNameStem")
                .containsExactly("7353-p01", "7353-p02", "7353-live-p02", "7353-live-p03", "7353-p04");
        assertThat(work.media()).extracting("type")
                .containsExactly(
                        DouyinMediaType.IMAGE, DouyinMediaType.IMAGE,
                        DouyinMediaType.LIVE_PHOTO_VIDEO, DouyinMediaType.LIVE_PHOTO_VIDEO,
                        DouyinMediaType.IMAGE);
        assertThat(work.media().get(2).url())
                .isEqualTo(URI.create("https://v3.douyinvod.com/live-b.mp4"));
        assertThat(work.media().get(2).fallbackUrls())
                .containsExactly(URI.create("https://v6.douyinvod.com/live-b.mp4"));
        assertThat(work.media().get(3).url())
                .isEqualTo(URI.create("https://v3.douyinvod.com/live-c.mp4"));
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
    @DisplayName("优先使用带签名的作品详情 API")
    void usesSignedDetailApiBeforePageFallback() throws Exception {
        FakeRestTemplate rest = new FakeRestTemplate();
        rest.enqueue(200, """
                {"aweme_detail":{"aweme_id":"7358","desc":"Signed",
                "video":{"play_addr":{"url_list":["https://v3.douyinvod.com/signed.mp4"]}}}}
                """);
        DouyinUrlParser parser = new DouyinUrlParser();
        var client = new DefaultDouyinClient(parser, rest,
                (input, cookie) -> parser.parse(input).orElseThrow());

        var work = client.resolvePublicWork("https://www.douyin.com/video/7358", "msToken=fromCookie; ttwid=tt");

        assertThat(work.id()).isEqualTo("7358");
        assertThat(rest.requests()).singleElement()
                .satisfies(uri -> {
                    assertThat(uri.getPath()).isEqualTo("/aweme/v1/web/aweme/detail/");
                    assertThat(uri.getRawQuery()).contains("aweme_id=7358", "msToken=fromCookie", "a_bogus=");
                });
    }

    @Test
    @DisplayName("a_bogus 候选失败时使用 X-Bogus 兜底解析作品")
    void fallsBackToXbogusWhenABogusCandidateFails() throws Exception {
        FakeRestTemplate rest = new FakeRestTemplate();
        rest.enqueue(200, "<html>signature blocked</html>");
        rest.enqueue(200, """
                {"aweme_detail":{"aweme_id":"7359","desc":"XBogus",
                "video":{"play_addr":{"url_list":["https://v3.douyinvod.com/xbogus.mp4"]}}}}
                """);
        DouyinUrlParser parser = new DouyinUrlParser();
        var client = new DefaultDouyinClient(parser, rest,
                (input, cookie) -> parser.parse(input).orElseThrow());

        var work = client.resolvePublicWork("https://www.douyin.com/video/7359",
                "msToken=fromCookie; ttwid=tt; odin_tt=odin; passport_csrf_token=csrf");

        assertThat(work.id()).isEqualTo("7359");
        assertThat(rest.requests()).hasSize(2);
        assertThat(rest.requests().get(0).getRawQuery()).contains("a_bogus=").doesNotContain("X-Bogus=");
        assertThat(rest.requests().get(1).getRawQuery()).contains("X-Bogus=").doesNotContain("a_bogus=");
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
            assertThat(uri.getRawQuery()).contains("sec_user_id=sec-demo", "max_cursor=opaque-1");
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
            assertThat(uri.getRawQuery()).contains("keyword=%E7%8C%AB", "offset=24");
        });
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

    private static DefaultDouyinClient client(String... bodies) {
        FakeRestTemplate rest = new FakeRestTemplate();
        for (String body : bodies) {
            rest.enqueue(200, body);
        }
        DouyinUrlParser parser = new DouyinUrlParser();
        return new DefaultDouyinClient(parser, rest,
                (input, cookie) -> parser.parse(input).orElseThrow());
    }

    private static DefaultDouyinClient client(FakeRestTemplate rest) {
        DouyinUrlParser parser = new DouyinUrlParser();
        return new DefaultDouyinClient(parser, rest,
                (input, cookie) -> parser.parse(input).orElseThrow());
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

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static final class FakeRestTemplate extends RestTemplate {
        private final Queue<ResponseEntity<byte[]>> responses = new ArrayDeque<>();
        private final List<URI> requests = new ArrayList<>();

        void enqueue(int status, String body) {
            responses.add(new ResponseEntity<>(
                    body.getBytes(StandardCharsets.UTF_8),
                    new HttpHeaders(),
                    HttpStatusCode.valueOf(status)));
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> ResponseEntity<T> exchange(URI url, HttpMethod method, HttpEntity<?> requestEntity,
                                              Class<T> responseType) {
            requests.add(url);
            ResponseEntity<byte[]> response = responses.isEmpty()
                    ? new ResponseEntity<>("{}".getBytes(StandardCharsets.UTF_8), HttpStatusCode.valueOf(200))
                    : responses.remove();
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
    }
}
