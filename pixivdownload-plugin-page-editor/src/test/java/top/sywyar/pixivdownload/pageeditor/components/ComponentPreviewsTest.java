package top.sywyar.pixivdownload.pageeditor.components;

import org.junit.jupiter.api.*;
import java.time.*;
import static org.assertj.core.api.Assertions.*;

class ComponentPreviewsTest {
    private static class MutableClock extends Clock {
        Instant now = Instant.parse("2020-01-01T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
    @Test @DisplayName("预览快照限制累计数量，到期、关闭和卸载均释放能力")
    void expiresRevokesAndLimitsPreviews() {
        var clock = new MutableClock();
        var previews = new ComponentPreviews(clock);
        var source = ComponentLibrary.JSON.createObjectNode().put("name", "Preview").put("html", "")
                .put("css", "").put("javascript", "");
        source.putObject("properties");
        String token = previews.create(source);
        source.put("html", "not published");
        assertThat(previews.read(token).path("html").asText()).isEmpty();
        for (int i = 0; i < 7; i++) previews.create(source);
        assertThatThrownBy(() -> previews.create(source)).hasMessage("PAGE_COMPONENT_PREVIEW_LIMIT");
        previews.remove(token);
        String revoked = token;
        assertThatThrownBy(() -> previews.read(revoked)).hasMessage("PAGE_COMPONENT_UNAVAILABLE");
        token = previews.create(source);
        clock.now = clock.now.plusSeconds(600);
        assertThat(previews.exists(token)).isFalse();
        String fresh = previews.create(source); previews.close();
        assertThat(previews.exists(fresh)).isFalse();
    }
}
