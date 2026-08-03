package top.sywyar.pixivdownload.download.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.download.LayoutFeedbackIdentityDeriver;
import top.sywyar.pixivdownload.download.request.LayoutFeedbackCommandRequest;
import top.sywyar.pixivdownload.download.response.LayoutFeedbackStateResponse;
import top.sywyar.pixivdownload.download.state.LayoutFeedbackStateSnapshot;
import top.sywyar.pixivdownload.download.state.LayoutFeedbackStateStore;
import top.sywyar.pixivdownload.setup.ApplicationModeProvider;
import top.sywyar.pixivdownload.setup.InstallIdentityProvider;

import java.io.IOException;

/**
 * 布局偏好调查的服务端状态端点：{@code GET /api/layout-feedback/state?surveyId=...} 返回
 * 调查作用域匿名身份与去重状态，{@code POST /api/layout-feedback/state} 接收动作式命令并
 * 经 revision / CAS 持久化到 {@code state/download-workbench/layout-feedback-state.json}。
 * 仅 solo 模式启用，multi 模式一律 403（不调用 InstallIdentityProvider、不读写状态文件）。
 *
 * <p>本控制器只负责模式判断、HTTP 参数、请求体大小、调用 {@link LayoutFeedbackStateStore}
 * 与返回状态码 / DTO；状态转移、校验、原子写入与损坏恢复都在 store 内完成。原始安装
 * UUID 只用于派生 scoped ID，绝不进入响应。
 */
@RestController
@RequestMapping("/api/layout-feedback/state")
public class LayoutFeedbackStateController {

    /** 命令请求体大小上限（超过直接 413，不解析、不修改文件）。 */
    public static final int MAX_COMMAND_BODY_BYTES = 16 * 1024;

    private static final ObjectReader COMMAND_READER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
            .readerFor(LayoutFeedbackCommandRequest.class);

    private final LayoutFeedbackStateStore store;
    private final ApplicationModeProvider applicationModeProvider;
    private final InstallIdentityProvider installIdentityProvider;

    public LayoutFeedbackStateController(LayoutFeedbackStateStore store,
                                         ApplicationModeProvider applicationModeProvider,
                                         InstallIdentityProvider installIdentityProvider) {
        this.store = store;
        this.applicationModeProvider = applicationModeProvider;
        this.installIdentityProvider = installIdentityProvider;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LayoutFeedbackStateResponse> getState(
            @RequestParam("surveyId") String surveyId) {
        if (!isSolo()) {
            return ResponseEntity.status(403).build();
        }
        if (!LayoutFeedbackIdentityDeriver.isValidSurveyId(surveyId)) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(buildResponse(store.snapshot(), surveyId));
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LayoutFeedbackStateResponse> saveState(
            @RequestBody byte[] body,
            @RequestParam("surveyId") String surveyId) throws IOException {
        if (!isSolo()) {
            return ResponseEntity.status(403).build();
        }
        if (!LayoutFeedbackIdentityDeriver.isValidSurveyId(surveyId)) {
            return ResponseEntity.badRequest().build();
        }
        if (body.length > MAX_COMMAND_BODY_BYTES) {
            return ResponseEntity.status(413).build();
        }
        LayoutFeedbackCommandRequest request;
        try {
            request = COMMAND_READER.readValue(body);
        } catch (JsonProcessingException e) {
            // 非 JSON / 未知字段 / 类型错误 / 命令字段非法一律 400，不静默忽略。
            return ResponseEntity.badRequest().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
        if (store.degraded()) {
            return ResponseEntity.status(503).build();
        }
        LayoutFeedbackStateStore.ApplyResult result;
        try {
            result = store.apply(request, System.currentTimeMillis());
        } catch (IllegalStateException e) {
            // degraded 竞态：写入被拒绝。
            return ResponseEntity.status(503).build();
        } catch (IOException e) {
            // 原子写入失败：保留旧内存状态，本次请求失败。
            return ResponseEntity.status(503).build();
        }
        LayoutFeedbackStateResponse response = buildResponse(result.snapshot(), surveyId);
        if (result.status() == LayoutFeedbackStateStore.ApplyStatus.CONFLICT) {
            return ResponseEntity.status(409).body(response);
        }
        return ResponseEntity.ok(response);
    }

    private boolean isSolo() {
        return "solo".equals(applicationModeProvider.getMode());
    }

    private LayoutFeedbackStateResponse buildResponse(LayoutFeedbackStateSnapshot snapshot,
                                                      String surveyId) {
        String scopedIdentity = LayoutFeedbackIdentityDeriver.deriveScopedIdentity(
                surveyId, installIdentityProvider.get());
        var state = snapshot.state() != null && surveyId.equals(snapshot.state().surveyId())
                ? snapshot.state()
                : null;
        return new LayoutFeedbackStateResponse(
                true,
                !store.degraded(),
                scopedIdentity,
                snapshot.revision(),
                state,
                snapshot.seen());
    }
}
