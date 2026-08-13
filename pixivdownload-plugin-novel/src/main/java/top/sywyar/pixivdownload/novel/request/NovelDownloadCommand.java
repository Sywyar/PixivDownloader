package top.sywyar.pixivdownload.novel.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 公开下载端点只接受作品标识与用户选择，作品内容由服务端向 Pixiv 获取。 */
@Data
@JsonIgnoreProperties({"title", "cookie", "content"})
public class NovelDownloadCommand {

    @NotNull
    @Positive
    private Long novelId;

    @Valid
    private Other other = new Other();

    @Data
    @JsonIgnoreProperties({
            "userDownload", "username", "authorId", "authorName", "xRestrict", "ai", "original",
            "language", "wordCount", "textLength", "readingTimeSeconds", "pageCount", "description",
            "tags", "seriesId", "seriesOrder", "seriesTitle", "seriesDescription", "seriesCoverUrl",
            "seriesTags", "fileName", "fileNameTimestamp", "delayMs", "uploadTimestamp", "coverUrl",
            "embeddedImages", "rawMetaJson"
    })
    public static class Other {

        @Size(max = 512)
        private String fileNameTemplate;

        private boolean bookmark;

        @Positive
        private Long collectionId;

        @Pattern(regexp = "(?i)txt|html|epub")
        private String format;

        private boolean autoTranslate;

        @Size(max = 100)
        private String autoTranslateLanguage;

        @Min(0)
        @Max(1_000_000)
        private Integer autoTranslateSegmentSize;

        private boolean autoTranslateMerge;

        @Pattern(regexp = "(?i)txt|html|epub")
        private String autoTranslateMergeFormat;

        public void applyTo(NovelDownloadRequest.Other target) {
            target.setFileNameTemplate(fileNameTemplate);
            target.setBookmark(bookmark);
            target.setCollectionId(collectionId);
            target.setFormat(format);
            target.setAutoTranslate(autoTranslate);
            target.setAutoTranslateLanguage(autoTranslateLanguage);
            target.setAutoTranslateSegmentSize(autoTranslateSegmentSize);
            target.setAutoTranslateMerge(autoTranslateMerge);
            target.setAutoTranslateMergeFormat(autoTranslateMergeFormat);
        }
    }
}
