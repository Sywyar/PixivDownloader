package top.sywyar.pixivdownload.douyin.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.douyin.client.signature.DouyinSignedUriBuilder;
import top.sywyar.pixivdownload.douyin.model.input.DouyinCanonicalKind;
import top.sywyar.pixivdownload.douyin.model.account.DouyinAccount;
import top.sywyar.pixivdownload.douyin.model.account.DouyinAccountSource;
import top.sywyar.pixivdownload.douyin.model.work.DouyinMediaType;
import top.sywyar.pixivdownload.douyin.model.input.DouyinParsedKind;
import top.sywyar.pixivdownload.douyin.model.work.DouyinWorkKind;
import top.sywyar.pixivdownload.douyin.parse.DouyinUrlParser;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpClient;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpRequest;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpStreamResponse;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpTransportException;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import static top.sywyar.pixivdownload.douyin.client.DefaultDouyinClientParserTestSupport.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DefaultDouyinClient 作品解析与规范化入口")
class DefaultDouyinClientResolutionTest {

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
    @DisplayName("生产调用在连续请求中透传 Cookie 中的 msToken 并同步发送 Cookie")
    void forwardsRealMsTokenInQueryAndCookieHeaders() throws Exception {
        FakeRestTemplate rest = new FakeRestTemplate();
        rest.enqueue(200, "{\"aweme_list\":[],\"has_more\":0,\"max_cursor\":\"0\"}");
        rest.enqueue(200, "{\"aweme_list\":[],\"has_more\":0,\"max_cursor\":\"0\"}");
        DouyinUrlParser parser = new DouyinUrlParser();
        var client = new DefaultDouyinClient(parser, rest,
                (input, cookie) -> parser.parse(input).orElseThrow());

        client.listUserWorksPage("sec-user", "0", 1, "ttwid=tt; msToken=real-token");
        client.listUserWorksPage("sec-user", "0", 1, "ttwid=tt; msToken=real-token");

        String firstToken = queryValue(rest.requests().get(0), "msToken");
        String secondToken = queryValue(rest.requests().get(1), "msToken");
        assertThat(firstToken).isEqualTo("real-token");
        assertThat(secondToken).isEqualTo("real-token");
        boolean cookiesMatch = rest.cookies().size() == 2
                && rest.cookies().get(0) != null
                && rest.cookies().get(1) != null
                && rest.cookies().get(0).endsWith("msToken=real-token")
                && rest.cookies().get(1).endsWith("msToken=real-token");
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
}
