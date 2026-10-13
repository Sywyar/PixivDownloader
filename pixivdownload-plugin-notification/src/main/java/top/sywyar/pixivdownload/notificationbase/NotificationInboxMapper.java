package top.sywyar.pixivdownload.notificationbase;

import java.util.List;

/** 管理员站内信的持久化操作。 */
public interface NotificationInboxMapper {
    int insert(NotificationMessage m);
    List<NotificationMessage> findLatest(String category, boolean unreadOnly, int limit);
    NotificationMessage findById(String id);
    NotificationHtmlContent findHtmlContent(String id);
    boolean blocksRemoteAnnouncementImport(String id);
    int updateRemoteAnnouncement(NotificationMessage m);
    List<RemoteAnnouncementTranslation> findRemoteAnnouncementTranslations(String id);
    NotificationHtmlContent findRemoteAnnouncementHtml(String id, String locale);
    int upsertRemoteAnnouncementTranslation(String id, RemoteAnnouncementTranslation t);
    int deleteStaleRemoteAnnouncementTranslations(String id, List<String> locales);
    int deleteRemoteAnnouncementTranslations(String id);
    int acceptRemoteAnnouncementIndex(long sequence, String digest, long generated, long expires);
    RemoteAnnouncementValidators findRemoteAnnouncementValidators();
    int saveRemoteAnnouncementValidators(String digest, String etag, String lastModified);
    long countUnread(String category);
    int markRead(String id, long time);
    int markAllRead(String category, long time);
    int dismissAnnouncement(String id, long time);
    int dismissPersistentSurvey(String id, long time);
    int setActivePersistentSurveys(List<String> ids);
    int deleteNonAnnouncement(String id);
    int pruneRetentionPool(long cutoff, int maxMessages);
}
