package top.sywyar.pixivdownload.onboarding;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.common.NetworkUtils;

/**
 * 浏览器侧引导回报接口。
 *
 * <p>只保留供浏览器（同机）调用的写入端点；引导进度读取与 GUI 内联配置
 * 已收敛到 GUI 令牌通道下的 {@code /api/gui/onboarding} 与
 * {@code /api/gui/setup/init}。本路径在 {@code AuthFilter.isAlwaysPublicApi}
 * 放行（浏览器无 GUI 令牌），由控制器自身强制 localhost。</p>
 */
@RestController
@RequestMapping("/api/onboarding")
@RequiredArgsConstructor
public class OnboardingController {

    private final OnboardingProgressService progressService;

    @PostMapping("/gallery-guide-done")
    public ResponseEntity<Void> galleryGuideDone(HttpServletRequest req) {
        if (!NetworkUtils.isTrustedLocalRequest(req)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        progressService.markGalleryGuideCompleted();
        return ResponseEntity.ok().build();
    }
}
