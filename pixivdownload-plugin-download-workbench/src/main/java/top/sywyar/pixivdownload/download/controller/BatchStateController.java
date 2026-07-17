package top.sywyar.pixivdownload.download.controller;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.sywyar.pixivdownload.config.RuntimePathProvider;
import top.sywyar.pixivdownload.download.request.BatchStateRequest;
import top.sywyar.pixivdownload.download.response.BatchStateResponse;
import top.sywyar.pixivdownload.setup.ApplicationModeProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@RestController
@RequestMapping("/api/batch")
@Slf4j
public class BatchStateController {

    private final Path stateFile;
    private volatile String cachedState = "{}";
    private final ApplicationModeProvider applicationModeProvider;

    public BatchStateController(RuntimePathProvider runtimePathProvider,
                                ApplicationModeProvider applicationModeProvider) {
        this.applicationModeProvider = applicationModeProvider;
        this.stateFile = runtimePathProvider.resolveBatchStatePath();
    }

    @PostConstruct
    public void loadState() throws IOException {
        if (Files.exists(stateFile)) {
            cachedState = Files.readString(stateFile, StandardCharsets.UTF_8);
            log.info("Loaded batch state from {}", stateFile);
        }
    }

    @GetMapping(value = "/state", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<BatchStateResponse> getState() {
        if (!"solo".equals(applicationModeProvider.getMode())) {
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.ok(new BatchStateResponse(cachedState));
    }

    @PostMapping(value = "/state", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> saveState(@RequestBody BatchStateRequest request) throws IOException {
        if (!"solo".equals(applicationModeProvider.getMode())) {
            return ResponseEntity.status(403).build();
        }
        cachedState = request.getState().toString();
        Files.createDirectories(stateFile.getParent());
        Files.writeString(stateFile, cachedState, StandardCharsets.UTF_8);
        return ResponseEntity.ok().build();
    }
}
