package top.sywyar.pixivdownload.schedule;

/**
 * 计划任务的触发来源类型。
 *
 * <ul>
 *   <li>{@link #USER_NEW} —— 画师新作：发现某画师的全部插画/漫画 ID，跳过已下载的，抓取新作。</li>
 *   <li>{@link #SEARCH} —— 保存的搜索：按关键词 + 排序/分级周期性抓取新结果。</li>
 *   <li>{@link #SERIES} —— 漫画系列：抓取系列内尚未下载的作品。</li>
 * </ul>
 */
public enum ScheduledTaskType {
    USER_NEW,
    SEARCH,
    SERIES
}
