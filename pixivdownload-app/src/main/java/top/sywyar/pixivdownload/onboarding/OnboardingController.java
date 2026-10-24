package top.sywyar.pixivdownload.onboarding;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.common.NetworkUtils;
import top.sywyar.pixivdownload.common.PixivConnectivityProbe;
import top.sywyar.pixivdownload.setup.SetupService;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * 浏览器侧引导接口。
 *
 * <p>路由声明为 {@code PUBLIC}，每个端点另行校验权限：步骤完成回调只接受本机可信请求；
 * {@code GET /profile} 通过 {@link SetupService#hasAdminScope} 排除邀请访客，允许 solo 请求与
 * multi 模式已登录管理员读取；{@code POST /profile}（写入称呼）与 {@code GET /connectivity}
 * （触发外部探测）均要求 {@link SetupService#isAdminLoggedIn}，避免未登录请求产生写入或外联。</p>
 *
 * <p>称呼（displayName）是非敏感的个性化展示信息，写入 {@code setup_config.json}；连通性探测固定只访问
 * {@code https://www.pixiv.net/}，不携带 Cookie、不回显上游正文（见 {@link PixivConnectivityProbe}）。</p>
 */
@RestController
@RequestMapping("/api/onboarding")
@RequiredArgsConstructor
public class OnboardingController {

    private static final int MAX_DISPLAY_NAME_LENGTH = 40;
    private static final Pattern STEP_ID_PATTERN =
            Pattern.compile("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*");

    private final OnboardingProgressService progressService;
    private final SetupService setupService;
    private final PixivConnectivityProbe pixivConnectivityProbe;

    @PostMapping("/steps/{stepId}/complete")
    public ResponseEntity<Void> completeStep(@PathVariable String stepId, HttpServletRequest req) {
        if (!NetworkUtils.isTrustedLocalRequest(req)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (stepId == null || !STEP_ID_PATTERN.matcher(stepId).matches()) {
            return ResponseEntity.badRequest().build();
        }
        progressService.markStepCompleted(stepId);
        return ResponseEntity.ok().build();
    }

    /**
     * 读取当前称呼；前端把 403 作为不参与新手向导的信号，读取资格与写入、探测权限分别判定。
     */
    @GetMapping("/profile")
    public ResponseEntity<ProfileResponse> getProfile(HttpServletRequest req) {
        if (!setupService.hasAdminScope(req)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(new ProfileResponse(setupService.getDisplayName()));
    }

    /** 更新称呼；空白视为清除。仅真正已登录的管理员可写，避免 solo 模式未登录请求覆盖 displayName。 */
    @PostMapping("/profile")
    public ResponseEntity<ProfileResponse> updateProfile(@RequestBody(required = false) ProfileRequest body,
                                                         HttpServletRequest req) throws IOException {
        if (!setupService.isAdminLoggedIn(req)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        String name = body == null ? null : body.displayName();
        if (name != null && name.trim().length() > MAX_DISPLAY_NAME_LENGTH) {
            name = name.trim().substring(0, MAX_DISPLAY_NAME_LENGTH);
        }
        setupService.updateDisplayName(name);
        return ResponseEntity.ok(new ProfileResponse(setupService.getDisplayName()));
    }

    /**
     * 探测后端到 Pixiv 的网络连通性（往返耗时）。仅真正已登录的管理员可触发，避免 solo 模式未登录请求
     * 被未授权方反复打到外网探测端点。
     */
    @GetMapping("/connectivity")
    public ResponseEntity<ConnectivityResponse> connectivity(HttpServletRequest req) {
        if (!setupService.isAdminLoggedIn(req)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        PixivConnectivityProbe.Result r = pixivConnectivityProbe.probe();
        return ResponseEntity.ok(new ConnectivityResponse(r.reachable(), r.latencyMs(), r.errorType()));
    }

    public record ProfileRequest(String displayName) {
    }

    public record ProfileResponse(String displayName) {
    }

    public record ConnectivityResponse(boolean reachable, long latencyMs, String errorType) {
    }
}
