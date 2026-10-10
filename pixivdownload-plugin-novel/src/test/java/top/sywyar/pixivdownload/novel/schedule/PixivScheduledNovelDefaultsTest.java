package top.sywyar.pixivdownload.novel.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskDefinition;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskPresentation;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PixivScheduledNovelDefaultsTest {
    @Test
    @DisplayName("小说缺省任务与同源默认值快照具有相同执行设置")
    void defaultsMatchExplicitSnapshot() throws Exception {
        var mapper = new ObjectMapper();
        var defaults = mapper.readTree(Files.readString(Path.of("../scripts/schedule/pixiv-defaults.json")));
        var root = mapper.createObjectNode().put("kind", "novel");
        var original = PixivScheduledNovelDefinition.parse(mapper, definition(root.toString()));
        root.set("download", defaults.get("download"));
        root.set("filters", defaults.get("filters"));
        var explicit = PixivScheduledNovelDefinition.parse(mapper, definition(root.toString()));
        assertThat(explicit).usingRecursiveComparison()
                .ignoringFields("download.novelTranslateSegmentSize").isEqualTo(original);
        assertThat(explicit.download().novelTranslateSegmentSize())
                .isEqualTo(PixivScheduleDefaults.DOWNLOAD_NOVEL_TRANSLATE_SEGMENT_SIZE);
    }

    private static ScheduledTaskDefinition definition(String json) {
        return new ScheduledTaskDefinition(1L, "search", PixivScheduledNovelDefinition.SCHEMA,
                PixivScheduledNovelDefinition.VERSION, json, ScheduledTaskPresentation.empty());
    }
}
