package top.sywyar.pixivdownload.download.schedule.credential;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.core.web.AcquisitionCredentialResolver;
import top.sywyar.pixivdownload.download.DownloadWorkbenchPlugin;
import top.sywyar.pixivdownload.download.response.ErrorResponse;
import top.sywyar.pixivdownload.download.schedule.credential.dto.AccountResumeRequest;
import top.sywyar.pixivdownload.download.schedule.credential.dto.CookieAuthorizeRequest;
import top.sywyar.pixivdownload.download.web.LocalizedException;
import top.sywyar.pixivdownload.download.web.WorkbenchErrorResponses;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;
import top.sywyar.pixivdownload.schedule.ScheduleService;
import top.sywyar.pixivdownload.schedule.dto.ScheduleTaskView;

import java.util.Locale;
import java.util.Map;

/**
 * Pixiv 计划凭证旧版 HTTP 兼容适配器。路径和请求体保持不变，具体 Cookie 与账号风险语义不进入通用计划控制器。
 */
@RestController
@RequestMapping("/api/schedule")
@PluginManagedBean
@RequiredArgsConstructor
public class PixivScheduleCredentialController {

    private final ScheduleService scheduleService;
    private final MessageResolver messages;

    @PostMapping("/tasks/{id}/authorize-cookie")
    public ScheduleTaskView authorizeCookie(
            @PathVariable long id,
            @Valid @RequestBody CookieAuthorizeRequest request,
            HttpServletRequest servletRequest) {
        String credential = AcquisitionCredentialResolver.resolve(
                servletRequest == null ? null
                        : servletRequest.getHeader(AcquisitionCredentialResolver.HEADER_NAME),
                request.getCookie());
        return scheduleService.bindCredential(
                id, credential, request.getActivationToken());
    }

    @PostMapping("/tasks/{id}/revoke-cookie")
    public ScheduleTaskView revokeCookie(@PathVariable long id) {
        return scheduleService.revokeCredential(id);
    }

    @PostMapping("/account/{accountId}/resume")
    public ResumeAccountResponse resumeAccount(
            @PathVariable String accountId,
            @RequestBody AccountResumeRequest request) {
        scheduleService.applyCurrentCredentialPolicyAction(
                DownloadWorkbenchPlugin.ID,
                PixivScheduledCredentialPolicy.POLICY_ID,
                accountId,
                request.getMode(),
                request.getMinutes() == null
                        ? Map.of()
                        : Map.of("minutes", Integer.toString(request.getMinutes())));
        return new ResumeAccountResponse(true);
    }

    public record ResumeAccountResponse(boolean success) {}

    @ExceptionHandler(LocalizedException.class)
    public ResponseEntity<ErrorResponse> handleLocalized(
            LocalizedException failure,
            Locale locale) {
        return WorkbenchErrorResponses.localized(failure, messages, locale);
    }
}
