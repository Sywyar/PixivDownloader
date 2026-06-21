package top.sywyar.pixivdownload.ai.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.ai.AiClientSettings;
import top.sywyar.pixivdownload.ai.AiService;
import top.sywyar.pixivdownload.ai.model.AiChatOptions;
import top.sywyar.pixivdownload.ai.model.AiChatResult;
import top.sywyar.pixivdownload.ai.probe.ConnectivityProbeRequest;
import top.sywyar.pixivdownload.common.NetworkUtils;

/**
 * GUI 配置页"测试 AI 连接"按钮对应的 REST 端点。
 * <p>
 * 路径 {@code /api/gui/ai-test} 由 {@code AuthFilter} 对 {@code /api/gui/**} 统一施加"本地请求 + GUI token"
 * 双校验；这里再次显式调用 {@link NetworkUtils#isTrustedLocalRequest} 作为深度防御。
 * <p>
 * 接收 GUI 当前表单的 AI 设置 DTO（含 API Key，仅在 localhost 同进程内传递），用一条极短的探测 prompt 调用
 * {@link AiService#chatTest}，把模型回复摘录 / 失败摘要原样回给 GUI；失败摘要绝不含 API Key。
 */
@RestController
@RequestMapping("/api/gui")
@RequiredArgsConstructor
@Slf4j
public class AiTestController {

    /** 连通性探测请求实体：固定提示词，要求模型只回一个词，尽量少消耗 token。 */
    private static final ConnectivityProbeRequest PROBE = new ConnectivityProbeRequest();

    /** 回显给 GUI 的模型回复最多保留的字符数。 */
    private static final int MAX_REPLY_LENGTH = 200;

    private final AiService aiService;

    @PostMapping("/ai-test")
    public ResponseEntity<AiTestResponse> test(@RequestBody AiTestRequest body,
                                               HttpServletRequest request) {
        if (!NetworkUtils.isTrustedLocalRequest(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (body == null) {
            return ResponseEntity.badRequest().body(AiTestResponse.fail("missing settings"));
        }

        AiClientSettings settings = body.toClientSettings();
        try {
            AiChatResult result = aiService.chatTest(ConnectivityProbeRequest.CALL_TYPE,
                    settings, PROBE.toMessages(), AiChatOptions.defaults());
            return ResponseEntity.ok(AiTestResponse.ok(truncate(result.content())));
        } catch (AiService.AiException e) {
            return ResponseEntity.ok(AiTestResponse.fail(e.getMessage()));
        }
    }

    private static String truncate(String reply) {
        if (reply == null) {
            return "";
        }
        String oneLine = reply.replaceAll("\\s+", " ").trim();
        return oneLine.length() > MAX_REPLY_LENGTH ? oneLine.substring(0, MAX_REPLY_LENGTH) + "…" : oneLine;
    }
}
