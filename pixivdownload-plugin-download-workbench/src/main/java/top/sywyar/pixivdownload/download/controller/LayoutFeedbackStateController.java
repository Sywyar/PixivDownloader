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
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.download.LayoutFeedbackIdentityDeriver;
import top.sywyar.pixivdownload.download.request.LayoutFeedbackCommandRequest;
import top.sywyar.pixivdownload.download.response.feedback.LayoutFeedbackStateResponse;
import top.sywyar.pixivdownload.download.state.LayoutFeedbackDecisionView;
import top.sywyar.pixivdownload.download.state.LayoutFeedbackRevisionExhaustedException;
import top.sywyar.pixivdownload.download.state.LayoutFeedbackStateEntry;
import top.sywyar.pixivdownload.download.state.LayoutFeedbackStateSnapshot;
import top.sywyar.pixivdownload.download.state.LayoutFeedbackStateStore;
import top.sywyar.pixivdownload.setup.ApplicationModeProvider;
import top.sywyar.pixivdownload.setup.InstallIdentityProvider;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 布局偏好调查的服务端状态端点：{@code GET /api/layout-feedback/state?surveyId=...} 返回
 * 调查作用域匿名身份、稳定提交 UUID 与权威展示视图（status / canShow / retryAfterMs / seenLayouts），
 * {@code POST /api/layout-feedback/state} 接收动作式命令并持久化到
 * {@code state/download-workbench/layout-feedback-state.json}（无 CAS：合法命令一律 200）。
 * solo 模式启用服务端状态；multi 模式的 GET 只返回安装作用域身份与稳定提交 UUID，
 * 不触发 Store 加载或状态文件读写，POST 仍在读取 body 前返回 403。
 *
 * <p>POST 处理顺序固定为：模式检查 → query surveyId 校验（缺失由 Controller 自行返回
 * 400）→ Content-Type 校验（null / 非 JSON / 非法字符串一律 415，读取 body 之前）→
 * Content-Length 前置限制 → 有界读取 body → 严格 JSON 解析（未知字段包括旧协议的
 * expectedRevision 一律 400）→ DTO 校验 → query / body surveyId 一致性 → Store degraded
 * 检查 → Store apply → 统一 200 权威视图（no-op 同样 200）。请求体在完整读取前受
 * {@link #MAX_COMMAND_BODY_BYTES} 限制（chunked 流最多读 MAX+1），绝不使用
 * {@code readAllBytes()} 读取无界请求体。
 *
 * <p>全部响应（含缺 surveyId 的 400 与错误 Content-Type 的 415，这些错误不再由 Spring
 * 在进入 Controller 前生成）携带 {@code Cache-Control: no-store, private}：scoped
 * distinct ID / submission ID / revision / status·canShow·retryAfterMs·seenLayouts 一律不得被代理或浏览器
 * 缓存。原始安装 UUID 只用于派生 scoped ID，绝不进入响应。
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
    private final Clock clock;

    public LayoutFeedbackStateController(LayoutFeedbackStateStore store,
                                         ApplicationModeProvider applicationModeProvider,
                                         InstallIdentityProvider installIdentityProvider) {
        this(store, applicationModeProvider, installIdentityProvider, Clock.systemUTC());
    }

    /** 测试可注入固定 {@link Clock}；生产构造使用 {@link Clock#systemUTC()}。 */
    LayoutFeedbackStateController(LayoutFeedbackStateStore store,
                                  ApplicationModeProvider applicationModeProvider,
                                  InstallIdentityProvider installIdentityProvider,
                                  Clock clock) {
        this.store = store;
        this.applicationModeProvider = applicationModeProvider;
        this.installIdentityProvider = installIdentityProvider;
        this.clock = clock;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LayoutFeedbackStateResponse> getState(
            @RequestParam(value = "surveyId", required = false) String surveyId) {
        if (!LayoutFeedbackIdentityDeriver.isValidSurveyId(surveyId)) {
            return statusResponse(HttpStatus.BAD_REQUEST);
        }
        if (!isSolo()) {
            return jsonResponse(HttpStatus.OK, buildIdentityOnlyResponse(surveyId));
        }
        // 同一个请求只读取一次服务端时钟；时钟源返回负值（墙钟回拨 / 异常）按 0 处理。
        // 服务端独立判断 snooze 是否到期，浏览器不参与解释任何服务端绝对时间点。
        long serverNow = Math.max(0L, clock.millis());
        return jsonResponse(HttpStatus.OK, buildResponse(store.snapshot(), surveyId, serverNow));
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LayoutFeedbackStateResponse> saveState(
            HttpServletRequest request,
            @RequestParam(value = "surveyId", required = false) String surveyId) {
        // 1. 模式检查：multi 在任何 Store / 身份 / body 读取之前返回 403。
        if (!isSolo()) {
            return statusResponse(HttpStatus.FORBIDDEN);
        }
        // 2. 校验 query surveyId（required=false 让缺失值进入 Controller，
        //    由这里统一返回带 no-store 的 400，而不是由 Spring 生成）。
        if (!LayoutFeedbackIdentityDeriver.isValidSurveyId(surveyId)) {
            return statusResponse(HttpStatus.BAD_REQUEST);
        }
        // 3. Content-Type 校验：null / 非法 / 非 JSON 一律 415（读取 body 之前）。
        if (!isJsonContentType(request.getContentType())) {
            return statusResponse(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        }
        // 4-5. 请求体前置限制 + 有界读取（Content-Length 声明超限立即 413，不读取 body）。
        byte[] body;
        try {
            body = readBoundedBody(request);
        } catch (BodyTooLargeException e) {
            return statusResponse(HttpStatus.PAYLOAD_TOO_LARGE);
        } catch (IOException e) {
            // body 读取 I/O 失败：客户端请求错误，不返回异常内容、不修改状态。
            return statusResponse(HttpStatus.BAD_REQUEST);
        }
        // 6. 严格解析 JSON（非 JSON / 未知字段 / 类型错误一律 400）。
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
        // 9. Store apply（无 CAS：合法命令一律 200，no-op 也返回 200 权威视图）。
        //    同一个请求只读取一次服务端时钟：Store apply 与响应视图使用同一个
        //    serverNow；时钟源返回负值（墙钟回拨 / 异常）按 0 处理，Store 不接收负时间。
        long serverNow = Math.max(0L, clock.millis());
        LayoutFeedbackStateStore.ApplyResult result;
        try {
            result = store.apply(commandRequest, serverNow);
        } catch (LayoutFeedbackRevisionExhaustedException e) {
            // revision 达到 JavaScript 安全整数上限后仍需真实修改：无法安全递增
            // revision（绝不回绕），本次写入失败，内存快照与文件均不变。
            return statusResponse(HttpStatus.SERVICE_UNAVAILABLE);
        } catch (IllegalStateException e) {
            // degraded 竞态：写入被拒绝。
            return statusResponse(HttpStatus.SERVICE_UNAVAILABLE);
        } catch (IOException e) {
            // 原子写入失败：保留旧内存状态，本次请求失败。
            return statusResponse(HttpStatus.SERVICE_UNAVAILABLE);
        }
        return jsonResponse(HttpStatus.OK, buildResponse(result.snapshot(), surveyId, serverNow));
    }

    /**
     * Content-Type 判定（严格使用 Spring {@link MediaType} 标准解析）：
     * - null / 空白 / 语法损坏（{@link InvalidMediaTypeException}）一律 415；
     * - wildcard type（星号斜杠星号）与 wildcard subtype（application/*、
     *   application/*+json）一律拒绝——subtype 中出现 {@code *} 的都不是可接受的
     *   具体 JSON 类型；
     * - 只接受 application/json 与具体的 application/*+json 子类型
     *   （application/problem+json、application/vnd.example+json 等，大小写不敏感）；
     * - 参数（charset 等）由 Spring 解析器严格校验：引号未闭合、空键会抛
     *   {@link InvalidMediaTypeException} 而被拒绝；Spring 对缺失 {@code =} 的参数
     *   会静默丢弃而不报错，因此由 {@link #areParametersWellFormed} 做补充校验，
     *   语法损坏的参数不得被当作合法 JSON。
     */
    private static boolean isJsonContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return false;
        }
        final MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(contentType);
        } catch (InvalidMediaTypeException e) {
            return false;
        }
        if (mediaType.isWildcardType() || mediaType.isWildcardSubtype()) {
            return false;
        }
        if (!"application".equalsIgnoreCase(mediaType.getType())) {
            return false;
        }
        String subtype = mediaType.getSubtype().toLowerCase(Locale.ROOT);
        if (subtype.indexOf('*') >= 0) {
            // application/*+json 这类 wildcard 基底不是合法具体 JSON 类型。
            return false;
        }
        if (!areParametersWellFormed(contentType)) {
            return false;
        }
        return "json".equals(subtype) || subtype.endsWith("+json");
    }

    /**
     * 参数段补充校验（字符级状态机）：Spring 的 MimeType 解析器对分号参数段是宽松的——
     * 缺失 {@code =} 的参数会被静默丢弃而不抛错（例如 {@code application/json; invalid parameter}）。
     * 这里只把引号外的分号视为参数分隔符，引号内的分号属于参数值，反斜杠转义的引号不结束
     * quoted string；每个非空参数段必须包含一个引号外的 {@code =} 且 {@code =} 左侧 key
     * trim 后非空。每个参数段在扫描过程中记录「第一个引号外的等号位置」，段结束时直接用它
     * 校验 key——绝不在段结束后重新扫描（重扫可能选中引号内的等号，例如
     * {@code profile="a=b;c=d"}）。媒体类型段（首个分号之前）不属于参数；空参数段（尾分号 /
     * 连续分号）按现有约定接受；引号未闭合返回 false。禁止使用正则 / {@code split(";")} 简单切分。
     */
    private static boolean areParametersWellFormed(String contentType) {
        int segmentStart = 0;
        int firstOuterEquals = -1;
        boolean firstSegment = true;
        boolean inQuotes = false;
        boolean escaped = false;
        boolean hasEquals = false;
        int length = contentType.length();
        for (int i = 0; i < length; i++) {
            char c = contentType.charAt(i);
            if (inQuotes) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inQuotes = false;
                }
                continue;
            }
            if (c == '"') {
                inQuotes = true;
                continue;
            }
            if (c == ';') {
                if (!firstSegment && !isWellFormedSegment(
                        contentType, segmentStart, i, hasEquals, firstOuterEquals)) {
                    return false;
                }
                firstSegment = false;
                segmentStart = i + 1;
                hasEquals = false;
                firstOuterEquals = -1;
                continue;
            }
            if (c == '=' && !hasEquals) {
                hasEquals = true;
                firstOuterEquals = i;
            }
        }
        if (inQuotes) {
            return false;
        }
        return firstSegment || isWellFormedSegment(
                contentType, segmentStart, length, hasEquals, firstOuterEquals);
    }

    /**
     * 单个参数段是否形如 {@code key=value}（key trim 后非空，以扫描中记录的
     * 第一个引号外等号为准）；空段按现有约定接受。
     */
    private static boolean isWellFormedSegment(String contentType, int segmentStart, int segmentEnd,
                                               boolean hasEquals, int firstOuterEquals) {
        if (contentType.substring(segmentStart, segmentEnd).trim().isEmpty()) {
            // 尾分号 / 连续分号产生的空参数段：与既有约定一致，接受。
            return true;
        }
        if (!hasEquals || firstOuterEquals < segmentStart || firstOuterEquals >= segmentEnd) {
            return false;
        }
        String key = contentType.substring(segmentStart, firstOuterEquals).trim();
        return !key.isEmpty();
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
                                                      String surveyId, long serverNow) {
        String scopedIdentity = LayoutFeedbackIdentityDeriver.deriveScopedIdentity(
                surveyId, installIdentityProvider.get());
        String submissionId = LayoutFeedbackIdentityDeriver.deriveSubmissionId(
                surveyId, LayoutFeedbackIdentityDeriver.CAMPAIGN_VERSION, scopedIdentity);
        LayoutFeedbackStateEntry state = snapshot.state(surveyId);
        boolean degraded = store.degraded();
        LayoutFeedbackDecisionView view = degraded
                ? new LayoutFeedbackDecisionView(null, false, 0L)
                : LayoutFeedbackDecisionView.evaluate(state, serverNow);
        List<String> seenLayouts = new ArrayList<>(LayoutFeedbackStateStore.LAYOUT_ID_ORDER.size());
        for (String layoutId : LayoutFeedbackStateStore.LAYOUT_ID_ORDER) {
            if (snapshot.seen().containsKey(layoutId)) {
                seenLayouts.add(layoutId);
            }
        }
        return new LayoutFeedbackStateResponse(
                true,
                !degraded,
                scopedIdentity,
                submissionId,
                snapshot.revision(),
                view.status(),
                view.canShow(),
                view.retryAfterMs(),
                seenLayouts);
    }

    private LayoutFeedbackStateResponse buildIdentityOnlyResponse(String surveyId) {
        String scopedIdentity = LayoutFeedbackIdentityDeriver.deriveScopedIdentity(
                surveyId, installIdentityProvider.get());
        return new LayoutFeedbackStateResponse(
                true,
                false,
                scopedIdentity,
                LayoutFeedbackIdentityDeriver.deriveSubmissionId(
                        surveyId, LayoutFeedbackIdentityDeriver.CAMPAIGN_VERSION, scopedIdentity),
                0L,
                null,
                false,
                0L,
                List.of());
    }

    /** 带 JSON body 的统一响应：一律 no-store。 */
    private static ResponseEntity<LayoutFeedbackStateResponse> jsonResponse(
            HttpStatus status, LayoutFeedbackStateResponse body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl(CacheControl.noStore().cachePrivate());
        return ResponseEntity.status(status).headers(headers).body(body);
    }

    /** 统一失败响应：保留稳定 code/error 且一律 no-store。 */
    private static ResponseEntity<LayoutFeedbackStateResponse> statusResponse(HttpStatus status) {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl(CacheControl.noStore().cachePrivate());
        String code = switch (status) {
            case BAD_REQUEST -> "layout-feedback.request.invalid";
            case FORBIDDEN -> "layout-feedback.solo-only";
            case PAYLOAD_TOO_LARGE -> "layout-feedback.request.too-large";
            case UNSUPPORTED_MEDIA_TYPE -> "layout-feedback.request.media-type-unsupported";
            default -> "layout-feedback.unavailable";
        };
        return ResponseEntity.status(status).headers(headers).body(new LayoutFeedbackStateResponse(
                false, false, null, null, 0L, null, false, 0L, List.of(),
                code, status.getReasonPhrase()));
    }
}
