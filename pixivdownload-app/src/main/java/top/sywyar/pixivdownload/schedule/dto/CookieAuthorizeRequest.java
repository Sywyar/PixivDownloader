package top.sywyar.pixivdownload.schedule.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 为计划任务授权（快照）Cookie 的请求体。
 *
 * <p>前端把 Cookie 卡片里那份当前 cookie（即平时作为 {@code X-Pixiv-Cookie} 发出的值）POST 上来，
 * 服务端校验含 {@code PHPSESSID} 后快照进任务行。cookie 绝不写日志 / 回显。
 */
@Data
public class CookieAuthorizeRequest {

    @NotBlank
    private String cookie;
}
