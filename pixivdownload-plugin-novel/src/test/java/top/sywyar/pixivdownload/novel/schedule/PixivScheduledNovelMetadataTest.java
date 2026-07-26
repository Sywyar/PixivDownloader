package top.sywyar.pixivdownload.novel.schedule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Pixiv 计划小说元数据上传时间解析")
class PixivScheduledNovelMetadataTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("优先使用 ISO 日期串 uploadDate")
    void shouldPreferIsoUploadDate() {
        String iso = "2026-06-06T21:27:00+00:00";

        assertThat(uploadTimestamp(
                "{\"uploadDate\":\"" + iso + "\",\"uploadTimestamp\":111}"))
                .isEqualTo(epoch(iso));
    }

    @Test
    @DisplayName("uploadTimestamp 为 epoch 毫秒数字时直接采用")
    void shouldAcceptUploadTimestampMillis() {
        long millis = epoch("2026-06-06T21:27:00+00:00");

        assertThat(uploadTimestamp("{\"uploadTimestamp\":" + millis + "}"))
                .isEqualTo(millis);
    }

    @Test
    @DisplayName("uploadTimestamp 为 epoch 秒时换算成毫秒")
    void shouldNormalizeUploadTimestampSeconds() {
        long seconds = epoch("2026-06-06T21:27:00+00:00") / 1000L;

        assertThat(uploadTimestamp("{\"uploadTimestamp\":" + seconds + "}"))
                .isEqualTo(seconds * 1000L);
    }

    @Test
    @DisplayName("uploadTimestamp 为 ISO 字符串时正确解析")
    void shouldAcceptUploadTimestampIsoString() {
        String iso = "2026-06-06T21:27:00+00:00";

        assertThat(uploadTimestamp("{\"uploadTimestamp\":\"" + iso + "\"}"))
                .isEqualTo(epoch(iso));
    }

    @Test
    @DisplayName("uploadTimestamp 为数字字符串时按毫秒与秒消歧")
    void shouldAcceptUploadTimestampNumericString() {
        long millis = epoch("2026-06-06T21:27:00+00:00");
        long seconds = millis / 1000L;

        assertThat(uploadTimestamp("{\"uploadTimestamp\":\"" + millis + "\"}"))
                .isEqualTo(millis);
        assertThat(uploadTimestamp("{\"uploadTimestamp\":\"" + seconds + "\"}"))
                .isEqualTo(seconds * 1000L);
    }

    @Test
    @DisplayName("非法、零或负数 uploadTimestamp 返回 null")
    void shouldReturnNullForInvalidUploadTimestamp() {
        assertThat(uploadTimestamp("{\"uploadTimestamp\":\"not-a-date\"}"))
                .isNull();
        assertThat(uploadTimestamp("{\"uploadTimestamp\":\"\"}"))
                .isNull();
        assertThat(uploadTimestamp("{\"uploadTimestamp\":0}"))
                .isNull();
        assertThat(uploadTimestamp("{\"uploadTimestamp\":-1}"))
                .isNull();
    }

    @Test
    @DisplayName("上传时间字段缺失或为 null 时返回 null")
    void shouldReturnNullWhenAbsentOrNull() {
        assertThat(uploadTimestamp("{\"title\":\"x\"}"))
                .isNull();
        assertThat(uploadTimestamp("{\"uploadTimestamp\":null}"))
                .isNull();
    }

    private Long uploadTimestamp(String json) {
        return PixivScheduledNovelMetadata.parse(1L, parse(json)).uploadTimestamp();
    }

    private JsonNode parse(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static long epoch(String iso) {
        return OffsetDateTime.parse(iso).toInstant().toEpochMilli();
    }
}
