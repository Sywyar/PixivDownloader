package top.sywyar.pixivdownload.testsupport;

import org.junit.jupiter.api.extension.AnnotatedElementContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDirDeletionStrategy;

import java.io.IOException;
import java.nio.file.Path;

/** 在 JUnit 原生目录生命周期结束时复用有界清理，保留资源占用与权限错误。 */
public final class TestDirectoryDeletionStrategy implements TempDirDeletionStrategy {
    @Override
    public DeletionResult delete(Path tempDir, AnnotatedElementContext elementContext,
                                 ExtensionContext extensionContext) throws IOException {
        TestDirectoryCleanup.delete(tempDir);
        return DeletionResult.builder(tempDir).build();
    }
}
