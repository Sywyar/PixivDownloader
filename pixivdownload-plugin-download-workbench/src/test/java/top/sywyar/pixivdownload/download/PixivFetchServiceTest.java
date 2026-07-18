package top.sywyar.pixivdownload.download;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PixivFetchService 上传时间解析")
class PixivFetchServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String text) {
        try {
            return mapper.readTree(text);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static long epoch(String iso) {
        return OffsetDateTime.parse(iso).toInstant().toEpochMilli();
    }

    @Test
    @DisplayName("优先 ISO 日期串 uploadDate")
    void shouldPreferIsoUploadDate() {
        String iso = "2026-06-06T21:27:00+00:00";
        JsonNode body = json("{\"uploadDate\":\"" + iso + "\",\"uploadTimestamp\":111}");
        assertThat(PixivFetchService.extractUploadTimestamp(body)).isEqualTo(epoch(iso));
    }

    @Test
    @DisplayName("仅 uploadTimestamp 且为 epoch 毫秒数字时直接采用")
    void shouldAcceptUploadTimestampMillis() {
        long millis = epoch("2026-06-06T21:27:00+00:00");
        JsonNode body = json("{\"uploadTimestamp\":" + millis + "}");
        assertThat(PixivFetchService.extractUploadTimestamp(body)).isEqualTo(millis);
    }

    @Test
    @DisplayName("uploadTimestamp 为 epoch 秒时换算成毫秒")
    void shouldNormalizeUploadTimestampSeconds() {
        long millis = epoch("2026-06-06T21:27:00+00:00");
        long seconds = millis / 1000L;
        JsonNode body = json("{\"uploadTimestamp\":" + seconds + "}");
        assertThat(PixivFetchService.extractUploadTimestamp(body)).isEqualTo(seconds * 1000L);
    }

    @Test
    @DisplayName("uploadTimestamp 为 ISO 字符串时正确解析")
    void shouldAcceptUploadTimestampIsoString() {
        String iso = "2026-06-06T21:27:00+00:00";
        JsonNode body = json("{\"uploadTimestamp\":\"" + iso + "\"}");
        assertThat(PixivFetchService.extractUploadTimestamp(body)).isEqualTo(epoch(iso));
    }

    @Test
    @DisplayName("uploadTimestamp 为数字字符串时按毫秒/秒消歧")
    void shouldAcceptUploadTimestampNumericString() {
        long millis = epoch("2026-06-06T21:27:00+00:00");
        assertThat(PixivFetchService.extractUploadTimestamp(json("{\"uploadTimestamp\":\"" + millis + "\"}")))
                .isEqualTo(millis);
        long seconds = millis / 1000L;
        assertThat(PixivFetchService.extractUploadTimestamp(json("{\"uploadTimestamp\":\"" + seconds + "\"}")))
                .isEqualTo(seconds * 1000L);
    }

    @Test
    @DisplayName("非法字符串 uploadTimestamp 返回 null（绝不退化成 0）")
    void shouldReturnNullForIllegalUploadTimestamp() {
        assertThat(PixivFetchService.extractUploadTimestamp(json("{\"uploadTimestamp\":\"not-a-date\"}"))).isNull();
        assertThat(PixivFetchService.extractUploadTimestamp(json("{\"uploadTimestamp\":\"\"}"))).isNull();
        assertThat(PixivFetchService.extractUploadTimestamp(json("{\"uploadTimestamp\":0}"))).isNull();
        assertThat(PixivFetchService.extractUploadTimestamp(json("{\"uploadTimestamp\":-1}"))).isNull();
    }

    @Test
    @DisplayName("无任何上传时间字段时返回 null")
    void shouldReturnNullWhenAbsent() {
        assertThat(PixivFetchService.extractUploadTimestamp(json("{\"title\":\"x\"}"))).isNull();
    }

    @Test
    @DisplayName("parseFlexibleEpochMillis 缺失 / null 节点返回 null")
    void shouldReturnNullForMissingNode() {
        assertThat(PixivFetchService.parseFlexibleEpochMillis(json("{}").path("nope"))).isNull();
        assertThat(PixivFetchService.parseFlexibleEpochMillis(json("{\"x\":null}").path("x"))).isNull();
    }
}
