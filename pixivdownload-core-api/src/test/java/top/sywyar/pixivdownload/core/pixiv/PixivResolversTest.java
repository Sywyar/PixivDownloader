package top.sywyar.pixivdownload.core.pixiv;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Pixiv 纯解析器")
class PixivResolversTest {

    @Test
    @DisplayName("Cookie 用户解析只接受带数字前缀的 PHPSESSID")
    void cookieUserResolverAcceptsOnlyNumericPhpSessionPrefix() {
        assertThat(PixivCookieUserResolver.extractUidFromCookie(
                "foo=bar; PHPSESSID=12345_suffix; baz=1")).isEqualTo("12345");
        assertThat(PixivCookieUserResolver.extractUidFromCookie(
                "phpsessid=9460149_xyz")).isEqualTo("9460149");
        assertThat(PixivCookieUserResolver.extractUidFromCookie(null)).isNull();
        assertThat(PixivCookieUserResolver.extractUidFromCookie("PHPSESSID=ab12_xyz")).isNull();
        assertThat(PixivCookieUserResolver.extractUidFromCookie("PHPSESSID=12345")).isNull();
    }

    @Test
    @DisplayName("小说缓存封面优先回退到高分辨率地址并保留原地址候选")
    void coverResolverPrefersHighResolutionAndKeepsFallback() {
        String cached = "https://i.pximg.net/c/600x600/novel-cover-master/img/2026/01/02/cover.jpg?x=1#f";
        String high = "https://i.pximg.net/novel-cover-master/img/2026/01/02/cover.jpg?x=1#f";

        assertThat(PixivCoverUrlResolver.preferHighResolution(cached)).isEqualTo(high);
        assertThat(PixivCoverUrlResolver.downloadCandidates(cached)).containsExactly(high, cached);
        assertThat(PixivCoverUrlResolver.downloadCandidates(high)).containsExactly(high);
        assertThat(PixivCoverUrlResolver.downloadCandidates(" ")).isEmpty();
    }
}
