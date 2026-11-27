package top.sywyar.pixivdownload.core.asset.artwork;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Repository;
import top.sywyar.pixivdownload.core.asset.ArtworkMediaManifest;
import top.sywyar.pixivdownload.core.asset.ArtworkMediaStore;
import top.sywyar.pixivdownload.core.db.MainDatabase;
import top.sywyar.pixivdownload.core.db.ArtworkMediaRows;
import top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer;
import top.sywyar.pixivdownload.plugin.api.storage.relational.*;
import java.io.IOException;
import java.util.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.Operator.*;

/** 宿主拥有的逐页事实；独立页冲突更新避免并发页面互相覆盖。 */
@Repository
public class ArtworkMediaStoreImpl implements ArtworkMediaStore {
    private static final String TABLE = "artwork_media";
    private final MainDatabase database;

    public ArtworkMediaStoreImpl(MainDatabase database, DatabaseInitializer initializer) {
        this.database = database;
    }
    private static Expression identity(long artworkId, int page) {
        return call(AND, call(EQUAL, column(TABLE, "artwork_id"), value(artworkId)),
                call(EQUAL, column(TABLE, "page"), value(page)));
    }
    @Override public Optional<ArtworkMediaManifest> find(long artworkId, int page) throws IOException {
        if (!database.available()) return Optional.empty();
        validateIdentity(artworkId, page);
        try {
            return database.read(s -> s.query(new Query(TABLE, Map.of(
                    "original_extension", column(TABLE, "original_extension"), "extensions", column(TABLE, "extensions"),
                    "original_retained", column(TABLE, "original_retained"), "thumbnail_owned", column(TABLE, "thumbnail_owned")), identity(artworkId, page),
                    List.of(), List.of(), 1, 0))).stream().findFirst().map(row -> ArtworkMediaRows.read(row, false));
        } catch (DataAccessException | IllegalArgumentException failure) {
            throw new IOException("Cannot read artwork media", failure);
        }
    }
    @Override public void save(long artworkId, int page, ArtworkMediaManifest media) throws IOException {
        if (!database.available()) throw new top.sywyar.pixivdownload.core.database.DatabaseUnavailableException();
        validateIdentity(artworkId, page);
        Objects.requireNonNull(media);
        try {
            database.transaction(s -> s.insert(TABLE, Map.of("artwork_id", artworkId, "page", page,
                    "original_extension", media.originalExtension(), "extensions", String.join(",", media.extensions()),
                    "original_retained", media.originalRetained() ? 1L : 0L, "thumbnail_owned", media.thumbnailOwned() ? 1L : 0L),
                    new DatabaseSession.Conflict(DatabaseSession.Mode.UPDATE, List.of("artwork_id", "page"),
                            Map.of("original_extension", new Incoming("original_extension"),
                                    "extensions", new Incoming("extensions"), "original_retained", new Incoming("original_retained"),
                                    "thumbnail_owned", new Incoming("thumbnail_owned")))));
        } catch (DataAccessException failure) {
            throw new IOException("Cannot persist artwork media", failure);
        }
    }
    @Override public void savePartial(long artworkId, int page, ArtworkMediaManifest media, String fingerprints) throws IOException {
        validateIdentity(artworkId, page);
        String table = "artwork_partial_pages";
        int changed = database.transaction(s -> s.update(table, Map.of(
                "original_extension", value(media.originalExtension()), "extensions", value(String.join(",", media.extensions())),
                "original_retained", value(media.originalRetained() ? 1L : 0L),
                "thumbnail_owned", value(media.thumbnailOwned() ? 1L : 0L),
                "output_fingerprints", value(Objects.requireNonNull(fingerprints))),
                call(AND, call(EQUAL, column(table, "artwork_id"), value(artworkId)), call(EQUAL, column(table, "page"), value(page)))));
        if (changed != 1) throw new IOException("Missing partial artwork page");
    }
    private static void validateIdentity(long artworkId, int page) {
        if (artworkId <= 0 || page < 0) throw new IllegalArgumentException("Invalid artwork page");
    }
}
