package top.sywyar.pixivdownload.scripts;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import top.sywyar.pixivdownload.quota.RateLimitService;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 油猴脚本分发接口，无需认证（AuthFilter 已放行 /api/scripts/**）。
 * 以客户端 IP 为 key 复用 RateLimitService 做分钟窗口速率限制。
 */
@RestController
@RequestMapping("/api/scripts")
@Slf4j
@RequiredArgsConstructor
public class ScriptController {

    private final ScriptRegistry scriptRegistry;
    private final RateLimitService rateLimitService;

    /**
     * 返回可安装的脚本列表及当前请求的 host（用于前端提示 @connect 将指向的地址）。
     */
    @GetMapping
    public ScriptListResponse listScripts(HttpServletRequest request) {
        checkRateLimit(request);
        String host = request.getServerName();
        List<ScriptListResponse.ScriptItem> items = scriptRegistry.getScripts().stream()
                .map(s -> new ScriptListResponse.ScriptItem(s.id(), s.displayName(), s.description(), s.version()))
                .toList();
        return new ScriptListResponse(items, host);
    }

    /**
     * 返回脚本内容。
     * <ul>
     *   <li>默认：Content-Type: application/javascript，供 Tampermonkey 拦截安装。</li>
     *   <li>?raw=1：Content-Type: text/plain，供浏览器内预览。</li>
     * </ul>
     * 非 localhost 请求时，将脚本中的 {@code YOUR_SERVER_HOST} 替换为实际 host。
     */
    /**
     * 供 Tampermonkey 拦截安装：URL 以 .user.js 结尾是触发安装弹窗的必要条件。
     * /{id}/install 保留作向后兼容，但前端应优先使用 /{id}.user.js。
     */
    @GetMapping({"/{id}.user.js", "/{id}/install"})
    public ResponseEntity<byte[]> installScript(
            @PathVariable String id,
            @RequestParam(name = "raw", defaultValue = "false") boolean raw,
            HttpServletRequest request) {

        checkRateLimit(request);
        ScriptResource resource = scriptRegistry.findById(id).orElse(null);
        if (resource == null) {
            return ResponseEntity.notFound().build();
        }

        String content;
        try {
            content = loadScriptContent(resource.fileName());
        } catch (IOException e) {
            log.error("Failed to read script file: {}", resource.fileName(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }

        String host = request.getServerName();
        content = applyHostReplacement(content, host);

        if (raw) {
            // 查看源码：text/plain + UTF-8，不加 Content-Disposition 让浏览器直接内联显示
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, "text/plain; charset=UTF-8")
                    .header("X-Content-Type-Options", "nosniff")
                    .body(content.getBytes(StandardCharsets.UTF_8));
        }

        // 安装模式：filename 只用 ASCII 的 id，避免 Tomcat 因中文文件名拒绝该头
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "application/javascript; charset=UTF-8")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + id + ".user.js\"")
                .header("X-Content-Type-Options", "nosniff")
                .body(content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 从 classpath 读取脚本文件内容。protected 以便测试时覆盖。
     */
    protected String loadScriptContent(String fileName) throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath:/static/userscripts/*.user.js");
        for (Resource res : resources) {
            if (fileName.equals(res.getFilename())) {
                try (InputStream in = res.getInputStream()) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new IOException("Script file not found: " + fileName);
    }

    /**
     * 以客户端 IP 为 key 检查速率限制，超出时抛出 429。
     * 复用 RateLimitService 的分钟窗口逻辑；limit <= 0 时自动放行。
     */
    private void checkRateLimit(HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        if (!rateLimitService.isAllowed(ip)) {
            log.warn("脚本接口速率限制：IP={}", ip);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests");
        }
    }

    /**
     * 将脚本中的 {@code YOUR_SERVER_HOST} 替换为实际 host。
     * 若请求来自 localhost / 127.0.0.1，保留占位符（用户自行在 Tampermonkey 中修改）。
     * 替换后在 {@code @version} 行追加 {@code +host-<host>} 子版本号。
     */
    private String applyHostReplacement(String content, String host) {
        if ("localhost".equals(host) || "127.0.0.1".equals(host)) {
            return content;
        }
        // 替换 @connect YOUR_SERVER_HOST 行（允许任意数量的空白）
        String replaced = content.replaceAll(
                "(//\\s*@connect\\s+)YOUR_SERVER_HOST",
                "$1" + host
        );
        // @version 行追加子版本号，让 Tampermonkey 识别为新版本
        replaced = replaced.replaceAll(
                "(//\\s*@version\\s+(\\S+))",
                "$1+host-" + host
        );
        return replaced;
    }
}
