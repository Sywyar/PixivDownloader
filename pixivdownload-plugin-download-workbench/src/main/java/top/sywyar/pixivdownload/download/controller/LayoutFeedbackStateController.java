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
import top.sywyar.pixivdownload.download.response.LayoutFeedbackStateResponse;
import top.sywyar.pixivdownload.download.state.LayoutFeedbackStateSnapshot;
import top.sywyar.pixivdownload.download.state.LayoutFeedbackStateStore;
import top.sywyar.pixivdownload.setup.ApplicationModeProvider;
import top.sywyar.pixivdownload.setup.InstallIdentityProvider;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.util.Locale;

/**
 * 布局偏好调查的服务端状态端点：{@code GET /api/layout-feedback/state?surveyId=...} 返回
 * 调查作用域匿名身份与去重状态，{@code POST /api/layout-feedback/state} 接收动作式命令并
 * 经 revision / CAS 持久化到 {@code state/download-workbench/layout-feedback-state.json}。
 * 仅 solo 模式启用，multi 模式一律 403（不调用 InstallIdentityProvider、不读取 body、
 * 不触发 Store 加载、不读写状态文件）。
 *
 * <p>POST 处理顺序固定为：模式检查 → query surveyId 校验（缺失由 Controller 自行返回
 * 400）→ Content-Type 校验（null / 非 JSON / 非法字符串一律 415，读取 body 之前）→
 * Content-Length 前置限制 → 有界读取 body → 严格 JSON 解析 → DTO 校验 → query / body
 * surveyId 一致性 → Store degraded 检查 → Store apply → 200 / 409。请求体在完整读取前受
 * {@link #MAX_COMMAND_BODY_BYTES} 限制（chunked 流最多读 MAX+1），绝不使用
 * {@code readAllBytes()} 读取无界请求体。
 *
 * <p>全部响应（含缺 surveyId 的 400 与错误 Content-Type 的 415，这些错误不再由 Spring
 * 在进入 Controller 前生成）携带 {@code Cache-Control: no-store, private}：scoped
 * distinct ID / revision / submitted·never·snoozed / seen 一律不得被代理或浏览器缓存。
 * 原始安装 UUID 只用于派生 scoped ID，绝不进入响应。
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
        if (!isSolo()) {
            return statusResponse(HttpStatus.FORBIDDEN);
        }
        if (!LayoutFeedbackIdentityDeriver.isValidSurveyId(surveyId)) {
            return statusResponse(HttpStatus.BAD_REQUEST);
        }
        return jsonResponse(HttpStatus.OK, buildResponse(store.snapshot(), surveyId, clock.millis()));
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
        // 9. Store apply（CAS：APPLIED → 200，CONFLICT → 409，均带当前完整快照）。
        //    同一个请求只读取一次服务端时钟：Store apply 与响应 serverTime 使用同一个 now。
        long serverNow = clock.millis();
        LayoutFeedbackStateStore.ApplyResult result;
        try {
            result = store.apply(commandRequest, serverNow);
        } catch (IllegalStateException e) {
            // degraded 竞态：写入被拒绝。
            return statusResponse(HttpStatus.SERVICE_UNAVAILABLE);
        } catch (IOException e) {
            // 原子写入失败：保留旧内存状态，本次请求失败。
            return statusResponse(HttpStatus.SERVICE_UNAVAILABLE);
        }
        LayoutFeedbackStateResponse response = buildResponse(result.snapshot(), surveyId, serverNow);
        if (result.status() == LayoutFeedbackStateStore.ApplyStatus.CONFLICT) {
            return jsonResponse(HttpStatus.CONFLICT, response);
        }
        return jsonResponse(HttpStatus.OK, response);
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
     * trim 后非空。媒体类型段（首个分号之前）不属于参数；空参数段（尾分号 / 连续分号）
     * 按现有约定接受；引号未闭合返回 false。禁止使用正则 / {@code split(";")} 简单切分。
     */
    private static boolean areParametersWellFormed(String contentType) {
        int segmentStart = 0;
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
                if (!firstSegment && !isWellFormedSegment(contentType, segmentStart, i, hasEquals)) {
                    return false;
                }
                firstSegment = false;
                segmentStart = i + 1;
                hasEquals = false;
                continue;
            }
            if (c == '=' && !hasEquals) {
                hasEquals = true;
            }
        }
        if (inQuotes) {
            return false;
        }
        return firstSegment || isWellFormedSegment(contentType, segmentStart, length, hasEquals);
    }

    /** 单个参数段是否形如 {@code key=value}（key trim 后非空）；空段按现有约定接受。 */
    private static boolean isWellFormedSegment(String contentType, int segmentStart, int segmentEnd,
                                               boolean hasEquals) {
        if (contentType.substring(segmentStart, segmentEnd).trim().isEmpty()) {
            // 尾分号 / 连续分号产生的空参数段：与既有约定一致，接受。
            return true;
        }
        if (!hasEquals) {
            return false;
        }
        int equals = -1;
        for (int i = segmentStart; i < segmentEnd; i++) {
            if (contentType.charAt(i) == '=') {
                equals = i;
                break;
            }
        }
        if (equals < 0) {
            return false;
        }
        String key = contentType.substring(segmentStart, equals).trim();
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
                                                      String surveyId, long serverTime) {
        String scopedIdentity = LayoutFeedbackIdentityDeriver.deriveScopedIdentity(
                surveyId, installIdentityProvider.get());
        var state = snapshot.state() != null && surveyId.equals(snapshot.state().surveyId())
                ? snapshot.state()
                : null;
        return new LayoutFeedbackStateResponse(
                true,
                !store.degraded(),
                scopedIdentity,
                serverTime,
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
