package top.sywyar.pixivdownload.core.download.request;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 恢复已存在文件的记录时，页数必须来自作品元数据；
 * 前端调 Pixiv 拉回作品元数据，再 POST 给 /api/downloaded/{id}/recover-metadata 把缺的字段补齐。
 * DB 无记录时仅在已知总页数且各页文件齐全后登记。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecoverMetadataRequest {
    private String title;
    private Long authorId;
    private String authorName;
    private Integer xRestrict;
    private Boolean isAi;
    private String description;
    private Integer pageCount;

    @JsonGetter("xRestrict")
    public Integer getXRestrict() {
        return xRestrict;
    }

    @JsonSetter("xRestrict")
    public void setXRestrict(Integer xRestrict) {
        this.xRestrict = xRestrict;
    }
}
