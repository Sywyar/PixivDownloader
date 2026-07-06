package top.sywyar.pixivdownload.douyin.model;

public record DouyinDownloadRequest(
        String input,
        String title,
        String cookie,
        String collectionId,
        String collectionTitle
) {

    public DouyinDownloadRequest(String input, String title, String cookie) {
        this(input, title, cookie, null, null);
    }
}
