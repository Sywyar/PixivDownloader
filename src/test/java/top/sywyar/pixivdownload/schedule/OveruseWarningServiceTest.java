package top.sywyar.pixivdownload.schedule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.sywyar.pixivdownload.download.PixivFetchService;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OveruseWarningService 过度访问检测")
class OveruseWarningServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long NOW = 1_700_000_000_000L;
    private static final long NOW_SECONDS = NOW / 1000L;

    @Mock
    private PixivFetchService pixivFetchService;
    @InjectMocks
    private OveruseWarningService service;

    private JsonNode body(String json) throws IOException {
        return MAPPER.readTree(json);
    }

    /** 构造一封官方过度访问警告线程（modified_at 为秒级 epoch）。 */
    private String officialWarning(long modifiedAtSeconds) {
        return "{\"message_threads\":[{"
                + "\"thread_name\":\"pixiv事務局\",\"is_official\":1,"
                + "\"latest_content\":\"<p>请阅读 https://policies.pixiv.net/#section14 第14条</p>\","
                + "\"modified_at\":" + modifiedAtSeconds + "}]}";
    }

    @Test
    @DisplayName("命中：官方线程 + policies.pixiv.net + 14 + 1h 窗口内 → WARNED（modifiedAt 转毫秒）")
    void warnsOnActionableWarning() throws Exception {
        when(pixivFetchService.fetchMessageThreads("ck"))
                .thenReturn(body(officialWarning(NOW_SECONDS - 60)));
        OveruseWarningService.Result r = service.check("ck", null, NOW);
        assertThat(r.isWarned()).isTrue();
        assertThat(r.modifiedAt()).isEqualTo((NOW_SECONDS - 60) * 1000L);
        assertThat(r.excerpt()).contains("policies.pixiv.net").doesNotContain("<p>");
    }

    @Test
    @DisplayName("超过 1 小时窗口的旧警告不触发 → CLEAN")
    void oldWarningOutsideWindowIsClean() throws Exception {
        when(pixivFetchService.fetchMessageThreads("ck"))
                .thenReturn(body(officialWarning(NOW_SECONDS - 7200)));
        assertThat(service.check("ck", null, NOW).isClean()).isTrue();
    }

    @Test
    @DisplayName("ackWarningTime >= 警告 modifiedAt（已显式放行）→ CLEAN")
    void ackedWarningIsClean() throws Exception {
        long modifiedSeconds = NOW_SECONDS - 60;
        when(pixivFetchService.fetchMessageThreads("ck"))
                .thenReturn(body(officialWarning(modifiedSeconds)));
        assertThat(service.check("ck", modifiedSeconds * 1000L, NOW).isClean()).isTrue();
    }

    @Test
    @DisplayName("非官方线程 / 缺 policies.pixiv.net 或 14 标记 → CLEAN（不依赖中文文案）")
    void nonActionableThreadIsClean() throws Exception {
        when(pixivFetchService.fetchMessageThreads("ck")).thenReturn(body(
                "{\"message_threads\":[{\"thread_name\":\"好友\",\"is_official\":0,"
                        + "\"latest_content\":\"policies.pixiv.net 14\",\"modified_at\":" + (NOW_SECONDS - 60) + "}]}"));
        assertThat(service.check("ck", null, NOW).isClean()).isTrue();

        when(pixivFetchService.fetchMessageThreads("ck2")).thenReturn(body(
                "{\"message_threads\":[{\"thread_name\":\"pixiv事務局\",\"is_official\":1,"
                        + "\"latest_content\":\"普通公告，无政策链接\",\"modified_at\":" + (NOW_SECONDS - 60) + "}]}"));
        assertThat(service.check("ck2", null, NOW).isClean()).isTrue();
    }

    @Test
    @DisplayName("fetchMessageThreads 抛 PixivFetchException（4xx / 登录重定向）→ COOKIE_DEAD")
    void deadCookieOnFetchException() throws Exception {
        when(pixivFetchService.fetchMessageThreads("ck"))
                .thenThrow(new PixivFetchService.PixivFetchException("http 403"));
        assertThat(service.check("ck", null, NOW).isCookieDead()).isTrue();
    }

    @Test
    @DisplayName("cookie 为空 → COOKIE_DEAD（读不到站内信）")
    void blankCookieIsDead() {
        assertThat(service.check("", null, NOW).isCookieDead()).isTrue();
        assertThat(service.check(null, null, NOW).isCookieDead()).isTrue();
    }

    @Test
    @DisplayName("瞬时异常（非 PixivFetchException）→ CLEAN，不误判 cookie 死、不误暂停")
    void transientErrorIsClean() throws Exception {
        when(pixivFetchService.fetchMessageThreads("ck"))
                .thenThrow(new RuntimeException("connection reset"));
        assertThat(service.check("ck", null, NOW).isClean()).isTrue();
    }
}
