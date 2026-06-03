package top.sywyar.pixivdownload.novel.db;

import lombok.Data;

/**
 * 新建朗读花名册的写入载体。{@code id} 由 MyBatis 在插入后回填（{@code useGeneratedKeys}）。
 */
@Data
public class NovelNarrationCastInsert {
    private Long id;
    private String name;
    private Long seriesId;
    private Long novelId;
    private long createdTime;
    private long updatedTime;
}
