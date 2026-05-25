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
}
