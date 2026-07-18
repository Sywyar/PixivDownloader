package top.sywyar.pixivdownload.core.metadata.sidecar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("WorkMetaCurator 归一化与剪枝")
class WorkMetaCuratorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final WorkMetaCurator curator = new WorkMetaCurator(mapper);

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
    @DisplayName("插画：剥 C 类（计数/会话私有/巨型噪声）、保留 A+B，normalized 抽出高价值字段 + 列投影")
    void shouldCurateArtwork() {
        JsonNode illust = json("""
                {"id":"1","illustTitle":"t","description":"d",
                 "uploadDate":"2026-06-06T21:27:00+00:00","createDate":"2026-06-06T21:27:00+00:00",
                 "isOriginal":false,"isUnlisted":false,"illustComment":"d","restrict":0,"alt":"a",
                 "bookmarkCount":266,"likeCount":187,"viewCount":1254,"commentCount":5,
                 "bookmarkData":null,"likeData":false,"userIllusts":{"x":1},"zoneConfig":{"z":1},"extraData":{"e":1},
                 "titleCaptionTranslation":{"workTitle":null,"workCaption":"译文"}}""");
        JsonNode pages = json("[{\"urls\":{\"original\":\"https://i.pximg.net/x_p0.jpg\"},\"width\":1640,\"height\":2360}]");

        CuratedWorkMeta c = curator.curateArtwork(1L, illust, pages, "schedule");

        assertThat(c.uploadTime()).isEqualTo(epoch("2026-06-06T21:27:00+00:00"));
        assertThat(c.isOriginal()).isFalse();

        JsonNode doc = c.document();
        assertThat(doc.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(doc.path("workType").asText()).isEqualTo("ARTWORK");
        assertThat(doc.path("workId").asLong()).isEqualTo(1L);
        assertThat(doc.path("source").asText()).isEqualTo("schedule");

        JsonNode n = doc.path("normalized");
        assertThat(n.path("uploadTime").asLong()).isEqualTo(c.uploadTime());
        assertThat(n.path("isOriginal").asBoolean()).isFalse();
        assertThat(n.path("isUnlisted").asBoolean()).isFalse();
        assertThat(n.path("captionTranslation").asText()).isEqualTo("译文");
        assertThat(n.has("titleTranslation")).as("workTitle 为 null 不写").isFalse();
        assertThat(n.has("illustComment")).as("illustComment == description 不写").isFalse();
        assertThat(n.path("pages").get(0).path("width").asInt()).isEqualTo(1640);
        assertThat(n.path("pages").get(0).path("height").asInt()).isEqualTo(2360);
        assertThat(n.path("pages").get(0).path("original").asText()).contains("x_p0.jpg");

        JsonNode raw = doc.path("raw");
        assertThat(raw.has("bookmarkCount")).isFalse();
        assertThat(raw.has("likeCount")).isFalse();
        assertThat(raw.has("viewCount")).isFalse();
        assertThat(raw.has("commentCount")).isFalse();
        assertThat(raw.has("bookmarkData")).isFalse();
        assertThat(raw.has("likeData")).isFalse();
        assertThat(raw.has("userIllusts")).isFalse();
        assertThat(raw.has("zoneConfig")).isFalse();
        assertThat(raw.has("extraData")).isFalse();
        // A+B 原样保留（含长尾 restrict/alt）
        assertThat(raw.path("description").asText()).isEqualTo("d");
        assertThat(raw.path("isOriginal").asBoolean()).isFalse();
        assertThat(raw.path("restrict").asInt()).isEqualTo(0);
        assertThat(raw.path("alt").asText()).isEqualTo("a");
    }

    @Test
    @DisplayName("插画：illustComment 与 description 不同才写入 normalized")
    void shouldKeepIllustCommentWhenDiffers() {
        JsonNode illust = json("{\"description\":\"d\",\"illustComment\":\"作者另写的评论\"}");
        CuratedWorkMeta c = curator.curateArtwork(1L, illust, null, "schedule");
        assertThat(c.document().path("normalized").path("illustComment").asText()).isEqualTo("作者另写的评论");
    }

    @Test
    @DisplayName("小说：额外剥正文 content 与内嵌图 textEmbeddedImages，保留其余 A+B + 列投影")
    void shouldCurateNovel() {
        JsonNode novel = json("""
                {"title":"n","description":"desc","content":"很长的正文……",
                 "textEmbeddedImages":{"1":{"urls":{"original":"u"}}},
                 "uploadDate":"2026-06-06T21:27:00+00:00","isOriginal":true,
                 "bookmarkCount":10,"likeData":false}""");

        CuratedWorkMeta c = curator.curateNovel(5L, novel, "schedule");

        assertThat(c.uploadTime()).isEqualTo(epoch("2026-06-06T21:27:00+00:00"));
        assertThat(c.isOriginal()).isTrue();

        JsonNode raw = c.document().path("raw");
        assertThat(raw.has("content")).as("正文已在 raw_content，剥除").isFalse();
        assertThat(raw.has("textEmbeddedImages")).as("内嵌图已在 novel_images，剥除").isFalse();
        assertThat(raw.has("bookmarkCount")).isFalse();
        assertThat(raw.has("likeData")).isFalse();
        assertThat(raw.path("description").asText()).isEqualTo("desc");
        assertThat(c.document().path("workType").asText()).isEqualTo("NOVEL");
    }

    @Test
    @DisplayName("超大文档触发总上限：拒绝整份 sidecar（无 document），但列投影仍有效")
    void shouldRejectSidecarWhenOversized() {
        String big = "x".repeat(300_000);
        JsonNode illust = json("{\"uploadDate\":\"2026-06-06T21:27:00+00:00\",\"isOriginal\":true,"
                + "\"description\":\"" + big + "\"}");

        CuratedWorkMeta c = curator.curateArtwork(1L, illust, null, "schedule");

        assertThat(c.hasDocument()).as("超总大小上限：不产出半成品 sidecar").isFalse();
        assertThat(c.document()).as("被拒时 document 为 null，调用方据此跳过落盘").isNull();
        // 列投影（可重建）仍随返回值带出
        assertThat(c.uploadTime()).isEqualTo(epoch("2026-06-06T21:27:00+00:00"));
        assertThat(c.isOriginal()).isTrue();
    }

    @Test
    @DisplayName("小说：仅 uploadTimestamp（epoch 毫秒）时 upload_time 写入 normalized 与列投影")
    void shouldCurateNovelUploadTimestampMillis() {
        long millis = epoch("2026-06-06T21:27:00+00:00");
        JsonNode novel = json("{\"title\":\"n\",\"uploadTimestamp\":" + millis + "}");

        CuratedWorkMeta c = curator.curateNovel(5L, novel, "schedule");

        assertThat(c.uploadTime()).isEqualTo(millis);
        assertThat(c.document().path("normalized").path("uploadTime").asLong()).isEqualTo(millis);
    }

    @Test
    @DisplayName("小说：uploadTimestamp 为 epoch 秒时换算成毫秒")
    void shouldCurateNovelUploadTimestampSeconds() {
        long millis = epoch("2026-06-06T21:27:00+00:00");
        long seconds = millis / 1000L;
        JsonNode novel = json("{\"title\":\"n\",\"uploadTimestamp\":" + seconds + "}");

        CuratedWorkMeta c = curator.curateNovel(5L, novel, "schedule");

        assertThat(c.uploadTime()).isEqualTo(seconds * 1000L);
    }

    @Test
    @DisplayName("小说：uploadTimestamp 为 ISO 字符串时正确解析")
    void shouldCurateNovelUploadTimestampIso() {
        String iso = "2026-06-06T21:27:00+00:00";
        JsonNode novel = json("{\"title\":\"n\",\"uploadTimestamp\":\"" + iso + "\"}");

        CuratedWorkMeta c = curator.curateNovel(5L, novel, "schedule");

        assertThat(c.uploadTime()).isEqualTo(epoch(iso));
    }

    @Test
    @DisplayName("小说：uploadTimestamp 为非法字符串时 upload_time 为空（绝不退化成 0）")
    void shouldNotCoerceIllegalUploadTimestampToZero() {
        JsonNode novel = json("{\"title\":\"n\",\"uploadTimestamp\":\"not-a-date\"}");

        CuratedWorkMeta c = curator.curateNovel(5L, novel, "schedule");

        assertThat(c.uploadTime()).isNull();
        assertThat(c.document().path("normalized").has("uploadTime")).isFalse();
    }

    @Test
    @DisplayName("小说：非正 uploadTimestamp 保持无效值语义")
    void shouldRejectNonPositiveUploadTimestamp() {
        CuratedWorkMeta zero = curator.curateNovel(
                5L, json("{\"title\":\"n\",\"uploadTimestamp\":0}"), "schedule");
        CuratedWorkMeta negative = curator.curateNovel(
                5L, json("{\"title\":\"n\",\"uploadTimestamp\":-1}"), "schedule");

        assertThat(zero.uploadTime()).isNull();
        assertThat(negative.uploadTime()).isNull();
        assertThat(zero.document().path("normalized").has("uploadTime")).isFalse();
        assertThat(negative.document().path("normalized").has("uploadTime")).isFalse();
    }

    @Test
    @DisplayName("小说：uploadDate 优先，存在时不被 uploadTimestamp 覆盖")
    void shouldPreferUploadDateOverTimestamp() {
        JsonNode novel = json("{\"title\":\"n\",\"uploadDate\":\"2026-06-06T21:27:00+00:00\","
                + "\"uploadTimestamp\":111}");

        CuratedWorkMeta c = curator.curateNovel(5L, novel, "schedule");

        assertThat(c.uploadTime()).isEqualTo(epoch("2026-06-06T21:27:00+00:00"));
    }

    @Test
    @DisplayName("插画：uploadTimestamp 是小说专属兼容，不影响插画路径")
    void shouldNotReadUploadTimestampForArtwork() {
        JsonNode illust = json("{\"illustTitle\":\"t\",\"uploadTimestamp\":1749245220000}");

        CuratedWorkMeta c = curator.curateArtwork(1L, illust, null, "schedule");

        assertThat(c.uploadTime()).as("插画只读 uploadDate/createDate，不读 uploadTimestamp").isNull();
    }
}
