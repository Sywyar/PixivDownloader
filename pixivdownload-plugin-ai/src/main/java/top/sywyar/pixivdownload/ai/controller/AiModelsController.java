package top.sywyar.pixivdownload.ai.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.ai.AiClientException;
import top.sywyar.pixivdownload.ai.OpenAiCompatibleAiClient;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;

/** GUI 配置页按当前未保存表单设置读取服务实际可用模型的本机端点。 */
@RestController
@RequestMapping("/api/gui")
@RequiredArgsConstructor
public class AiModelsController {

    private final OpenAiCompatibleAiClient aiClient;

    @PostMapping("/ai-models")
    public ResponseEntity<?> models(@RequestBody AiTestRequest body,
                                    HttpServletRequest request) {
        if (!AiTestController.trustedLocalRequest(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiErrorResponse.of("auth.local-only", "Forbidden: local access only"));
        }
        try {
            return ResponseEntity.ok(AiModelsResponse.ok(
                    aiClient.listModels(body == null ? null : body.toClientSettings())
            ));
        } catch (AiClientException e) {
            return ResponseEntity.ok(AiModelsResponse.fail(e.getMessage()));
        }
    }
}
