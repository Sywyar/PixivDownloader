package top.sywyar.pixivdownload.download;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxClient;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxException;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxFailure;

import java.io.IOException;
import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PixivFetchService} 中搜索与账号私有来源发现、解析和失败传播的单元测试。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PixivFetchService 作品发现")
class PixivFetchServiceDiscoveryTest {

    private static final ObjectMapper MAPPER = JsonMapper.builderWithJackson2Defaults().build();
    /** PHPSESSID 下划线前缀 = uid（账号私有发现需要它取 uid）。 */
    private static final String COOKIE = "PHPSESSID=12345_abcdefghijklmnop";

    @Mock
    private PixivAjaxClient pixivAjaxClient;

    private PixivFetchService service;

    private void mockResponse(String json) {
        when(pixivAjaxClient.get(any(URI.class), any())).thenReturn(json);
    }

    private PixivFetchService service() {
        if (service == null) {
            service = new PixivFetchService(pixivAjaxClient, MAPPER);
        }
        return service;
    }

    @Nested
    @DisplayName("小说搜索分页")
    class NovelSearch {

        @ParameterizedTest
        @ValueSource(booleans = {false, true})
        @DisplayName("按单页请求保留作品顺序与总数，关键词只编码一次")
        void readsOneSearchPage(boolean novel) throws Exception {
            String key = novel ? "novel" : "illustManga";
            mockResponse("{\"body\":{\"" + key + "\":{\"total\":5,\"data\":[{\"id\":\"20\"},{\"id\":\"10\"}]}}}");
            var page = service().fetchSearchPage(novel, "猫 空", "date_d", "all", "s_tag", 2, COOKIE);
            assertThat(page.ids()).containsExactly("20", "10");
            assertThat(page.total()).isEqualTo(5);
            ArgumentCaptor<URI> request = ArgumentCaptor.forClass(URI.class);
            verify(pixivAjaxClient).get(request.capture(), eq(COOKIE));
            assertThat(request.getValue().getRawPath()).isEqualTo("/ajax/search/"
                    + (novel ? "novels" : "artworks") + "/%E7%8C%AB%20%E7%A9%BA");
            assertThat(request.getValue().getRawQuery()).contains("word=%E7%8C%AB%20%E7%A9%BA", "p=2",
                    "order=date_d", "mode=all", "s_mode=s_tag", "lang=zh");
            assertThat(request.getValue().getRawQuery().contains("type=illust_and_ugoira")).isEqualTo(!novel);
        }

        @ParameterizedTest
        @ValueSource(strings = {"[]", "{}", "null", "[{\"id\":\"\"},{}]"})
        void ignoresUnusableIds(String data) throws Exception {
            mockResponse("{\"body\":{\"novel\":{\"data\":" + data + "}}}");
            assertThat(service().fetchSearchPage(true, "word", "date_d", "all", "s_tag", 1, COOKIE).ids()).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(booleans = {false, true})
        void propagatesPageFailure(boolean malformedJson) {
            mockResponse(malformedJson ? "broken-json" : "{\"error\":true,\"message\":\"blocked\"}");
            assertThatThrownBy(() -> service().fetchSearchPage(true, "word", "date_d", "all", "s_tag", 2, COOKIE))
                    .isInstanceOf(malformedJson ? IOException.class : PixivFetchService.PixivFetchException.class);
        }
    }

    @Nested
    @DisplayName("站内信失败投影")
    class MessageThreadFailures {

        @Test
        @DisplayName("站内信只有明确未认证响应判定凭据失效")
        void mapsUnauthenticatedToCredentialFailure() {
            when(pixivAjaxClient.get(any(URI.class), any()))
                    .thenThrow(new PixivAjaxException(PixivAjaxFailure.HTTP_STATUS, 401));

            assertThatThrownBy(() -> service().fetchMessageThreads(COOKIE))
                    .isInstanceOf(PixivFetchService.PixivFetchException.class)
                    .hasMessageContaining("401");
        }

        @ParameterizedTest
        @ValueSource(ints = {302, 403, 404, 429})
        @DisplayName("重定向、拒绝和限流保留原错误且不误判凭据失效")
        void preservesOtherResponses(int status) {
            var failure = new PixivAjaxException(PixivAjaxFailure.HTTP_STATUS, status);
            when(pixivAjaxClient.get(any(URI.class), any())).thenThrow(failure);
            assertThatThrownBy(() -> service().fetchMessageThreads(COOKIE)).isSameAs(failure);
        }

