package top.sywyar.pixivdownload.douyin.db.history;

import top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixCodec;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class DouyinHistoryRepository {

    private final DouyinHistoryMapper mapper;
    private final PathPrefixCodec pathPrefixCodec;

    public DouyinHistoryRepository(DouyinHistoryMapper mapper, PathPrefixCodec pathPrefixCodec) {
        this.mapper = mapper;
        this.pathPrefixCodec = pathPrefixCodec;
    }

    public Optional<DouyinWorkRecord> findById(String workId) {
        return Optional.ofNullable(resolve(mapper.findActiveById(workId)));
    }

    public Optional<DouyinWorkRecord> findAnyById(String workId) {
        return Optional.ofNullable(resolve(mapper.findAnyById(workId)));
    }

    public List<DouyinWorkFileRecord> findFilesByWorkId(String workId) {
        List<DouyinWorkFileRecord> rows = mapper.findFilesByWorkId(workId);
        return rows == null ? List.of() : rows;
    }

    public int insertWork(DouyinWorkRecord record) {
        return mapper.insertWork(record.withFolder(encodeFolder(record.folder())).withDeleted(false));
    }

    public void insertFiles(List<DouyinWorkFileRecord> files) {
        if (files == null || files.isEmpty()) {
            return;
        }
        files.forEach(mapper::upsertFile);
    }

    public boolean hasWork(String workId) {
        return mapper.countById(workId) > 0;
    }

    public boolean hasActiveWork(String workId) {
        return mapper.countActiveById(workId) > 0;
    }

    public boolean isDeleted(String workId) {
        return mapper.countDeletedById(workId) > 0;
    }

    public int countByTime(long time) {
        return mapper.countByTime(time);
    }

    public Long findMaxTime() {
        return mapper.findMaxTime();
    }

    public int markDeleted(String workId) {
        return mapper.markDeletedById(workId);
    }

    public boolean deleteIfMarkedDeleted(String workId) {
        int files = mapper.deleteFilesIfWorkMarkedDeleted(workId);
        int works = mapper.deleteWorkIfMarkedDeleted(workId);
        return files > 0 || works > 0;
    }

    private DouyinWorkRecord resolve(DouyinWorkRecord record) {
        if (record == null) {
            return null;
        }
        String resolved = pathPrefixCodec.resolve(record.folder());
        return Objects.equals(resolved, record.folder()) ? record : record.withFolder(resolved);
    }

    private String encodeFolder(String folder) {
        if (folder == null) {
            return null;
        }
        return pathPrefixCodec.encode(stripTrailingSlash(folder));
    }

    private static String stripTrailingSlash(String path) {
        return path == null ? null : path.replaceAll("[/\\\\]+$", "");
    }
}
