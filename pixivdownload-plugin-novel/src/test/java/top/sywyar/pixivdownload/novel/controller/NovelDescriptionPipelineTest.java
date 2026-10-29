package top.sywyar.pixivdownload.novel.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.core.pixiv.PixivDescriptionHtml;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("小说元数据说明在下载保存前保持实体语义")
class NovelDescriptionPipelineTest {
    @Test
    @DisplayName("代理规范化后的说明可再次安全清理，标题与原始响应保持原样")
    void preservesDescriptionAndOriginalBody() {
        var mapper = new ObjectMapper();
        String raw = "A&#39;s note&#44; &quot;quoted&quot; &amp;#39;";
        var body = mapper.createObjectNode().put("title", "Title &#39;").put("description", raw);
        var response = NovelPixivResponseMapper.novelMeta(7, body, "fixture");
        assertThat(PixivDescriptionHtml.normalizeLinks(response.description()))
                .isEqualTo("A's note, \"quoted\" &amp;#39;");
        assertThat(response.title()).isEqualTo("Title &#39;");
        assertThat(body.path("description").asText()).isEqualTo(raw);
    }
}
