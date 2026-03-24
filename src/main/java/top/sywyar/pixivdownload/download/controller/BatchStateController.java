package top.sywyar.pixivdownload.download.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@RestController
@RequestMapping("/api/batch")
@CrossOrigin(origins = "*")
@Slf4j
public class BatchStateController {

    private final Path stateFile;
    private volatile String cachedState = "{}";

    public BatchStateController(@Value("${download.root-folder:pixiv-download}") String rootFolder) {
        this.stateFile = Path.of(rootFolder, "batch_state.json");
        try {
            if (Files.exists(stateFile)) {
                cachedState = Files.readString(stateFile, StandardCharsets.UTF_8);
                log.info("Loaded batch state from {}", stateFile);
            }
        } catch (IOException e) {
            log.warn("Failed to load batch state: {}", e.getMessage());
        }
    }

    @GetMapping(value = "/state", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getState() {
        return ResponseEntity.ok(cachedState);
    }

    @PostMapping(value = "/state", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> saveState(@RequestBody String body) {
        cachedState = body;
        try {
            Files.createDirectories(stateFile.getParent());
            Files.writeString(stateFile, body, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to save batch state: {}", e.getMessage());
        }
        return ResponseEntity.ok().build();
    }
}
