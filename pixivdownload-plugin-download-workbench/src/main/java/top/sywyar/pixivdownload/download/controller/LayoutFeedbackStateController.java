package top.sywyar.pixivdownload.download.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * 布局偏好调查的服务端状态端点：{@code GET /api/layout-feedback/state?surveyId=...} 返回
 * 调查作用域匿名身份与去重状态，{@code POST /api/layout-feedback/state} 接收动作式命令并
 * 经 revision / CAS 持久化到 {@code state/download-workbench/layout-feedback-state.json}。
 * 仅 solo 模式启用，multi 模式一律 403（不调用 InstallIdentityProvider、不读取 body、
 * 不触发 Store 加载、不读写状态文件）。
 *
 * <p>POST 处理顺序固定为：模式检查 → query surveyId 校验 → Content-Length 前置限制 →
 * 有界读取 body → 严格 JSON 解析 → DTO 校验 → query / body surveyId 一致性 → Store
 * degraded 检查 → Store apply → 200 / 409。请求体在完整读取前受
 * {@link #MAX_COMMAND_BODY_BYTES} 限制（chunked 流最多读 MAX+1），绝不使用
 * {@code readAllBytes()} 读取无界请求体。
 *
 * <p>全部响应携带 {@code Cache-Control: no-store, private}：scoped distinct ID / revision /
 * submitted·never·snoozed / seen 一律不得被代理或浏览器缓存。原始安装 UUID 只用于派生
 * scoped ID，绝不进入响应。
 */
@RestController
@RequestMapping("/api/layout-feedback/state")
public class LayoutFeedbackStateController {

    /** 命令请求体大小上限（超过直接 413，不解析、不修改文件）。 */
    public static final int MAX_COMMAND_BODY_BYTES = 16 * 1024;

    private static final int READ_BUFFER_BYTES = 1024;
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
            return statusResponse(HttpStatus.FORBIDDEN);
        }
        if (!LayoutFeedbackIdentityDeriver.isValidSurveyId(surveyId)) {
            return statusResponse(HttpStatus.BAD_REQUEST);
        }
        return jsonResponse(HttpStatus.OK, buildResponse(store.snapshot(), surveyId));
    }

    @PostMapping(
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<LayoutFeedbackStateResponse> saveState(
            HttpServletRequest request,
            @RequestParam("surveyId") String surveyId) {
        // 1. 模式检查：multi 在任何 Store / 身份 / body 读取之前返回 403。
        if (!isSolo()) {
            return statusResponse(HttpStatus.FORBIDDEN);
        }
        // 2. 校验 query surveyId。
        if (!LayoutFeedbackIdentityDeriver.isValidSurveyId(surveyId)) {
            return statusResponse(HttpStatus.BAD_REQUEST);
        }
        // 3-4. 请求体前置限制 + 有界读取（Content-Length 声明超限立即 413，不读取 body）。
        byte[] body;
        try {
            body = readBoundedBody(request);
        } catch (BodyTooLargeException e) {
            return statusResponse(HttpStatus.PAYLOAD_TOO_LARGE);
        } catch (IOException e) {
            // body 读取 I/O 失败：客户端请求错误，不返回异常内容、不修改状态。
            return statusResponse(HttpStatus.BAD_REQUEST);
        }
        // 5. 严格解析 JSON（非 JSON / 未知字段 / 类型错误一律 400）。
        LayoutFeedbackCommandRequest commandRequest;
        try {
            commandRequest = COMMAND_READER.readValue(body);
        } catch (JsonProcessingException e) {
            return statusResponse(HttpStatus.BAD_REQUEST);
        } catch (IOException e) {
            return statusResponse(HttpStatus.BAD_REQUEST);
        } catch (IllegalArgumentException e) {
            return statusResponse(HttpStatus.BAD_REQUEST);
        }
        // 7. query surveyId 必须与请求体 surveyId 完全一致（精确字符串相等，不 trim、不忽略大小写）。
        if (!surveyId.equals(commandRequest.surveyId())) {
            return statusResponse(HttpStatus.BAD_REQUEST);
        }
        // 8. Store degraded：写入被拒绝。
        if (store.degraded()) {
            return statusResponse(HttpStatus.SERVICE_UNAVAILABLE);
        }
        // 9. Store apply（CAS：APPLIED → 200，CONFLICT → 409，均带当前完整快照）。
        LayoutFeedbackStateStore.ApplyResult result;
        try {
            result = store.apply(commandRequest, System.currentTimeMillis());
        } catch (IllegalStateException e) {
            // degraded 竞态：写入被拒绝。
            return statusResponse(HttpStatus.SERVICE_UNAVAILABLE);
        } catch (IOException e) {
            // 原子写入失败：保留旧内存状态，本次请求失败。
            return statusResponse(HttpStatus.SERVICE_UNAVAILABLE);
        }
        LayoutFeedbackStateResponse response = buildResponse(result.snapshot(), surveyId);
        if (result.status() == LayoutFeedbackStateStore.ApplyStatus.CONFLICT) {
            return jsonResponse(HttpStatus.CONFLICT, response);
        }
        return jsonResponse(HttpStatus.OK, response);
    }

    private boolean isSolo() {
        return "solo".equals(applicationModeProvider.getMode());
    }

    /**
     * 受控读取请求体：
     * <ol>
     *   <li>先检查 {@code Content-Length}；声明长度大于上限时立即 413，不读取 body；</li>
     *   <li>Content-Length 缺失 / 为 -1（chunked）时从 {@link ServletInputStream} 以固定
     *       小 buffer 分块读取，累计超过上限立即停止并 413（最多读 MAX+1，不使用
     *       {@code readAllBytes()}）；</li>
     *   <li>空 body 返回空数组（由调用方映射 400）；读取 I/O 失败抛
     *       {@link IOException}（由调用方映射 400）。</li>
     * </ol>
     */
    private byte[] readBoundedBody(HttpServletRequest request) throws IOException {
        long declared = request.getContentLengthLong();
        if (declared > MAX_COMMAND_BODY_BYTES) {
            throw new BodyTooLargeException();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[READ_BUFFER_BYTES];
        try (ServletInputStream in = request.getInputStream()) {
            while (true) {
                int remainingBudget = MAX_COMMAND_BODY_BYTES + 1 - out.size();
                if (remainingBudget <= 0) {
                    throw new BodyTooLargeException();
                }
                int read = in.read(buffer, 0, Math.min(buffer.length, remainingBudget));
                if (read == -1) {
                    break;
                }
                out.write(buffer, 0, read);
                if (out.size() > MAX_COMMAND_BODY_BYTES) {
                    throw new BodyTooLargeException();
                }
            }
        }
        return out.toByteArray();
    }

    private static final class BodyTooLargeException extends RuntimeException {
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

    /** 带 JSON body 的统一响应：一律 no-store。 */
    private static ResponseEntity<LayoutFeedbackStateResponse> jsonResponse(
            HttpStatus status, LayoutFeedbackStateResponse body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl(CacheControl.noStore().cachePrivate());
        return ResponseEntity.status(status).headers(headers).body(body);
    }

    /** 无 body 的统一响应（400 / 403 / 413 / 503）：一律 no-store。 */
    private static ResponseEntity<LayoutFeedbackStateResponse> statusResponse(HttpStatus status) {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl(CacheControl.noStore().cachePrivate());
        return ResponseEntity.status(status).headers(headers).build();
    }
}
