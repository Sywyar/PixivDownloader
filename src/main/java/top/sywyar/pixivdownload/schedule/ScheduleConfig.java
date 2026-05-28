package top.sywyar.pixivdownload.schedule;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 计划任务调度配置。前缀 {@code schedule}。
 *
 * <p>调度以管理员身份运行，不受限流 / 配额约束；这里只控制 tick 节奏与抓取礼貌延迟。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "schedule")
public class ScheduleConfig {

    /** 是否启用计划任务调度（关闭时 tick 直接跳过，所有任务不运行） */
    private volatile boolean enabled = true;

    /** 调度 tick 间隔（毫秒）：每隔多久检查一次到期任务，默认 60 秒 */
    private volatile long tickIntervalMs = 60_000L;

    /** 单库最多可创建的计划任务数（防滥用） */
    private volatile int maxTasks = 100;

    /** 抓取相邻作品间的礼貌延迟（毫秒），避免被 Pixiv 限流 */
    private volatile long fetchDelayMs = 1_000L;

    /** 轮内过度访问检查点的派发间隔 N：每成功派发 N 个下载读一次站内信（跳过/过滤不计） */
    private volatile int inboxCheckEvery = 500;

    /** 单作品连续 PixivFetchException 熔断阈值 M：连续失败达到即挂起任务（AUTH_EXPIRED） */
    private volatile int authFailureCircuitBreaker = 5;

    /** 隔离表单作品最大自动重试次数：到上限标「需人工」、停止自动重试 */
    private volatile int pendingMaxAttempts = 5;

    /** 账号级 defer 恢复的默认延迟分钟数（最低 60） */
    private volatile int overuseDeferDefaultMinutes = 60;
}
