package top.sywyar.pixivdownload.notificationbase;

/** 远程公告的一份本地化元数据与可选 HTML 快照。 */
public record RemoteAnnouncementTranslation(
        String locale,
        String title,
        String summary,
        String contentUrl,
        String contentSha256,
        String contentHtml
) {

    RemoteAnnouncementTranslation withHtml(String html) {
        return new RemoteAnnouncementTranslation(locale, title, summary, contentUrl, contentSha256, html);
    }
}
