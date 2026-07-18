package top.sywyar.pixivdownload.core.metadata.sidecar;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 作品 meta sidecar 的文件层写入：路径解析与原子落盘。
 * 生命周期排除（配额打包 / 小说导出）经 {@link top.sywyar.pixivdownload.core.metadata.sidecar.WorkSidecarFiles#isSidecarFile}
 * 统一判定文件名。
 */
@Component
@RequiredArgsConstructor
public class WorkSidecarStore {

    private final ObjectMapper objectMapper;

    /** sidecar 在作品目录下的路径。 */
    public Path sidecarPath(Path directory, long workId) {
        return directory.resolve(WorkSidecarFiles.fileName(workId));
    }

    /** 原子写出 sidecar 文档（先写临时文件再移动），覆盖既有。 */
    public void write(Path directory, long workId, ObjectNode document) throws IOException {
        Files.createDirectories(directory);
        Path target = sidecarPath(directory, workId);
        Path tmp = directory.resolve(WorkSidecarFiles.fileName(workId) + ".tmp");
        objectMapper.writeValue(tmp.toFile(), document);
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
