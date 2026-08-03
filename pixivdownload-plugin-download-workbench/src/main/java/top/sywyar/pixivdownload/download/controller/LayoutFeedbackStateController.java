package top.sywyar.pixivdownload.download.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.download.request.LayoutFeedbackStateRequest;
import top.sywyar.pixivdownload.download.response.LayoutFeedbackStateResponse;
import top.sywyar.pixivdownload.download.state.LayoutFeedbackStateFiles;
import top.sywyar.pixivdownload.setup.ApplicationModeProvider;
import top.sywyar.pixivdownload.setup.InstallIdentityProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 布局偏好调查的服务端状态端点：{@code GET /api/layout-feedback/state} 返回安装身份
 * distinct_id 与去重状态，{@code POST /api/layout-feedback/state} 持久化状态到
 * {@code state/download-workbench/layout-feedback-state.json}。仅 solo 模式启用，
 * multi 模式一律 403（前端保持 localStorage 实现不变）。
 */
@RestController
@RequestMapping("/api/layout-feedback")
@Slf4j
public class LayoutFeedbackStateController {

    private final Path stateFile;
    private final ObjectMapper objectMapper;
    private final ApplicationModeProvider applicationModeProvider;
    private final InstallIdentityProvider installIdentityProvider;
    private volatile String cachedState = "";

    public LayoutFeedbackStateController(LayoutFeedbackStateFiles layoutFeedbackStateFiles,
                                         ObjectMapper objectMapper,
                                         ApplicationModeProvider applicationModeProvider,
                                         InstallIdentityProvider installIdentityProvider) {
        this.stateFile = layoutFeedbackStateFiles.stateFile();
        this.objectMapper = objectMapper;
        this.applicationModeProvider = applicationModeProvider;
        this.installIdentityProvider = installIdentityProvider;
    }

    @PostConstruct
    public void loadState() throws IOException {
        if (Files.isRegularFile(stateFile)) {
            cachedState = Files.readString(stateFile, StandardCharsets.UTF_8);
            log.info("Loaded layout feedback state from {}", stateFile);
        }
    }

    @GetMapping(value = "/state", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LayoutFeedbackStateResponse> getState() {
        if (!"solo".equals(applicationModeProvider.getMode())) {
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.ok(new LayoutFeedbackStateResponse(
                true,
                installIdentityProvider.get(),
                parseMember("state"),
                parseMember("seen")));
    }

    @PostMapping(value = "/state", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> saveState(@RequestBody LayoutFeedbackStateRequest request)
            throws JsonProcessingException, IOException {
        if (!"solo".equals(applicationModeProvider.getMode())) {
            return ResponseEntity.status(403).build();
        }
        cachedState = objectMapper.writeValueAsString(request);
        Files.createDirectories(stateFile.getParent());
        Files.writeString(stateFile, cachedState, StandardCharsets.UTF_8);
        return ResponseEntity.ok().build();
    }

    private JsonNode parseMember(String fieldName) {
        if (cachedState == null || cachedState.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(cachedState);
            return root == null || root.isNull() ? null : root.get(fieldName);
        } catch (IOException e) {
            log.warn("Ignoring corrupt layout feedback state: {}", stateFile);
            return null;
        }
    }
}