        @ParameterizedTest
        @ValueSource(strings = {"<html>challenge</html>", "{\"error\":true}", "{}"})
        @DisplayName("挑战页面和不完整响应不能当作失效凭据")
        void rejectsUnverifiedMessageBody(String body) {
            mockResponse(body);
            assertThatThrownBy(() -> service().fetchMessageThreads(COOKIE))
                    .isInstanceOf(IOException.class).isNotInstanceOf(PixivFetchService.PixivFetchException.class);
        }

        @Test
        @DisplayName("站内信服务端与传输失败保持瞬时网络失败")
        void preservesServerAndTransportFailures() {
            PixivAjaxException serverFailure =
                    new PixivAjaxException(PixivAjaxFailure.HTTP_STATUS, 503);
            when(pixivAjaxClient.get(any(URI.class), any())).thenThrow(serverFailure);

            assertThatThrownBy(() -> service().fetchMessageThreads(COOKIE))
                    .isSameAs(serverFailure);

            PixivAjaxException transportFailure =
                    new PixivAjaxException(PixivAjaxFailure.TRANSPORT, 0);
            doThrow(transportFailure).when(pixivAjaxClient).get(any(URI.class), any());

            assertThatThrownBy(() -> service().fetchMessageThreads(COOKIE))
                    .isSameAs(transportFailure);
        }
    }

    @Nested
    @DisplayName("纯解析（无需触网）")
    class PureParsing {

        @Test
        @DisplayName("resolveOwnUid 取 PHPSESSID 下划线前缀；缺失 / 非法返回 null")
        void resolveOwnUid() {
            assertThat(PixivFetchService.resolveOwnUid(COOKIE)).isEqualTo("12345");
            assertThat(PixivFetchService.resolveOwnUid(null)).isNull();
            assertThat(PixivFetchService.resolveOwnUid("foo=bar")).isNull();
            assertThat(PixivFetchService.resolveOwnUid("PHPSESSID=nounderscore")).isNull();
            assertThat(PixivFetchService.resolveOwnUid("PHPSESSID=abc_def")).isNull();
        }

        @Test
        @DisplayName("followLatestPageIds 优先取 page.ids 顺序，缺失时回退 thumbnails.illust")
        void followLatestPageIds() throws Exception {
            JsonNode withIds = MAPPER.readTree("""
                    {"page":{"ids":["3","1","2"]},
                     "thumbnails":{"illust":[{"id":"1"},{"id":"2"},{"id":"3"}]}}
                    """);
            assertThat(PixivFetchService.followLatestPageIds(withIds)).containsExactly("3", "1", "2");

            JsonNode fallback = MAPPER.readTree("""
                    {"page":{},"thumbnails":{"illust":[{"id":"9"},{"id":"8"}]}}
                    """);
            assertThat(PixivFetchService.followLatestPageIds(fallback)).containsExactly("9", "8");

            assertThat(PixivFetchService.followLatestPageIds(null)).isEmpty();
        }

        @Test
        @DisplayName("parseCollectionWorkIds 按 tiles 拆分插画 / 小说，跳过非 Active / 非 Work")
        void parseCollectionWorkIds() throws Exception {
            JsonNode body = MAPPER.readTree("""
                    {"data":{"detail":{"tiles":[
                      {"type":"Work","status":"Active","workType":"illust","workId":"100"},
                      {"type":"Work","status":"Active","workType":"novel","workId":"200"},
                      {"type":"Work","status":"Hidden","workType":"illust","workId":"300"},
                      {"type":"Header","status":"Active","workType":"illust","workId":"400"},
                      {"type":"Work","status":"Active","workType":"manga","workId":"500"}
                    ]}}}
                    """);
            PixivFetchService.CollectionWorkIds ids = PixivFetchService.parseCollectionWorkIds(body);
            // novel → novelIds；其余（illust/manga/未注明）归入 illustIds；Hidden / 非 Work 被跳过
            assertThat(ids.illustIds()).containsExactly("100", "500");
            assertThat(ids.novelIds()).containsExactly("200");
        }

