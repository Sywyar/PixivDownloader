package top.sywyar.pixivdownload.download.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.core.pixiv.PixivDescriptionHtml;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("插画元数据说明在下载保存前保持实体语义")
class PixivDescriptionPipelineTest {
    @Test
    @DisplayName("代理规范化后的说明可再次安全清理，转发快照保留原文")
    void preservesDescriptionAndRawSnapshot() throws Exception {
        var mapper = new ObjectMapper();
        String raw = "A&#39;s note&#44; &quot;quoted&quot; &amp;#39;";
        var body = mapper.createObjectNode().put("illustTitle", "Title &#39;").put("description", raw);
        var response = PixivProxyResponseMapper.artworkMeta(mapper, body);
        assertThat(PixivDescriptionHtml.normalizeLinks(response.description()))
                .isEqualTo("A's note, \"quoted\" &amp;#39;");
        assertThat(response.illustTitle()).isEqualTo("Title &#39;");
        assertThat(mapper.readTree(response.rawMetaJson()).path("description").asText()).isEqualTo(raw);
        assertThat(body.path("description").asText()).isEqualTo(raw);
    }
}
