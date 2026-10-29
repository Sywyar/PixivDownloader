package top.sywyar.pixivdownload.testsupport;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;

/** 资源关闭后清理测试目录；Windows 延迟删除或临时条目消退时有限等待。 */
public final class TestDirectoryCleanup {
    private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");

    private TestDirectoryCleanup() {}

    public static void delete(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (attrs.isOther() || Files.isSymbolicLink(dir)) {
                    throw new IOException("Refusing to traverse linked test directory: " + dir);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException failure) throws IOException {
                if (!(failure instanceof NoSuchFileException)) throw failure;
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                if (failure != null) throw failure;
                deleteEmptyDirectory(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static void deleteEmptyDirectory(Path directory) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try {
                Files.deleteIfExists(directory);
                return;
            } catch (DirectoryNotEmptyException failure) {
                if (!WINDOWS || attempt == 9) throw failure;
                // 只重试目录删除，不删除遍历结束后出现的条目；持续残留仍按上限失败。
                try {
                    Thread.sleep(25);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while deleting empty test directory", interrupted);
                }
            }
        }
    }
}
