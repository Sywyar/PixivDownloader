package top.sywyar.pixivdownload.core.asset.artwork;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import top.sywyar.pixivdownload.core.asset.ArtworkMediaManifest;
import top.sywyar.pixivdownload.core.asset.ArtworkMediaStore;
import top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer;

import javax.sql.DataSource;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 宿主拥有的逐页事实；独立页 UPSERT 避免整作品读改写丢失并发更新。 */
@Repository
public class ArtworkMediaStoreImpl implements ArtworkMediaStore {
    private final JdbcTemplate jdbc;

    public ArtworkMediaStoreImpl(DataSource dataSource, DatabaseInitializer initializer) {
        jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public Optional<ArtworkMediaManifest> find(long artworkId, int page) throws IOException {
        validateIdentity(artworkId, page);
        try {
            return jdbc.query("SELECT original_extension, extensions, original_retained FROM artwork_media"
                            + " WHERE artwork_id = ? AND page = ?",
                    (rs, row) -> {
                        int retained = rs.getInt(3);
                        if (retained != 0 && retained != 1) throw new IllegalArgumentException("Invalid retention fact");
                        return new ArtworkMediaManifest(rs.getString(1), List.of(rs.getString(2).split(",", -1)), retained == 1);
                    }, artworkId, page).stream().findFirst();
        } catch (DataAccessException | IllegalArgumentException failure) {
            throw new IOException("Cannot read artwork media", failure);
        }
    }

    @Override
    public void save(long artworkId, int page, ArtworkMediaManifest media) throws IOException {
        validateIdentity(artworkId, page);
        Objects.requireNonNull(media);
        try {
            jdbc.update("INSERT INTO artwork_media(artwork_id, page, original_extension, extensions, original_retained)"
                            + " VALUES (?, ?, ?, ?, ?) ON CONFLICT(artwork_id, page) DO UPDATE SET"
                            + " original_extension = excluded.original_extension, extensions = excluded.extensions,"
                            + " original_retained = excluded.original_retained",
                    artworkId, page, media.originalExtension(), String.join(",", media.extensions()), media.originalRetained() ? 1 : 0);
        } catch (DataAccessException failure) {
            throw new IOException("Cannot persist artwork media", failure);
        }
    }

    private static void validateIdentity(long artworkId, int page) {
        if (artworkId <= 0 || page < 0) throw new IllegalArgumentException("Invalid artwork page");
    }
}
