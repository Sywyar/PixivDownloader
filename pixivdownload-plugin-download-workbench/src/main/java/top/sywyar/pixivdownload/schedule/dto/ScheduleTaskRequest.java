package top.sywyar.pixivdownload.schedule.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

/**
 * 计划任务的创建 / 编辑请求体。来源定义由所选来源的前端贡献生成，宿主只把不透明 JSON 交给
 * 当前激活 owner 规范化和校验。
 */
@Data
public class ScheduleTaskRequest {

    @NotBlank
    private String name;

    @NotBlank
    private String sourceType;

    /** 来源清单给出的当前 publication 激活令牌。 */
    @NotBlank
    private String activationToken;

    /** 来源插件拥有的不透明定义 JSON。 */
    @NotBlank
    private String definitionJson;

    /**
     * 编辑器打开任务时固定的状态版本。创建请求禁止携带；编辑请求必须携带非负版本，
     * 后续列表轮询不得用较新的版本替换它。
     */
    @PositiveOrZero
    private Long expectedStateVersion;

    /** {@code interval} 或 {@code cron} */
    @NotBlank
    private String triggerKind;

    /** interval 模式的周期分钟数 */
    private Integer intervalMinutes;

    /** cron 模式的 Spring Cron 表达式 */
    private String cronExpr;
}
