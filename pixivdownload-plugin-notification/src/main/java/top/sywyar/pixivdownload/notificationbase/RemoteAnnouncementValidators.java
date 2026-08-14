package top.sywyar.pixivdownload.notificationbase;

public record RemoteAnnouncementValidators(
        String manifestSha256,
        long expiresTime,
        String etag,
        String lastModified) {
}
