package top.sywyar.pixivdownload.core.db.pathprefix;

import org.springframework.stereotype.Repository;
import top.sywyar.pixivdownload.core.db.MainDatabase;
import top.sywyar.pixivdownload.plugin.api.storage.relational.*;
import java.util.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.Operator.*;

@Repository
public class PathPrefixMapper {
    private static final String TABLE = "path_prefixes";
    private final MainDatabase database;
    public PathPrefixMapper(MainDatabase database) { this.database = database; }

    public void insertIfAbsent(String path) {
        database.transaction(s -> s.insert(TABLE, Map.of("path", path), DatabaseSession.Conflict.ignore()));
    }
    public Long findIdByPath(String path) {
        return database.read(s -> s.query(new Query(TABLE, Map.of("id", column(TABLE, "id")),
                call(EQUAL, column(TABLE, "path"), value(path)), List.of(), List.of(), 1, 0)))
                .stream().map(row -> (Long) row.get("id")).findFirst().orElse(null);
    }
    public String findPathById(long id) {
        return database.read(s -> s.query(new Query(TABLE, Map.of("path", column(TABLE, "path")),
                call(EQUAL, column(TABLE, "id"), value(id)), List.of(), List.of(), 1, 0)))
                .stream().map(row -> (String) row.get("path")).findFirst().orElse(null);
    }
    public int updatePath(long id, String path) {
        return database.transaction(s -> s.update(TABLE, Map.of("path", value(path)),
                call(EQUAL, column(TABLE, "id"), value(id))));
    }
    public List<PathPrefix> findAll() {
        return database.read(s -> s.query(new Query(TABLE, Map.of("id", column(TABLE, "id"), "path", column(TABLE, "path")),
                null, List.of(), List.of(new Query.Order(call(LENGTH, column(TABLE, "path")), true, false),
                new Query.Order(column(TABLE, "id"), false, true)), Integer.MAX_VALUE, 0)))
                .stream().map(row -> new PathPrefix((Long) row.get("id"), (String) row.get("path"))).toList();
    }
}
