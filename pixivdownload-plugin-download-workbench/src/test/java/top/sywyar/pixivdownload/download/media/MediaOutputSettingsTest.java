package top.sywyar.pixivdownload.download.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.download.request.DownloadRequest;
import top.sywyar.pixivdownload.download.schedule.snapshot.ScheduleTaskSnapshot;
import top.sywyar.pixivdownload.download.schedule.source.definition.PixivScheduledDefinitionValidator;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;

import static org.junit.jupiter.api.Assertions.*;

class MediaOutputSettingsTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String OPTIONS = """
            {"imageFormats":"png,webp","ugoiraFormats":"zip,webp","mediaQuality":73,
             "mediaWebpLossless":true,"mediaMaximumEdge":1280}
            """;

    @Test
    @DisplayName("请求输出选项隔离且非管理员不能开启自定义编码")
    void requestOptionsAreIsolatedAndRespectTrustedFlag() throws Exception {
        var request = mapper.readValue(OPTIONS, DownloadRequest.Other.class);
        var settings = request.resolveMediaOutputSettings();
        assertEquals("png,webp", settings.getImageFormats());
        assertEquals("zip,webp", settings.getUgoiraFormats());
        assertEquals(73, settings.getQuality());
        assertTrue(settings.isWebpLossless());
        assertEquals(1280, settings.getMaximumEdge());
        assertEquals(73, mapper.readValue(mapper.writeValueAsString(request), DownloadRequest.Other.class).getMediaQuality());
        request.setMediaQuality(30);
        assertEquals(73, settings.getQuality());
        request.setMediaOutputEnabled(false);
        var defaults = new MediaOutputSettings();
        var restricted = request.resolveMediaOutputSettings();
        assertEquals(defaults.getImageFormats(), restricted.getImageFormats());
        assertEquals(defaults.getUgoiraFormats(), restricted.getUgoiraFormats());
        assertEquals(defaults.getQuality(), restricted.getQuality());
        assertEquals(defaults.getMaximumEdge(), restricted.getMaximumEdge());
        assertEquals(defaults.isWebpLossless(), restricted.isWebpLossless());
        assertFalse(mapper.writeValueAsString(request).contains("mediaOutputEnabled"));
    }

    @Test
    @DisplayName("计划保存和恢复保留全部输出参数并拒绝无效值")
    void scheduledSnapshotValidatesAllMediaOptions() throws Exception {
        var root = mapper.readTree("{\"source\":{\"userId\":\"42\"},\"download\":" + OPTIONS + "}");
        PixivScheduledDefinitionValidator.validate(root, "user-new");
        var download = ScheduleTaskSnapshot.from(root).download();
        assertEquals("png,webp", download.imageFormats());
        assertEquals("zip,webp", download.ugoiraFormats());
        assertEquals(73, download.mediaQuality());
        assertTrue(download.mediaWebpLossless());
        assertEquals(1280, download.mediaMaximumEdge());
        for (String invalid : new String[]{"\"mediaQuality\":0", "\"mediaQuality\":101", "\"mediaQuality\":1.5",
                "\"mediaQuality\":\"70\"", "\"mediaMaximumEdge\":-1", "\"mediaMaximumEdge\":16384",
                "\"mediaMaximumEdge\":999999999999", "\"mediaWebpLossless\":\"true\"",
                "\"imageFormats\":\"\"", "\"ugoiraFormats\":\"webp,webp\""}) {
            var bad = mapper.readTree("{\"source\":{\"userId\":\"42\"},\"download\":{" + invalid + "}}");
            assertThrows(ScheduledExecutionException.class, () -> PixivScheduledDefinitionValidator.validate(bad, "user-new"), invalid);
            assertThrows(IllegalArgumentException.class, () -> ScheduleTaskSnapshot.from(bad), invalid);
            assertThrows(Exception.class, () -> mapper.readValue("{" + invalid + "}", DownloadRequest.Other.class).resolveMediaOutputSettings(), invalid);
        }
    }

    @Test
    @DisplayName("动图画质来自作品参数，无损压缩力度独立使用后端配置")
    void animationUsesTaskEncodingOptions() throws Exception {
        var settings = mapper.readValue(OPTIONS, DownloadRequest.Other.class).resolveMediaOutputSettings();
        var args = UgoiraEncoding.arguments("webp", settings);
        assertEquals(Integer.toString(UgoiraEncoderSettings.DEFAULT_LOSSLESS_EFFORT),
                args.get(args.indexOf("-quality") + 1));
        assertEquals("1", args.get(args.indexOf("-lossless") + 1));
        assertTrue(args.get(args.indexOf("-vf") + 1).contains("1280"));
        var custom = UgoiraEncoding.arguments("webp", settings, 100);
        assertEquals("100", custom.get(custom.indexOf("-quality") + 1));
        settings.setWebpLossless(false);
        var lossy = UgoiraEncoding.arguments("webp", settings, 100);
        assertEquals("73", lossy.get(lossy.indexOf("-quality") + 1));
    }
}
