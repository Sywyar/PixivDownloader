package top.sywyar.pixivdownload.schedule.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import top.sywyar.pixivdownload.schedule.ScheduledTaskType;

/**
 * 计划任务的创建 / 编辑请求体。
 *
 * <p>{@code paramsJson} 按 {@code type} 解释：
 * <ul>
 *   <li>{@code USER_NEW}：{@code {"userId":"123"}}（可选 {@code fileNameTemplate}）</li>
 *   <li>{@code SEARCH}：{@code {"word":"...","order":"date_d","mode":"all","sMode":"s_tag","maxPages":3}}</li>
 *   <li>{@code SERIES}：{@code {"seriesId":"123"}}</li>
 * </ul>
 */
@Data
public class ScheduleTaskRequest {

    @NotBlank
    private String name;

    @NotNull
    private ScheduledTaskType type;

    /** 任务参数 JSON（按 type 解释，见类注释） */
    @NotBlank
    private String paramsJson;

    /** {@code interval} 或 {@code cron} */
    @NotBlank
    private String triggerKind;

    /** interval 模式的周期分钟数 */
    private Integer intervalMinutes;

    /** cron 模式的 Spring Cron 表达式 */
    private String cronExpr;
}
