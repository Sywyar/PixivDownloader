package top.sywyar.pixivdownload.novel.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.sywyar.pixivdownload.novel.NovelPlugin;
import top.sywyar.pixivdownload.novel.download.NovelDownloadService.NovelFormat;
import top.sywyar.pixivdownload.novel.export.NovelExportService;
import top.sywyar.pixivdownload.novel.testsupport.NovelTestMessages;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("单篇小说直接下载")
class NovelExportControllerTest {
    private final NovelExportService service = mock(NovelExportService.class);
    private final MockMvc http = MockMvcBuilders.standaloneSetup(
            new NovelExportController(service, NovelTestMessages.messageResolver())).build();

    @Test
    @DisplayName("直接返回精确版本的 EPUB 附件并保留 Unicode 文件名")
    void downloadsAttachment() throws Exception {
        byte[] content = "epub-bytes".getBytes(StandardCharsets.UTF_8);
        when(service.export(42, NovelFormat.EPUB, "zh-CN@youdao"))
                .thenReturn(new NovelExportService.Export("译文_zh-CN@youdao.epub", content));
        var response = http.perform(get("/api/novel/42/export").param("variant", "zh-CN@youdao"))
                .andExpect(status().isOk()).andExpect(content().contentType("application/epub+zip"))
                .andExpect(content().bytes(content)).andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse();
        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION))
                .startsWith("attachment;").contains("filename*=UTF-8''%E8%AF%91%E6%96%87");
        assertThat(new NovelPlugin().routes()).anySatisfy(route -> {
            assertThat(route.pathPattern()).isEqualTo("/api/novel/*/export");
            assertThat(route.accessPolicy()).isEqualTo(AccessPolicy.ADMIN);
        });
        verify(service).export(42, NovelFormat.EPUB, "zh-CN@youdao");
    }

    @Test
    @DisplayName("原文不携带译文身份；内容不存在时返回不可用")
    void handlesOriginalAndMissing() throws Exception {
        when(service.export(42, NovelFormat.EPUB, null))
                .thenReturn(new NovelExportService.Export("original.epub", new byte[]{1}));
        http.perform(get("/api/novel/42/export")).andExpect(status().isOk());
        http.perform(get("/api/novel/42/export").param("variant", "zh-CN@ai"))
                .andExpect(status().isNotFound());
        verify(service).export(42, NovelFormat.EPUB, null);
        verify(service).export(42, NovelFormat.EPUB, "zh-CN@ai");
    }

    @Test
    @DisplayName("无效输入不进入渲染；渲染失败不回显内部信息")
    void rejectsInvalidAndSanitizesFailures() throws Exception {
        http.perform(get("/api/novel/0/export")).andExpect(status().isBadRequest());
        http.perform(get("/api/novel/42/export").param("variant", "zh-CN@../secret"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
        when(service.export(42, NovelFormat.EPUB, null)).thenThrow(new IOException("private-path-secret"));
        var response = http.perform(get("/api/novel/42/export")).andExpect(status().isInternalServerError())
                .andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain("private-path-secret");
    }

    @Test
    @DisplayName("过大的附件拒绝返回，不转为后台归档")
    void rejectsOversizedDocument() throws Exception {
        when(service.export(42, NovelFormat.EPUB, null)).thenReturn(
                new NovelExportService.Export("large.epub", new byte[NovelExportController.MAX_DOCUMENT_BYTES + 1]));
        http.perform(get("/api/novel/42/export")).andExpect(status().isPayloadTooLarge());
    }
}
