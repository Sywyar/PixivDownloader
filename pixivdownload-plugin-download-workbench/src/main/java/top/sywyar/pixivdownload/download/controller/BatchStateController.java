package top.sywyar.pixivdownload.download.controller;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.sywyar.pixivdownload.download.request.BatchStateRequest;
import top.sywyar.pixivdownload.download.response.status.BatchStateResponse;
import top.sywyar.pixivdownload.download.state.BatchStateFiles;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.setup.ApplicationModeProvider;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@RestController
@RequestMapping("/api/batch")
@Slf4j
public class BatchStateController {

    private final Path stateFile;
    private volatile String cachedState = "{}";
    private final ApplicationModeProvider applicationModeProvider;
    private final MessageResolver messages;

    public BatchStateController(BatchStateFiles batchStateFiles,
                                ApplicationModeProvider applicationModeProvider,
                                MessageResolver messages) {
        this.applicationModeProvider = applicationModeProvider;
        this.messages = messages;
        this.stateFile = batchStateFiles.stateFile();
    }

    @PostConstruct
    public void loadState() throws IOException {
        if (Files.exists(stateFile)) {
            cachedState = Files.readString(stateFile, StandardCharsets.UTF_8);
            log.info("Loaded batch state from {}", stateFile);
        }
    }

    @GetMapping(value = "/state", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getState() {
        if (!"solo".equals(applicationModeProvider.getMode())) {
            return ResponseEntity.status(403)
                    .body(ApiErrorResponse.of("batch.state.solo-only", "Batch state is available only in solo mode"));
        }
        return ResponseEntity.ok(new BatchStateResponse(cachedState));
    }

    @PostMapping(value = "/state", consumes = MediaType.APPLICATION_JSON_VALUE)
    public synchronized ResponseEntity<?> saveState(@RequestBody BatchStateRequest request) throws IOException {
        if (!"solo".equals(applicationModeProvider.getMode())) {
            return ResponseEntity.status(403)
                    .body(ApiErrorResponse.of("batch.state.solo-only", "Batch state is available only in solo mode"));
        }
        if (request.getState() == null || !request.getState().isObject()) {
            return ResponseEntity.badRequest().body(ApiErrorResponse.of(
                    "error.request.param.invalid", messages.get("error.request.param.invalid")));
        }
        String state = request.getState().toString();
        Files.createDirectories(stateFile.getParent());
        Path temporary = Files.createTempFile(stateFile.getParent(), "batch-state-", ".tmp");
        try {
            Files.writeString(temporary, state, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, stateFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, stateFile, StandardCopyOption.REPLACE_EXISTING);
            }
            cachedState = state;
        } finally {
            Files.deleteIfExists(temporary);
        }
        return ResponseEntity.ok().build();
    }
}
