package top.sywyar.pixivdownload.collection;

import lombok.Data;

/**
 * {@link CollectionMapper#insert(CollectionInsert)} 的参数载体。
 * MyBatis {@code useGeneratedKeys} 通过 {@code id} setter 回填自增主键。
 */
@Data
public class CollectionInsert {
    private Long id;
    private String name;
    private String iconExt;
    private String downloadRoot;
    private int sortOrder;
    private long createdTime;
}
