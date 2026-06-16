package top.sywyar.pixivdownload.novel.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 小说下载旧址兼容垫片：验证历史 {@code /api/download/**} 小说路径服务端 forward 到新址 {@code /api/novel/**}。
 * <p>
 * 用服务端 forward（非 301/302）转发：对 {@code POST} 透明保留请求方法与请求体（forward 不消费请求体、
 * 由目标控制器读取），forwardedUrl 即落到新址；油猴脚本走旧址因此无需改动即可命中迁移后的端点。
 */
@DisplayName("小说下载旧址兼容垫片 forward 至新址")
class NovelDownloadLegacyForwardControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new NovelDownloadLegacyForwardController()).build();
    }

    @Test
    @DisplayName("POST /api/download/pixiv/novel → forward /api/novel/download（保留 POST + 请求体）")
    void forwardsDownloadPostWithBody() throws Exception {
        mockMvc.perform(post("/api/download/pixiv/novel")
                        .contentType("application/json")
                        .content("{\"novelId\":12345}"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/api/novel/download"));
    }

    @Test
    @DisplayName("GET /api/download/novel/status/{id} → forward /api/novel/status/{id}")
    void forwardsStatus() throws Exception {
        mockMvc.perform(get("/api/download/novel/status/12345"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/api/novel/status/12345"));
    }

    @Test
    @DisplayName("GET /api/download/novel/translate-status/{id} → forward /api/novel/translate-status/{id}")
    void forwardsTranslateStatus() throws Exception {
        mockMvc.perform(get("/api/download/novel/translate-status/12345"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/api/novel/translate-status/12345"));
    }
}
