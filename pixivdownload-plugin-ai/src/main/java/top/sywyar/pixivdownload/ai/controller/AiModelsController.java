package top.sywyar.pixivdownload.ai.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientResponseException;
import top.sywyar.pixivdownload.ai.AiClientException;
import top.sywyar.pixivdownload.ai.OpenAiCompatibleAiClient;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;

/** GUI 配置页按当前未保存表单设置读取服务实际可用模型的本机端点。 */
@RestController
@RequiredArgsConstructor
public class AiModelsController {

    private final OpenAiCompatibleAiClient aiClient;

    @PostMapping("/api/gui/ai-models")
    public ResponseEntity<?> models(@RequestBody AiTestRequest body,
                                    HttpServletRequest request) {
        if (!AiTestController.trustedLocalRequest(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiErrorResponse.of("auth.local-only", "Forbidden: local access only"));
        }
        return modelsSettings(body);
    }

    /** 管理员入口使用临时设置；身份与 CSRF 由声明路由统一校验。 */
    @PostMapping("/api/admin/ai/models")
    public ResponseEntity<?> modelsAdmin(@top.sywyar.pixivdownload.plugin.api.web.RequestBodyLimit(64 * 1024) @RequestBody AiTestRequest body) {
        return modelsSettings(body);
    }

    private ResponseEntity<?> modelsSettings(AiTestRequest body) {
        try {
            return ResponseEntity.ok(AiModelsResponse.ok(
                    aiClient.listModels(body == null ? null : body.toClientSettings())
            ));
        } catch (AiClientException e) {
            String code = "models-query-failed";
            if (e.getCause() instanceof RestClientResponseException response) {
                code = switch (response.getStatusCode().value()) {
                    case 401 -> body == null || body.apiKey() == null || body.apiKey().isBlank()
                            ? "api-key-required" : "authentication-failed";
                    case 403 -> "models-forbidden";
                    default -> code;
                };
            }
            return ResponseEntity.ok(AiModelsResponse.fail(code, e.getMessage()));
        }
    }
}
