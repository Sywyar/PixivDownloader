package top.sywyar.pixivdownload.download.schedule;

import lombok.Data;

/** 已发布 {@code schedule.*} 键中由 Pixiv 计划来源拥有的策略配置。 */
@Data
public final class PixivScheduleSettings {

    /** 轮内读取 Pixiv 站内信的成功派发间隔。 */
    private volatile int inboxCheckEvery = 500;

    /** 账号风险确认后延迟恢复的默认分钟数。 */
    private volatile int overuseDeferDefaultMinutes = 60;
}
