package top.sywyar.pixivdownload.download.media;

import top.sywyar.pixivdownload.core.asset.FileTransactionService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.HexFormat;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

/** 在插件测试中执行条件发布与失败回退。宿主的耐久事务另行集成验证。 */
public final class TestMediaTransactions implements FileTransactionService {
    @Override public void changeFiles(Map<Path, Path> files, Collection<Path> deletions, Runnable commit) {
        throw new UnsupportedOperationException();
    }

    @Override public void changeFiles(Map<Path, Path> files, Collection<Path> deletions, Set<Path> createOnly, Runnable commit) throws IOException {
        changeFiles(files, deletions, createOnly, Map.of(), commit);
    }

    @Override public void changeFiles(Map<Path, Path> files, Collection<Path> deletions, Set<Path> createOnly,
                                      Map<Path, String> expected, Runnable commit) throws IOException {
        var before = new LinkedHashMap<Path, byte[]>();
        for (var file : createOnly)
            if (!Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) throw new FileAlreadyExistsException(file.toString());
        for (var file : expected.entrySet()) {
            byte[] previous = Files.readAllBytes(file.getKey());
            try {
                String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(previous));
                if (!digest.equals(file.getValue())) throw new FileAlreadyExistsException(file.getKey().toString());
            } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
            before.put(file.getKey(), previous);
        }
        try {
            for (var file : files.entrySet()) {
                if (createOnly.contains(file.getKey())) {
                    Files.copy(file.getValue(), file.getKey());
                    before.put(file.getKey(), null);
                } else {
                    if (!before.containsKey(file.getKey())) before.put(file.getKey(), Files.readAllBytes(file.getKey()));
                    Files.copy(file.getValue(), file.getKey(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            for (var file : deletions) Files.delete(file);
            commit.run();
        } catch (RuntimeException | IOException failure) {
            for (var entry : before.entrySet()) {
                if (entry.getValue() == null) Files.deleteIfExists(entry.getKey());
                else Files.write(entry.getKey(), entry.getValue());
            }
            throw failure;
        }
    }
}
