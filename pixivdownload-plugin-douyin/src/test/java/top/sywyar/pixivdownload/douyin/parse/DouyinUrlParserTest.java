package top.sywyar.pixivdownload.douyin.parse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.douyin.model.DouyinParsedKind;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DouyinUrlParser 抖音 URL 解析")
class DouyinUrlParserTest {

    private final DouyinUrlParser parser = new DouyinUrlParser();

    @Test
    @DisplayName("解析 v.douyin.com 短链")
    void parsesShortLink() {
        var parsed = parser.parse("https://v.douyin.com/AbCd123/").orElseThrow();

        assertThat(parsed.kind()).isEqualTo(DouyinParsedKind.SHORT_LINK);
        assertThat(parsed.id()).isEqualTo("AbCd123");
        assertThat(parsed.canonicalUrl()).isEqualTo("https://v.douyin.com/AbCd123/");
        assertThat(parser.parse("v.iesdouyin.com/XyZ9/").orElseThrow().id()).isEqualTo("XyZ9");
        assertThat(parser.parse("iesdouyin.com/XyZ9/").orElseThrow().id()).isEqualTo("XyZ9");
    }

    @Test
    @DisplayName("兼容下载队列中的抖音内部 ID")
    void parsesQueueIds() {
        var shortLink = parser.parse("dshort-XUyPmdu7naU").orElseThrow();
        var video = parser.parse("d7351234567890123456").orElseThrow();

        assertThat(shortLink.kind()).isEqualTo(DouyinParsedKind.SHORT_LINK);
        assertThat(shortLink.id()).isEqualTo("XUyPmdu7naU");
        assertThat(shortLink.canonicalUrl()).isEqualTo("https://v.douyin.com/XUyPmdu7naU/");
        assertThat(video.kind()).isEqualTo(DouyinParsedKind.VIDEO);
        assertThat(video.id()).isEqualTo("7351234567890123456");
    }

    @Test
    @DisplayName("解析 www.douyin.com 单视频页面")
    void parsesVideoPage() {
        var parsed = parser.parse("https://www.douyin.com/video/7351234567890123456?previous_page=app_code_link")
                .orElseThrow();

        assertThat(parsed.kind()).isEqualTo(DouyinParsedKind.VIDEO);
        assertThat(parsed.id()).isEqualTo("7351234567890123456");
        assertThat(parser.parse("https://www.douyin.com/?modal_id=7351234567890123456").orElseThrow().id())
                .isEqualTo("7351234567890123456");
    }

    @Test
    @DisplayName("从分享文本中提取抖音作品 URL")
    void extractsUrlFromShareText() {
        var parsed = parser.parse("复制打开抖音，看看这个视频 https://www.douyin.com/video/7350000000000000001，复制此链接")
                .orElseThrow();

        assertThat(parsed.kind()).isEqualTo(DouyinParsedKind.VIDEO);
        assertThat(parsed.id()).isEqualTo("7350000000000000001");
    }

    @Test
    @DisplayName("解析图文、用户主页、合集和音乐 URL")
    void parsesProfileAndSeriesUrls() {
        assertThat(parser.parse("https://www.douyin.com/note/12345").orElseThrow().kind())
                .isEqualTo(DouyinParsedKind.NOTE);
        assertThat(parser.parse("https://www.douyin.com/gallery/12345").orElseThrow().kind())
                .isEqualTo(DouyinParsedKind.GALLERY);
        assertThat(parser.parse("https://www.douyin.com/slides/12345").orElseThrow().kind())
                .isEqualTo(DouyinParsedKind.GALLERY);
        assertThat(parser.parse("https://www.douyin.com/user/MS4wLjABAAAA-demo").orElseThrow().kind())
                .isEqualTo(DouyinParsedKind.USER_PROFILE);
        assertThat(parser.parse("https://www.douyin.com/collection/12345").orElseThrow().kind())
                .isEqualTo(DouyinParsedKind.COLLECTION);
        assertThat(parser.parse("https://www.douyin.com/mix/12345").orElseThrow().kind())
                .isEqualTo(DouyinParsedKind.COLLECTION);
        assertThat(parser.parse("https://www.douyin.com/music/12345").orElseThrow().kind())
                .isEqualTo(DouyinParsedKind.MUSIC);
        assertThat(parser.parse("https://www.iesdouyin.com/share/video/998877").orElseThrow().id())
                .isEqualTo("998877");
    }

    @Test
    @DisplayName("拒绝非法 URL 与非抖音域名")
    void rejectsInvalidOrNonDouyinUrls() {
        assertThat(parser.parse("not a url")).isEmpty();
        assertThat(parser.parse("https://www.tiktok.com/@demo/video/123")).isEmpty();
        assertThat(parser.parse("https://douyin.example.com/video/123")).isEmpty();
    }
}
