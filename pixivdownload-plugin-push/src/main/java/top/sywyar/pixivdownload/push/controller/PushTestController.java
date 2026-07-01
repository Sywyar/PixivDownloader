package top.sywyar.pixivdownload.push.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.push.PushChannelSettings;
import top.sywyar.pixivdownload.push.PushDispatcher;
import top.sywyar.pixivdownload.push.PushLevel;
import top.sywyar.pixivdownload.push.PushMessage;
import top.sywyar.pixivdownload.push.PushResult;
import top.sywyar.pixivdownload.web.LocalRequestTrust;

import java.util.List;

/**
 * GUI 配置页"测试推送"按钮对应的 REST 端点。
 * <p>
 * 路径 {@code /api/gui/push-test} 由 {@code AuthFilter} 对 {@code /api/gui/**} 统一施加"本地请求 + GUI token"
 * 双校验；这里再次显式执行本地请求校验作为深度防御。
 * <p>
 * 接收 GUI 当前推送表单的临时设置（含各通道密钥，仅在 localhost 同进程内传递），对其中已勾选启用的通道用一条
 * 固定的测试消息发送，把每通道结果汇总回 GUI；明细绝不含密钥。<b>不</b>检查推送总开关，便于保存前测试。
 */
@RestController
@RequestMapping("/api/gui")
@RequiredArgsConstructor
public class PushTestController {

    private final PushDispatcher pushService;
    private final MessageResolver messages;

    @PostMapping("/push-test")
    public ResponseEntity<PushTestResponse> test(@RequestBody PushTestRequest body,
                                                 HttpServletRequest request) {
        if (!trustedLocalRequest(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (body == null) {
            return ResponseEntity.ok(PushTestResponse.from(List.of()));
        }
        List<PushChannelSettings> settings = body.toEnabledSettings();
        PushMessage message = PushMessage.of(
                messages.get("push.test.message.title"),
                messages.get("push.test.message.body"),
                PushLevel.INFO);
        List<PushResult> results = pushService.test(settings, message);
        return ResponseEntity.ok(PushTestResponse.from(results));
    }

    private static boolean trustedLocalRequest(HttpServletRequest request) {
        return request != null && LocalRequestTrust.isTrustedLocalRequest(
                request.getRemoteAddr(),
                request.getHeader("Host"),
                request.getHeader("X-Forwarded-For"),
                request.getHeader("X-Real-IP"),
                request.getHeader("Forwarded"),
                request.getHeader("Origin"));
    }
}
