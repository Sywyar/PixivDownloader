package top.sywyar.pixivdownload.novel.db;

import lombok.Data;

/**
 * 新建名词映射表的写入载体。{@code id} 由数据库提供者在插入后返回并回填。
 */
@Data
public class NovelGlossaryInsert {
    private Long id;
    private String name;
    private Long seriesId;
    private Long novelId;
    private long createdTime;
    private long updatedTime;
}
