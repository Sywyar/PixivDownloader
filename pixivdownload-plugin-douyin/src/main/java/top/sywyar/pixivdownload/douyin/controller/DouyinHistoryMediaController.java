package top.sywyar.pixivdownload.douyin.controller;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.douyin.db.history.DouyinHistoryService;
import top.sywyar.pixivdownload.douyin.db.history.DouyinWorkFileRecord;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@RestController
@RequestMapping("/api/douyin/history")
@PluginManagedBean
public class DouyinHistoryMediaController {

    private final DouyinHistoryService historyService;

    public DouyinHistoryMediaController(DouyinHistoryService historyService) {
        this.historyService = historyService;
    }

    @GetMapping("/{workId}/media/{fileIndex}")
    public ResponseEntity<Resource> media(@PathVariable String workId,
                                          @PathVariable int fileIndex) throws IOException {
        var work = historyService.findById(workId).orElse(null);
        if (work == null) {
            return ResponseEntity.notFound().build();
        }
        DouyinWorkFileRecord file = historyService.findFilesByWorkId(workId).stream()
                .filter(candidate -> candidate.fileIndex() == fileIndex)
                .findFirst().orElse(null);
        if (file == null || file.fileName() == null || file.fileName().isBlank()) {
            return ResponseEntity.notFound().build();
        }
        Path folder = Path.of(work.folder()).toAbsolutePath().normalize();
        Path candidate = folder.resolve(file.fileName()).normalize();
        if (!candidate.startsWith(folder) || !Files.isRegularFile(candidate)) {
            return ResponseEntity.notFound().build();
        }
        MediaType contentType = mediaType(file.contentType());
        return ResponseEntity.ok()
                .contentType(contentType)
                .contentLength(Files.size(candidate))
                .lastModified(Files.getLastModifiedTime(candidate).toMillis())
                .body(new FileSystemResource(candidate));
    }

    private static MediaType mediaType(String value) {
        if (value == null || value.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        try {
            return MediaType.parseMediaType(value);
        } catch (IllegalArgumentException ignored) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
