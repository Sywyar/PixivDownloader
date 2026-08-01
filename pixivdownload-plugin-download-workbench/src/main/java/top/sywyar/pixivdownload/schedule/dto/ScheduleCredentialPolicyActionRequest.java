package top.sywyar.pixivdownload.schedule.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.util.Map;

/** 当前凭证策略 publication 上执行账号级纯值动作的中性请求。 */
@Data
public class ScheduleCredentialPolicyActionRequest {

    @NotBlank
    private String ownerPluginId;

    @NotBlank
    private String policyId;

    @NotNull
    @Positive
    private Long publicationId;

    @NotBlank
    private String accountKey;

    @NotBlank
    private String actionId;

    private Map<String, String> parameters = Map.of();
}
