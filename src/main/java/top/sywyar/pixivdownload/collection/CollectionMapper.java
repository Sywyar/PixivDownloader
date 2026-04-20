package top.sywyar.pixivdownload.collection;

import org.apache.ibatis.annotations.*;

import java.util.Collection;
import java.util.List;

@Mapper
public interface CollectionMapper {

    @Update("CREATE TABLE IF NOT EXISTS collections ("
            + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + "name TEXT NOT NULL,"
            + "icon_ext TEXT,"
            + "sort_order INTEGER DEFAULT 0,"
            + "created_time INTEGER NOT NULL)")
    void createCollectionsTable();

    @Update("CREATE TABLE IF NOT EXISTS artwork_collections ("
            + "collection_id INTEGER NOT NULL,"
            + "artwork_id INTEGER NOT NULL,"
            + "added_time INTEGER NOT NULL,"
            + "PRIMARY KEY (collection_id, artwork_id))")
    void createArtworkCollectionsTable();

    @Update("CREATE INDEX IF NOT EXISTS idx_artwork_collections_artwork"
            + " ON artwork_collections(artwork_id)")
    void createArtworkCollectionsArtworkIndex();

    @Select("SELECT c.id, c.name, c.icon_ext AS iconExt,"
            + " c.sort_order AS sortOrder, c.created_time AS createdTime,"
            + " COALESCE((SELECT COUNT(*) FROM artwork_collections ac WHERE ac.collection_id = c.id), 0) AS artworkCount"
            + " FROM collections c ORDER BY c.sort_order, c.id")
    List<top.sywyar.pixivdownload.collection.Collection> findAll();

    @Select("SELECT c.id, c.name, c.icon_ext AS iconExt,"
            + " c.sort_order AS sortOrder, c.created_time AS createdTime,"
            + " COALESCE((SELECT COUNT(*) FROM artwork_collections ac WHERE ac.collection_id = c.id), 0) AS artworkCount"
            + " FROM collections c WHERE c.id = #{id}")
    top.sywyar.pixivdownload.collection.Collection findById(@Param("id") long id);

    @Insert("INSERT INTO collections(name, icon_ext, sort_order, created_time)"
            + " VALUES(#{name}, #{iconExt}, #{sortOrder}, #{createdTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    void insert(CollectionInsert insert);

    @Update("UPDATE collections SET name = #{name} WHERE id = #{id}")
    int updateName(@Param("id") long id, @Param("name") String name);

    @Update("UPDATE collections SET icon_ext = #{iconExt} WHERE id = #{id}")
    int updateIconExt(@Param("id") long id, @Param("iconExt") String iconExt);

    @Update("UPDATE collections SET sort_order = #{sortOrder} WHERE id = #{id}")
    int updateSortOrder(@Param("id") long id, @Param("sortOrder") int sortOrder);

    @Delete("DELETE FROM collections WHERE id = #{id}")
    int deleteById(@Param("id") long id);

    @Delete("DELETE FROM artwork_collections WHERE collection_id = #{id}")
    int deleteArtworkLinksByCollection(@Param("id") long id);

    @Insert("INSERT OR IGNORE INTO artwork_collections(collection_id, artwork_id, added_time)"
            + " VALUES(#{collectionId}, #{artworkId}, #{addedTime})")
    int addArtwork(@Param("collectionId") long collectionId,
                   @Param("artworkId") long artworkId,
                   @Param("addedTime") long addedTime);

    @Delete("DELETE FROM artwork_collections"
            + " WHERE collection_id = #{collectionId} AND artwork_id = #{artworkId}")
    int removeArtwork(@Param("collectionId") long collectionId,
                      @Param("artworkId") long artworkId);

    @Delete("DELETE FROM artwork_collections WHERE artwork_id = #{artworkId}")
    int removeAllArtworkLinks(@Param("artworkId") long artworkId);

    @Select("SELECT collection_id FROM artwork_collections WHERE artwork_id = #{artworkId}")
    List<Long> findCollectionIdsByArtwork(@Param("artworkId") long artworkId);

    @Select({
            "<script>",
            "SELECT artwork_id AS artworkId, collection_id AS collectionId FROM artwork_collections",
            "WHERE artwork_id IN",
            "<foreach item='id' collection='ids' open='(' separator=',' close=')'>#{id}</foreach>",
            "</script>"
    })
    List<java.util.Map<String, Object>> findLinksByArtworks(@Param("ids") Collection<Long> artworkIds);

    @Select({
            "<script>",
            "SELECT DISTINCT artwork_id FROM artwork_collections",
            "WHERE collection_id IN",
            "<foreach item='id' collection='ids' open='(' separator=',' close=')'>#{id}</foreach>",
            "</script>"
    })
    List<Long> findArtworkIdsInCollections(@Param("ids") Collection<Long> collectionIds);

    @Select("SELECT COUNT(*) FROM collections WHERE id = #{id}")
    int countById(@Param("id") long id);

    @Select("SELECT COUNT(*) FROM collections WHERE LOWER(name) = LOWER(#{name}) AND id <> #{excludeId}")
    int countByNameExcludingId(@Param("name") String name, @Param("excludeId") long excludeId);

    @Select("SELECT COUNT(*) FROM collections WHERE LOWER(name) = LOWER(#{name})")
    int countByName(@Param("name") String name);
}
