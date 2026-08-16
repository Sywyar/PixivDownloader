package top.sywyar.pixivdownload.novel.browser;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.novel.schedule.PixivNovelMetadata;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentity;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("浏览器授权小说抓取票据")
class NovelBrowserFetchTicketStoreTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("上传令牌和下载票据均一次性且下载票据绑定作品")
    void tokensAreOneTimeAndFetchTicketIsBoundToNovel() throws Exception {
        MutableClock clock = new MutableClock();
        NovelBrowserFetchTicketStore store = new NovelBrowserFetchTicketStore(clock, new SecureRandom());

        String importToken = store.issueImportToken();
        assertThat(store.claimImportToken(importToken)).isTrue();
        assertThat(store.claimImportToken(importToken)).isFalse();

        PixivNovelMetadata metadata = PixivNovelMetadata.parse(42L, objectMapper.readTree("""
                {"id":"42","title":"restricted","content":"body","userId":"7","userName":"author"}
                """));
        String fetchToken = store.issueBrowserFetchTicket(42L, metadata, "{\"id\":\"42\"}");

        RequestOwnerIdentity admin = RequestOwnerIdentity.adminScope();
        assertThat(store.consumeFetchTicket(fetchToken, 41L, admin, null, true)).isEmpty();
        assertThat(store.consumeFetchTicket(fetchToken, 42L, admin, null, true)).isEmpty();

        String disallowedToken = store.issueBrowserFetchTicket(42L, metadata, "{\"id\":\"42\"}");
        assertThat(store.consumeFetchTicket(disallowedToken, 42L, admin, null, false)).isEmpty();

        String validToken = store.issueBrowserFetchTicket(42L, metadata, "{\"id\":\"42\"}");
        assertThat(store.consumeFetchTicket(validToken, 42L, admin, null, true))
                .get()
                .extracting(NovelBrowserFetchTicketStore.ImportedNovel::metadata)
                .isEqualTo(metadata);
        assertThat(store.consumeFetchTicket(validToken, 42L, admin, null, true)).isEmpty();
    }

    @Test
    @DisplayName("过期令牌不会恢复为有效")
    void expiredTokensAreRejected() {
        MutableClock clock = new MutableClock();
        NovelBrowserFetchTicketStore store = new NovelBrowserFetchTicketStore(clock, new SecureRandom());
        String importToken = store.issueImportToken();

        clock.advanceSeconds(NovelBrowserFetchTicketStore.TICKET_TTL_SECONDS + 1);

        assertThat(store.claimImportToken(importToken)).isFalse();
    }

    @Test
    @DisplayName("Web 预览票据绑定 owner、作品与取得凭据")
    void previewTicketIsBoundToRequestContext() throws Exception {
        NovelBrowserFetchTicketStore store = new NovelBrowserFetchTicketStore();
        PixivNovelMetadata metadata = PixivNovelMetadata.parse(42L, objectMapper.readTree("""
                {"id":"42","title":"preview","content":"body","userId":"7","userName":"author"}
                """));
        RequestOwnerIdentity owner = RequestOwnerIdentity.owner("owner-a");

        String wrongOwner = store.issuePreviewFetchTicket(
                42L, metadata, "{\"id\":\"42\"}", owner, "cookie-a");
        assertThat(store.consumeFetchTicket(
                wrongOwner, 42L, RequestOwnerIdentity.owner("owner-b"), "cookie-a", false)).isEmpty();

        String wrongCredential = store.issuePreviewFetchTicket(
                42L, metadata, "{\"id\":\"42\"}", owner, "cookie-a");
        assertThat(store.consumeFetchTicket(
                wrongCredential, 42L, owner, "cookie-b", false)).isEmpty();

        String valid = store.issuePreviewFetchTicket(
                42L, metadata, "{\"id\":\"42\"}", owner, "cookie-a");
        assertThat(store.consumeFetchTicket(valid, 42L, owner, "cookie-a", false))
                .get()
                .extracting(NovelBrowserFetchTicketStore.ImportedNovel::origin)
                .isEqualTo(NovelBrowserFetchTicketStore.FetchOrigin.WEB_PREVIEW);
        assertThat(store.consumeFetchTicket(valid, 42L, owner, "cookie-a", false)).isEmpty();
    }

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-08-15T00:00:00Z");

        void advanceSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
