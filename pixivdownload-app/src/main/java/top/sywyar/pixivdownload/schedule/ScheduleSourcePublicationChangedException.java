package top.sywyar.pixivdownload.schedule;

/** 浏览器提交的来源激活令牌不再对应当前 publication。 */
public final class ScheduleSourcePublicationChangedException extends Exception {

    public ScheduleSourcePublicationChangedException(String sourceType) {
        super("scheduled source publication changed: " + sourceType);
    }
}
