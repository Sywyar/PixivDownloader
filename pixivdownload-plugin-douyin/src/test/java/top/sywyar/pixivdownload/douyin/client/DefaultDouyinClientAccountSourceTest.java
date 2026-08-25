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

@DisplayName("DefaultDouyinClient 账号来源与收藏分页")
class DefaultDouyinClientAccountSourceTest {

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
        assertThat(rest.methods()).containsExactly("POST");
        assertThat(rest.cookies()).singleElement().satisfies(cookie ->
                assertThat(cookie).contains("sessionid=test"));
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
            assertThat(rest.methods()).containsExactly("POST");
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
        assertThat(rest.methods()).containsExactly("GET");
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

    @Test
    @DisplayName("自建收藏夹与夹内作品分别使用真实上游游标分页")
    void pagesFavoriteFoldersAndFolderWorksIndependently() throws Exception {
        FakeRestTemplate rest = new FakeRestTemplate();
        rest.enqueue(200, """
                {"status_code":0,"has_more":1,"cursor":"folder-next","total":6,"collects_list":[
                  {"collects_id_str":"folder-a","collects_name":"收藏夹 A"},
                  {"collects_info":{"collects_id":"folder-b","collects_name":"收藏夹 B"}}
                ]}
                """);
        rest.enqueue(200, """
                {"status_code":0,"has_more":1,"cursor":"works-next","total":9,"aweme_list":[
                  {"aweme_id":"9601","desc":"Folder work",
                   "video":{"play_addr":{"url_list":["https://v3.douyinvod.com/9601.mp4"]}}}
                ]}
                """);
        DefaultDouyinClient client = client(rest);

        var folders = client.listFavoriteFolders("folder-current", 12, "sessionid=test");
        var works = client.listFavoriteFolderWorksPage(
                "folder-a", "works-current", 10, "sessionid=test");

        assertThat(folders.items()).extracting("id", "title")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("folder-a", "收藏夹 A"),
                        org.assertj.core.groups.Tuple.tuple("folder-b", "收藏夹 B"));
        assertThat(folders.total()).isEqualTo(6);
        assertThat(folders.nextCursor()).isEqualTo("folder-next");
        assertThat(folders.hasMore()).isTrue();
        assertThat(works.items()).extracting("id").containsExactly("9601");
        assertThat(works.items().get(0).collectionId()).isEqualTo("folder-a");
        assertThat(works.total()).isEqualTo(9);
        assertThat(works.nextCursor()).isEqualTo("works-next");
        assertThat(works.hasMore()).isTrue();
        assertThat(rest.requests()).extracting(URI::getPath).containsExactly(
                "/aweme/v1/web/collects/list/",
                "/aweme/v1/web/collects/video/list/");
        assertThat(rest.requests().get(0).getRawQuery())
                .contains("cursor=folder-current", "count=12", "version_code=170400", "a_bogus=");
        assertThat(rest.requests().get(1).getRawQuery())
                .contains("collects_id=folder-a", "cursor=works-current", "count=10",
                        "version_code=170400", "a_bogus=");
        assertThat(rest.methods()).containsExactly("GET", "GET");
        assertThat(rest.cookies()).allSatisfy(cookie ->
                assertThat(cookie).contains("sessionid=test"));
    }

    @Test
    @DisplayName("自建收藏夹两层响应缺少已知识别数组时分别报告结构异常")
    void rejectsUnknownFavoriteFolderResponseStructures() {
        FakeRestTemplate folderRest = new FakeRestTemplate();
        folderRest.enqueue(200, "{\"status_code\":0,\"unexpected\":[]}");
        assertCodeName(() -> client(folderRest).listFavoriteFolders(
                        "0", 20, "sessionid=test"),
                "RESPONSE_STRUCTURE_UNRECOGNIZED");

        FakeRestTemplate worksRest = new FakeRestTemplate();
        worksRest.enqueue(200, "{\"status_code\":0,\"unexpected\":[]}");
        assertCodeName(() -> client(worksRest).listFavoriteFolderWorksPage(
                        "folder-a", "0", 20, "sessionid=test"),
                "RESPONSE_STRUCTURE_UNRECOGNIZED");
    }

    @Test
    @DisplayName("自建收藏夹候选缺少稳定 ID 或作品缺少媒体时分别报告过滤异常")
    void rejectsFilteredFavoriteFolderCandidates() {
        FakeRestTemplate folderRest = new FakeRestTemplate();
        folderRest.enqueue(200, """
                {"status_code":0,"has_more":0,"cursor":"done","collects_list":[
                  {"collects_name":"Missing id"}
                ]}
                """);
        assertCodeName(() -> client(folderRest).listFavoriteFolders(
                        "0", 20, "sessionid=test"),
                "RESPONSE_CANDIDATES_FILTERED");

        FakeRestTemplate worksRest = new FakeRestTemplate();
        worksRest.enqueue(200, """
                {"status_code":0,"has_more":0,"cursor":"done","aweme_list":[
                  {"aweme_id":"9602","desc":"Missing media"}
                ]}
                """);
        assertCodeName(() -> client(worksRest).listFavoriteFolderWorksPage(
                        "folder-a", "0", 20, "sessionid=test"),
                "RESPONSE_CANDIDATES_FILTERED");
    }

    @Test
    @DisplayName("自建收藏夹两层仍有下一页但游标未推进时分别明确失败")
    void rejectsStalledFavoriteFolderCursors() {
        FakeRestTemplate folderRest = new FakeRestTemplate();
        folderRest.enqueue(200, """
                {"status_code":0,"has_more":1,"cursor":"folder-current","collects_list":[
                  {"collects_id":"folder-a","collects_name":"收藏夹 A"}
                ]}
                """);
        assertCodeName(() -> client(folderRest).listFavoriteFolders(
                        "folder-current", 20, "sessionid=test"),
                "PAGINATION_STALLED");

        FakeRestTemplate worksRest = new FakeRestTemplate();
        worksRest.enqueue(200, """
                {"status_code":0,"has_more":1,"cursor":"works-current","aweme_list":[
                  {"aweme_id":"9603","desc":"Folder work",
                   "video":{"play_addr":{"url_list":["https://v3.douyinvod.com/9603.mp4"]}}}
                ]}
                """);
        assertCodeName(() -> client(worksRest).listFavoriteFolderWorksPage(
                        "folder-a", "works-current", 20, "sessionid=test"),
                "PAGINATION_STALLED");
    }
}
