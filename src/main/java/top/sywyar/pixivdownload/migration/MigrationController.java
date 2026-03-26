package top.sywyar.pixivdownload.migration;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api/migration")
@Slf4j
public class MigrationController {

    @Autowired
    private JsonToSqliteMigration migration;

    /**
     * 触发 JSON → SQLite 迁移。
     * 幂等操作，已迁移的数据不会重复写入。
     * 仅允许本地 IP 调用。
     */
    @PostMapping("/json-to-sqlite")
    public ResponseEntity<?> migrate(HttpServletRequest request) {
        if (!isLocalAddress(request.getRemoteAddr())) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden: local access only"));
        }
        JsonToSqliteMigration.MigrationResult result = migration.migrate();
        if (result.success()) {
            return ResponseEntity.ok(result);
        } else {
            return ResponseEntity.internalServerError().body(result);
        }
    }

    /**
     * 带实时进度的迁移端点（SSE 流）。
     * 仅允许本地 IP 调用。
     * curl 用法：curl -N <a href="http://localhost:6999/api/migration/json-to-sqlite/stream">...</a>
     */
    @GetMapping(value = "/json-to-sqlite/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Object migrateWithProgress(HttpServletRequest request) {
        if (!isLocalAddress(request.getRemoteAddr())) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden: local access only"));
        }
        SseEmitter emitter = new SseEmitter(600_000L);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> {
            try {
                migration.migrate(message -> {
                    try {
                        emitter.send(SseEmitter.event().data(message));
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                });
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            } finally {
                executor.shutdown();
            }
        });
        return emitter;
    }

    private boolean isLocalAddress(String remoteAddr) {
        return "127.0.0.1".equals(remoteAddr)
            || "0:0:0:0:0:0:0:1".equals(remoteAddr)
            || "::1".equals(remoteAddr)
            || "::ffff:127.0.0.1".equals(remoteAddr);   // IPv4-mapped IPv6 修复
    }
}
