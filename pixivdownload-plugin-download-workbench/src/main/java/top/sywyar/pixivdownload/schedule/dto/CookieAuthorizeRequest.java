package top.sywyar.pixivdownload.schedule.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 为计划任务授权（快照）来源凭证的请求体。
 *
 * <p>新客户端把凭证放在 {@code X-Acquisition-Credential} 请求头；{@code cookie} 仅保留旧客户端兼容。
 * 凭证绝不写日志 / 回显。
 */
@Data
public class CookieAuthorizeRequest {

    /** 来源清单给出的当前 publication 激活令牌。 */
    @NotBlank
    private String activationToken;

    /** 旧客户端兼容字段；新请求应保持为空。 */
    private String cookie;
}
