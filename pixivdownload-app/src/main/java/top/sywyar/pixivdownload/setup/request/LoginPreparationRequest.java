package top.sywyar.pixivdownload.setup.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 登录挑战只接收主体和会话时效选择，不接收密码或执行认证。 */
public record LoginPreparationRequest(
        @NotBlank(message = "{validation.login.username.required}")
        @Size(max = 128, message = "{validation.login.username.size}") String username,
        boolean rememberMe) { }