        @Test
        @DisplayName("parseRequestArtworkIds 递归取 postWork.postWorkId，去重 / 跳过非正数 / 按 ID 倒序")
        void parseRequestArtworkIds() throws Exception {
            // 约稿成品 ID 承载在 postWork.postWorkId；进行中的约稿（无 postWork）不计；wrapper key 名不敏感（递归）。
            JsonNode body = MAPPER.readTree("""
                    {"requests":[
                      {"requestId":"a","postWork":{"postWorkId":"100"}},
                      {"requestId":"b","postWork":{"postWorkId":"300"}},
                      {"requestId":"c","requestProposal":{"requestOriginalProposalHtml":"x"}},
                      {"requestId":"d","postWork":{"postWorkId":"100"}},
                      {"requestId":"e","postWork":{"postWorkId":"0"}},
                      {"requestId":"f","postWork":{"postWorkId":""}}
                    ]}
                    """);
            assertThat(PixivFetchService.parseRequestArtworkIds(body)).containsExactly("300", "100");
            assertThat(PixivFetchService.parseRequestArtworkIds(MAPPER.readTree("{}"))).isEmpty();
        }
    }

    @Nested
    @DisplayName("分页发现（隔离外部请求）")
    class PagedDiscovery {

        @Test
        @DisplayName("插画收藏：单页拉全、按 id 去重")
        void illustBookmarks() throws Exception {
            mockResponse("""
                    {"error":false,"body":{"total":2,"works":[{"id":"11"},{"id":"22"},{"id":"11"}]}}
                    """);
            List<String> ids = service().discoverMyIllustBookmarkIds("show", COOKIE);
            assertThat(ids).containsExactly("11", "22");
        }

        @Test
        @DisplayName("已关注用户的新作：isLastPage=true 即停、按 id 去重")
        void followLatest() throws Exception {
            mockResponse("""
                    {"error":false,"body":{"page":{"ids":["5","6"],"isLastPage":true},
                     "thumbnails":{"illust":[{"id":"5"},{"id":"6"}]}}}
                    """);
            List<String> ids = service().discoverFollowLatestIllustIds(COOKIE);
            assertThat(ids).containsExactly("5", "6");
        }

        @Test
        @DisplayName("已关注用户的新作单页：透出 feed 顺序 ID 与 isLastPage（供水位线增量逐页消费）")
        void fetchFollowLatestPage() throws Exception {
            mockResponse("""
                    {"error":false,"body":{"page":{"ids":["9","8","7"],"isLastPage":true}}}
                    """);
            PixivFetchService.FollowLatestPage page = service().fetchFollowLatestPage(1, COOKIE);
            assertThat(page.ids()).containsExactly("9", "8", "7");
            assertThat(page.lastPage()).isTrue();
        }

        @Test
        @DisplayName("已关注用户的新作单页：缺少 PHPSESSID 直接抛 PixivFetchException（不触网）")
        void fetchFollowLatestPageMissingPhpsessid() {
            assertThatThrownBy(() -> service().fetchFollowLatestPage(1, "foo=bar"))
                    .isInstanceOf(PixivFetchService.PixivFetchException.class);
        }

        @Test
        @DisplayName("珍藏集：返回插画 + 小说两份成员 ID")
        void collection() throws Exception {
            mockResponse("""
                    {"error":false,"body":{"data":{"detail":{"tiles":[
                      {"type":"Work","status":"Active","workType":"illust","workId":"7"},
                      {"type":"Work","status":"Active","workType":"novel","workId":"8"}
                    ]}}}}
                    """);
            PixivFetchService.CollectionWorkIds ids = service().discoverCollectionWorkIds("99887766", COOKIE);
            assertThat(ids.illustIds()).containsExactly("7");
            assertThat(ids.novelIds()).containsExactly("8");
        }

        @Test
        @DisplayName("缺少 / 非法 PHPSESSID 时直接抛 PixivFetchException（不触网）")
        void missingPhpsessidThrows() {
            assertThatThrownBy(() -> service().discoverMyIllustBookmarkIds("show", "foo=bar"))
                    .isInstanceOf(PixivFetchService.PixivFetchException.class);
            assertThatThrownBy(() -> service().discoverFollowLatestIllustIds(null))
                    .isInstanceOf(PixivFetchService.PixivFetchException.class);
            assertThatThrownBy(() -> service().discoverCollectionWorkIds("1", "PHPSESSID=nounderscore"))
                    .isInstanceOf(PixivFetchService.PixivFetchException.class);
        }
    }
}
