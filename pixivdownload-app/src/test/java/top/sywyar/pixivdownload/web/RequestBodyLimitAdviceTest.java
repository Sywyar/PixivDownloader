package top.sywyar.pixivdownload.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.plugin.api.web.RequestBodyLimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("插件声明的正文预算在 MVC 反序列化之前执行")
class RequestBodyLimitAdviceTest {
    @RestController
    static class Endpoint {
        int calls;
        record Input(String text) {}
        @PostMapping("/probe")
        Input invoke(@RequestBodyLimit(32) @RequestBody Input input) { calls++; return input; }
    }

    @Test
    @DisplayName("正常 DTO 保持转换，已知长度及无长度的超限请求均拒绝且不调用业务")
    void boundsBothLengthModes() throws Exception {
        var endpoint = new Endpoint();
        var mvc = MockMvcBuilders.standaloneSetup(endpoint)
                .setControllerAdvice(new RequestBodyLimitAdvice(TestI18nBeans.appMessages())).build();
        mvc.perform(post("/probe").contentType("application/json").content("{\"text\":\"ok\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.text").value("ok"));
        String oversized = "{\"text\":\"" + "x".repeat(33) + "\"}";
        mvc.perform(post("/probe").contentType("application/json").content(oversized))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("HTTP_BODY_LIMIT"));
        mvc.perform(post("/probe").contentType("application/json").content(oversized)
                        .with(request -> { request.removeHeader("Content-Length"); request.addHeader("Content-Length", "-1"); return request; }))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("HTTP_BODY_LIMIT"));
        assertThat(endpoint.calls).isEqualTo(1);
    }
}
