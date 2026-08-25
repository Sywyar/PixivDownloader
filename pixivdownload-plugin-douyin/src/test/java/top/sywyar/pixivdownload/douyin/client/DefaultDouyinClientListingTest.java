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

@DisplayName("DefaultDouyinClient 公开来源列表与分页")
class DefaultDouyinClientListingTest {

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
    @DisplayName("目标用户喜欢作品使用 sec_uid 与不透明游标且不解析当前账号")
    void listsTargetUserLikedWorksWithOpaqueCursor() throws Exception {
        FakeRestTemplate rest = new FakeRestTemplate();
        rest.enqueue(200, """
                {"status_code":0,"has_more":1,"max_cursor":"liked-next","aweme_list":[
                  {"aweme_id":"9151","desc":"Liked work","author":{"sec_uid":"sec-target","nickname":"目标作者"},
                   "video":{"play_addr":{"url_list":["https://v3.douyinvod.com/9151.mp4"]}}}
                ]}
                """);

        var listing = client(rest).listUserLikedWorksPage(
                "sec-target", "liked-current", 24, "sessionid=test");

        assertThat(listing.items()).extracting("id").containsExactly("9151");
        assertThat(listing.ownerId()).isEqualTo("sec-target");
        assertThat(listing.ownerName()).isEqualTo("目标作者");
        assertThat(listing.nextCursor()).isEqualTo("liked-next");
        assertThat(listing.hasMore()).isTrue();
        assertThat(rest.requests()).singleElement().satisfies(uri -> {
            assertThat(uri.getPath()).isEqualTo("/aweme/v1/web/aweme/favorite/");
            assertThat(uri.getRawQuery()).contains(
                    "sec_user_id=sec-target",
                    "max_cursor=liked-current",
                    "count=24",
                    "locate_query=false",
                    "a_bogus=");
        });
        assertThat(rest.methods()).containsExactly("GET");
    }

    @Test
    @DisplayName("目标用户喜欢作品逻辑预览按游标批量遍历深页")
    void targetUserLikedLogicalPaginationUsesUpstreamBatchSize() throws Exception {
        FakeRestTemplate rest = new FakeRestTemplate();
        rest.enqueue(200, userPage(1, 20, true, "liked-20"));
        rest.enqueue(200, userPage(21, 1, false, "liked-done"));

        var listing = client(rest).listUserLikedWorks("sec-target", 20, 1, null);

        assertThat(listing.items()).extracting("id").containsExactly("9021");
        assertThat(rest.requests()).hasSize(2)
                .allSatisfy(uri -> {
                    assertThat(uri.getPath()).isEqualTo("/aweme/v1/web/aweme/favorite/");
                    assertThat(uri.getRawQuery()).contains("sec_user_id=sec-target", "count=20");
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
    @DisplayName("目标用户喜欢作品响应缺少已知识别数组时明确报告结构异常")
    void rejectsUnknownLikedWorksResponseStructure() {
        FakeRestTemplate rest = new FakeRestTemplate();
        rest.enqueue(200, "{\"status_code\":0,\"unexpected\":[]}");

        assertCodeName(() -> client(rest).listUserLikedWorksPage(
                        "sec-target", "0", 20, "sessionid=test"),
                "RESPONSE_STRUCTURE_UNRECOGNIZED");
    }

    @Test
    @DisplayName("目标用户明确隐藏喜欢列表时报告权限拒绝")
    void rejectsExplicitlyHiddenTargetUserLikedWorks() {
        FakeRestTemplate rest = new FakeRestTemplate();
        rest.enqueue(200, """
                {"status_code":0,"status_msg":"该用户已隐藏喜欢列表","has_more":0,"aweme_list":[]}
                """);

        assertCodeName(() -> client(rest).listUserLikedWorksPage(
                        "sec-target", "0", 20, "sessionid=test"),
                "PERMISSION_DENIED");
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
                .listUserLikedWorksPage("sec-user-1", "0", 20, "sessionid=test").items())
                .isEmpty();
        assertThat(client("{\"status_code\":0,\"has_more\":0,\"aweme_list\":null}")
                .listUserLikedWorksPage("sec-user-1", "0", 20, "sessionid=test").items())
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
}
