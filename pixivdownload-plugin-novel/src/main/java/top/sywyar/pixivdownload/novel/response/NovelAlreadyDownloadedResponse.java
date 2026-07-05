package top.sywyar.pixivdownload.novel.response;

public record NovelAlreadyDownloadedResponse(
        boolean success,
        boolean alreadyDownloaded,
        String message
) {
}
