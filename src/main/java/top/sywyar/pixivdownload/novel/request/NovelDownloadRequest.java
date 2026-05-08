package top.sywyar.pixivdownload.novel.request;

import lombok.Data;
import top.sywyar.pixivdownload.download.db.TagDto;

import java.util.List;
import java.util.Map;

@Data
public class NovelDownloadRequest {
    private Long novelId;
    private String title;
    private String cookie;
    private String content;
    private Other other = new Other();

    @Data
    public static class Other {
        private boolean userDownload;
        private String username;
        private Long authorId;
        private String authorName;
        private int xRestrict;
        private boolean ai;
        private boolean original;
        private String language;
        private Integer wordCount;
        private Integer textLength;
        private Integer pageCount;
        private String description;
        private List<TagDto> tags;
        private Long seriesId;
        private Long seriesOrder;
        private String seriesTitle;
        private String fileNameTemplate;
        private String fileName;
        private Long fileNameTimestamp;
        private boolean bookmark;
        private Long collectionId;
        private int delayMs;
        /** TXT / HTML / EPUB；不区分大小写。 */
        private String format;
        /** Pixiv upload timestamp, in epoch milliseconds. */
        private Long uploadTimestamp;
        /** 封面 URL（pximg），用于在系列目录页生成缩略图；可选。 */
        private String coverUrl;
        /** [uploadedimage:id] → pximg 原图 URL；可选。 */
        private Map<String, String> embeddedImages;
    }
}
