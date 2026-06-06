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
        private Integer readingTimeSeconds;
        private Integer pageCount;
        private String description;
        private List<TagDto> tags;
        private Long seriesId;
        private Long seriesOrder;
        private String seriesTitle;
        /** 系列简介（{@code /ajax/novel/series/{id}} → {@code body.caption}）。下载时一并落库，best-effort。 */
        private String seriesDescription;
        /** 系列封面 URL（pximg），下载时若本地尚无封面则尝试 SSRF-safe 拉取后落盘。 */
        private String seriesCoverUrl;
        /** 系列标签；下载时整体替换 novel_series_tags。 */
        private List<TagDto> seriesTags;
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
        /** 下载完成后是否自动 AI 翻译（admin-only；游客请求会被控制器置 false）。 */
        private boolean autoTranslate;
        /** 自动翻译的目标语言自由文本（如 {@code english}）；{@code autoTranslate} 为真时使用。 */
        private String autoTranslateLanguage;
        /** 自动翻译的分段字数阈值（{@code <=0}/null 整章一次性翻译）。 */
        private Integer autoTranslateSegmentSize;
        /** 自动翻译后是否重生该语言的系列译文合订本（取自下载设置的「生成合订本」）。 */
        private boolean autoTranslateMerge;
        /** 译文合订本格式（{@code epub}/{@code txt}/{@code html}）。 */
        private String autoTranslateMergeFormat;
    }
}
