package top.sywyar.pixivdownload.onboarding;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.common.NetworkUtils;
import top.sywyar.pixivdownload.common.PixivConnectivityProbe;
import top.sywyar.pixivdownload.setup.SetupService;

import java.io.IOException;

/**
 * 浏览器侧引导接口。
 *
 * <p>本路径在 {@code AuthFilter.isAlwaysPublicApi} 放行（浏览器无 GUI 令牌），故每个端点都由控制器
 * 自身做权限校验：{@code gallery-guide-done} 仅限同机调用；{@code profile} / {@code connectivity} 仅对
 * 「全局可见」范围（solo 模式任意请求 / multi 模式登录管理员）开放——新用户引导是面向管理员的体验。</p>
 *
 * <p>称呼（displayName）是非敏感的个性化展示信息，写入 {@code setup_config.json}；连通性探测固定只访问
 * {@code https://www.pixiv.net/}，不携带 Cookie、不回显上游正文（见 {@link PixivConnectivityProbe}）。</p>
 */
@RestController
@RequestMapping("/api/onboarding")
@RequiredArgsConstructor
public class OnboardingController {

    private static final int MAX_DISPLAY_NAME_LENGTH = 40;

    private final OnboardingProgressService progressService;
    private final SetupService setupService;
    private final PixivConnectivityProbe pixivConnectivityProbe;

    @PostMapping("/gallery-guide-done")
    public ResponseEntity<Void> galleryGuideDone(HttpServletRequest req) {
        if (!NetworkUtils.isTrustedLocalRequest(req)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        progressService.markGalleryGuideCompleted();
        return ResponseEntity.ok().build();
    }

    /** 读取当前称呼（个性化问候）。 */
    @GetMapping("/profile")
    public ResponseEntity<ProfileResponse> getProfile(HttpServletRequest req) {
        if (!setupService.hasAdminScope(req)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(new ProfileResponse(setupService.getDisplayName()));
    }

    /** 更新称呼；空白视为清除。 */
    @PostMapping("/profile")
    public ResponseEntity<ProfileResponse> updateProfile(@RequestBody(required = false) ProfileRequest body,
                                                         HttpServletRequest req) throws IOException {
        if (!setupService.hasAdminScope(req)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        String name = body == null ? null : body.displayName();
        if (name != null && name.trim().length() > MAX_DISPLAY_NAME_LENGTH) {
            name = name.trim().substring(0, MAX_DISPLAY_NAME_LENGTH);
        }
        setupService.updateDisplayName(name);
        return ResponseEntity.ok(new ProfileResponse(setupService.getDisplayName()));
    }

    /** 探测后端到 Pixiv 的网络连通性（往返耗时）。 */
    @GetMapping("/connectivity")
    public ResponseEntity<ConnectivityResponse> connectivity(HttpServletRequest req) {
        if (!setupService.hasAdminScope(req)) {
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
