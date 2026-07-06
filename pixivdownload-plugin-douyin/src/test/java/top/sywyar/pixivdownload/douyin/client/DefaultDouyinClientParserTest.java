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
                """)).isEqualTo("Douyin 8105");
    }

    @Test
    @DisplayName("解析图文图集图片候选")
    void parsesImageNote() throws Exception {
        DefaultDouyinClient client = client("""
                {"aweme_detail":{"aweme_id":"7352","desc":"Images","author":{"sec_uid":"sec","nickname":"Author"},
                "image_post_info":{"images":[
                {"watermark_free_download_url_list":["https://p3.douyinpic.com/a.jpg"]},
                {"display_image":{"url_list":["https://p3.douyinpic.com/b.webp"]}}
                ]}}}
                """);

        var work = client.resolvePublicWork("https://www.douyin.com/note/7352", null);

        assertThat(work.kind()).isEqualTo(DouyinWorkKind.IMAGE_NOTE);
        assertThat(work.media()).hasSize(2);
        assertThat(work.media()).allMatch(media -> media.type() == DouyinMediaType.IMAGE);
        assertThat(work.media().get(1).extension()).isEqualTo("webp");
    }

    @Test
    @DisplayName("解析 live-photo 图片与视频候选")
    void parsesLivePhoto() throws Exception {
        DefaultDouyinClient client = client("""
                {"aweme_detail":{"aweme_id":"7353","desc":"Live",
                "image_post_info":{"images":[{"display_image":{"url_list":["https://p3.douyinpic.com/a.jpg"]},
                "video":{"play_addr":{"url_list":["https://v3.douyinvod.com/live.mp4"]}}}]}}}
                """);

        var work = client.resolvePublicWork("https://www.douyin.com/gallery/7353", null);

        assertThat(work.kind()).isEqualTo(DouyinWorkKind.LIVE_PHOTO);
        assertThat(work.media()).extracting("type")
                .contains(DouyinMediaType.IMAGE, DouyinMediaType.LIVE_PHOTO_VIDEO);
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

    private static DefaultDouyinClient client(String... bodies) {
        FakeRestTemplate rest = new FakeRestTemplate();
        for (String body : bodies) {
            rest.enqueue(200, body);
        }
        DouyinUrlParser parser = new DouyinUrlParser();
        return new DefaultDouyinClient(parser, rest,
                (input, cookie) -> parser.parse(input).orElseThrow());
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
