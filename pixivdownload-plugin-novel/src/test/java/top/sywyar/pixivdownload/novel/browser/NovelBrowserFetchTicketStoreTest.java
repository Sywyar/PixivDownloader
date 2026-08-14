package top.sywyar.pixivdownload.novel.browser;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.novel.schedule.PixivNovelMetadata;

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
        String fetchToken = store.issueFetchTicket(42L, metadata, "{\"id\":\"42\"}");

        assertThat(store.consumeFetchTicket(fetchToken, 41L)).isEmpty();
        assertThat(store.consumeFetchTicket(fetchToken, 42L)).isEmpty();

        String validToken = store.issueFetchTicket(42L, metadata, "{\"id\":\"42\"}");
        assertThat(store.consumeFetchTicket(validToken, 42L))
                .get()
                .extracting(NovelBrowserFetchTicketStore.ImportedNovel::metadata)
                .isEqualTo(metadata);
        assertThat(store.consumeFetchTicket(validToken, 42L)).isEmpty();
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
