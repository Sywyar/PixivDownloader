package top.sywyar.pixivdownload.download.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.sywyar.pixivdownload.download.state.DownloadPagePreference;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.plugin.api.storage.RuntimePathProvider;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("下载页面选择与服务端重定向")
class DownloadPageControllerTest {
    @TempDir Path directory;
    private RuntimePathProvider paths;
    private DownloadPagePreference preference;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        paths = mock(RuntimePathProvider.class);
        when(paths.stateDirectory()).thenReturn(directory);
        preference = new DownloadPagePreference(paths);
        MessageResolver messages = mock(MessageResolver.class);
        when(messages.get("error.request.param.invalid")).thenReturn("Invalid parameter");
        mvc = MockMvcBuilders.standaloneSetup(new DownloadPageController(preference, messages)).build();
    }

    @Test
    @DisplayName("默认新版，显式保存后两入口按同一偏好跳转并保留查询参数")
    void navigationFollowsPersistedSelection() throws Exception {
        mvc.perform(get("/pixiv-batch-alt.html")).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"abRail\"")))
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get("/pixiv-batch.html?lang=ja-JP&tab=schedule"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/pixiv-batch-alt.html?lang=ja-JP&tab=schedule"));
        mvc.perform(post("/api/batch/page").contentType(MediaType.APPLICATION_JSON)
                .content("{\"page\":\"pixiv-batch.html\"}")).andExpect(status().isNoContent());
        assertThat(new DownloadPagePreference(paths).currentPage()).isEqualTo(DownloadPagePreference.CLASSIC);
        mvc.perform(get(java.net.URI.create("/pixiv-batch-alt.html?tab=schedule&query=%E7%94%BB%26")))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/pixiv-batch.html?tab=schedule&query=%E7%94%BB%26"))
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get("/pixiv-batch.html")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"s-file-name-templates\"")));
        mvc.perform(post("/api/batch/page").contentType(MediaType.APPLICATION_JSON)
                .content("{\"page\":\"pixiv-batch-alt.html\"}")).andExpect(status().isNoContent());
        assertThat(new DownloadPagePreference(paths).currentPage()).isEqualTo(DownloadPagePreference.ALTERNATE);
    }

    @Test
    @DisplayName("访问页面不改写偏好，拒绝非白名单地址与空值")
    void requestsCannotSelectArbitraryTargets() throws Exception {
        preference.save(DownloadPagePreference.ALTERNATE);
        for (String body : new String[]{"{}", "{\"page\":null}", "{\"page\":\"//evil.example/\"}",
                "{\"page\":\"../pixiv-batch.html\"}", "{\"page\":\"pixiv-batch.html?layout=classic\"}"}) {
            mvc.perform(post("/api/batch/page").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("error.request.param.invalid"))
                    .andExpect(jsonPath("$.error").value("Invalid parameter"));
        }
        mvc.perform(get("/pixiv-batch.html?page=pixiv-batch.html")).andExpect(status().isFound());
        assertThat(new DownloadPagePreference(paths).currentPage()).isEqualTo(DownloadPagePreference.ALTERNATE);
    }

    @Test
    @DisplayName("损坏或超长偏好回退新版，写盘失败传播且清理临时文件")
    void corruptedStateAndFailedWrites() throws Exception {
        Path file = directory.resolve("download_page.txt");
        for (String value : new String[]{"", "pixiv-batch-alt.html\n", "x".repeat(4096)}) {
            Files.writeString(file, value, StandardCharsets.UTF_8);
            assertThat(preference.currentPage()).isEqualTo(DownloadPagePreference.ALTERNATE);
        }
        assertThatThrownBy(() -> preference.save("invalid")).isInstanceOf(IllegalArgumentException.class);
        Files.delete(file);
        Files.createDirectory(file);
        Files.writeString(file.resolve("occupied"), "fixture", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> preference.save(DownloadPagePreference.ALTERNATE))
                .isInstanceOf(java.io.IOException.class);
        try (var entries = Files.list(directory)) {
            assertThat(entries.toList()).containsExactly(file);
        }
    }
}
