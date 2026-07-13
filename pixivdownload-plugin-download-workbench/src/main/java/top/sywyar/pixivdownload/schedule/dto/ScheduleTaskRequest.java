package top.sywyar.pixivdownload.schedule.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 计划任务的创建 / 编辑请求体。
 *
 * <p>{@code paramsJson} 按 {@code type} 解释（顶层另含 {@code kind} 与 {@code filters} / {@code download} 段）：
 * <ul>
 *   <li>{@code USER_NEW}：{@code {"source":{"userId":"123"}}}</li>
 *   <li>{@code SEARCH}：{@code {"source":{"word":"...","order":"date_d","mode":"all","sMode":"s_tag","maxPages":3}}}</li>
 *   <li>{@code SERIES}：{@code {"source":{"seriesId":"123"}}}</li>
 *   <li>{@code MY_BOOKMARKS}：{@code {"kind":"illust|novel","source":{"rest":"show|hide"}}}（账号私有，需 cookie）</li>
 *   <li>{@code FOLLOW_LATEST}：{@code {"kind":"illust","source":{}}}（账号私有，需 cookie）</li>
 *   <li>{@code COLLECTION}：{@code {"source":{"collectionId":"123"}}}（插画+小说混合都下，账号私有，需 cookie）</li>
 * </ul>
 */
@Data
public class ScheduleTaskRequest {

    @NotBlank
    private String name;

    @NotBlank
    private String type;

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
