package top.sywyar.pixivdownload.schedule;

/**
 * 计划任务的触发来源类型。
 *
 * <ul>
 *   <li>{@link #USER_NEW} —— 画师新作：发现某画师的全部插画/漫画 ID，跳过已下载的，抓取新作。</li>
 *   <li>{@link #SEARCH} —— 保存的搜索：按关键词 + 排序/分级周期性抓取新结果。</li>
 *   <li>{@link #SERIES} —— 漫画系列：抓取系列内尚未下载的作品。</li>
 *   <li>{@link #MY_BOOKMARKS} —— 我的收藏：发现账号收藏（{@code source.rest=show|hide}、{@code kind=illust|novel}）里尚未下载的作品。账号私有、必须 cookie。</li>
 *   <li>{@link #FOLLOW_LATEST} —— 已关注用户的新作：发现 Pixiv「フォロー新着作品」（仅插画/漫画/动图）里尚未下载的作品。账号私有、必须 cookie。</li>
 *   <li>{@link #COLLECTION} —— 珍藏集：发现某珍藏集（{@code source.collectionId}）内的插画与小说成员（混合），分别下载。账号私有、必须 cookie。</li>
 * </ul>
 */
public enum ScheduledTaskType {
    USER_NEW,
    SEARCH,
    SERIES,
    MY_BOOKMARKS,
    FOLLOW_LATEST,
    COLLECTION
}
