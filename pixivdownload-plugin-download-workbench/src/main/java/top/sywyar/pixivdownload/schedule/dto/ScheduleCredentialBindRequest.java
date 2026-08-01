package top.sywyar.pixivdownload.schedule.dto;

import jakarta.validation.constraints.NotBlank;

/** 绑定任务执行计划声明的凭证策略时提交的中性来源 publication 请求。 */
public record ScheduleCredentialBindRequest(
        @NotBlank String activationToken
) {
}
