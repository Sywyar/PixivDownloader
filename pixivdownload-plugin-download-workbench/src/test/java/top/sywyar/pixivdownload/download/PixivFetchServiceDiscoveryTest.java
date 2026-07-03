package top.sywyar.pixivdownload.download;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * {@link PixivFetchService} 中账号私有来源（收藏 / 已关注用户的新作 / 珍藏集）发现与解析逻辑的单元测试。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PixivFetchService 账号私有来源发现")
class PixivFetchServiceDiscoveryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** PHPSESSID 下划线前缀 = uid（账号私有发现需要它取 uid）。 */
    private static final String COOKIE = "PHPSESSID=12345_abcdefghijklmnop";

    @Mock
    private RestTemplate restTemplate;

    private PixivFetchService service;

    private void mockResponse(String json) {
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(), eq(byte[].class)))
                .thenReturn(ResponseEntity.ok(json.getBytes(StandardCharsets.UTF_8)));
    }

    private PixivFetchService service() {
        if (service == null) {
            service = new PixivFetchService(restTemplate, MAPPER);
        }
        return service;
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
    @DisplayName("分页发现（mock RestTemplate）")
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
