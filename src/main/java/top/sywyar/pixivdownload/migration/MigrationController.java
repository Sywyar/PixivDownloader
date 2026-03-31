package top.sywyar.pixivdownload.migration;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import top.sywyar.pixivdownload.common.NetworkUtils;

import java.io.IOException;

@RestController
@RequestMapping("/api/migration")
@Slf4j
@RequiredArgsConstructor
public class MigrationController {

    private final JsonToSqliteMigration migration;

    /**
     * 触发 JSON → SQLite 迁移。
     * 幂等操作，已迁移的数据不会重复写入。
     * 仅允许本地 IP 调用。
     */
    @PostMapping("/json-to-sqlite")
    public ResponseEntity<MigrationResponse> migrate(HttpServletRequest request) throws IOException {
        if (!NetworkUtils.isLocalAddress(request.getRemoteAddr())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden: local access only");
        }
        return ResponseEntity.ok(migration.migrate());
    }

    /**
     * 带实时进度的迁移端点（SSE 流）。
     * 仅允许本地 IP 调用。
     * curl 用法：curl -N http://localhost:6999/api/migration/json-to-sqlite/stream
     */
    @GetMapping(value = "/json-to-sqlite/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter migrateWithProgress(HttpServletRequest request) {
        if (!NetworkUtils.isLocalAddress(request.getRemoteAddr())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden: local access only");
        }
        SseEmitter emitter = new SseEmitter(600_000L);
        migration.migrateAsync(emitter);
        return emitter;
    }
}
