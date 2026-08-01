package top.sywyar.pixivdownload.scripts;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.plugin.api.userscript.UserscriptArtifact;
import top.sywyar.pixivdownload.plugin.api.userscript.UserscriptCatalog;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;

/**
 * 下载工作台插件的油猴脚本分发接口。
 * 路由由 download-workbench 贡献，随插件启停注册 / 注销；游客限流由宿主鉴权过滤器统一执行。
 */
@RestController
@RequestMapping("/api/scripts")
@RequiredArgsConstructor
public class ScriptController {

    private final UserscriptCatalog userscriptCatalog;
    private final MessageResolver messages;

    /**
     * 返回可安装的脚本列表及当前请求的 host（用于前端提示 @connect 将指向的地址）。
     */
    @GetMapping
    public ScriptListResponse listScripts(HttpServletRequest request) {
        String host = request.getServerName();
        List<ScriptListResponse.ScriptItem> items = userscriptCatalog.scripts().stream()
                .map(s -> new ScriptListResponse.ScriptItem(
                        s.id(),
                        messages.getOrDefault(displayNameCode(s.id()), s.displayName()),
                        messages.getOrDefault(descriptionCode(s.id()), s.description()),
                        s.version()
                ))
                .toList();
        return new ScriptListResponse(items, host);
    }

    /**
     * 返回脚本内容。
     * <ul>
     *   <li>默认：Content-Type: application/javascript，供 Tampermonkey 拦截安装。</li>
     *   <li>?raw=1：Content-Type: text/plain，供浏览器内预览。</li>
     * </ul>
     * 非 localhost 请求时，将脚本中的 {@code YOUR_SERVER_HOST} 替换为实际 host；
     * 同时将 {@code @updateURL} 指向当前后端的标准安装地址。
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
        return serveScript(id, raw, request);
    }

    @GetMapping("/{id}")
    public ResponseEntity<byte[]> viewScriptSource(
            @PathVariable String id,
            @RequestParam(name = "raw", defaultValue = "false") boolean raw,
            HttpServletRequest request) {

        if (!raw) {
            return ResponseEntity.notFound().build();
        }
        return serveScript(id, true, request);
    }

    private ResponseEntity<byte[]> serveScript(String id, boolean raw, HttpServletRequest request) {

        UserscriptArtifact artifact = userscriptCatalog.scripts().stream()
                .filter(script -> script.id().equals(id))
                .findFirst()
                .orElse(null);
        if (artifact == null) {
            return ResponseEntity.notFound().build();
        }

        String content = artifact.content();
        String host = request.getServerName();
        String installUrl = buildInstallUrl(id, request);
        content = applyInstallReplacements(content, host, installUrl);

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
     * 将脚本中的 {@code @updateURL} / {@code @downloadURL} 替换为后端安装地址，并在需要时替换 {@code YOUR_SERVER_HOST}。
     * 若请求来自 localhost / 127.0.0.1，保留占位符（用户自行在 Tampermonkey 中修改）。
     * 替换后在 {@code @version} 行追加 {@code +host-<host>} 子版本号。
     */
    private String applyInstallReplacements(String content, String host, String installUrl) {
        String replaced = content.replaceAll(
                "(//\\s*@(?:update|download)URL\\s+)\\S+",
                "$1" + Matcher.quoteReplacement(installUrl)
        );
        if ("localhost".equals(host) || "127.0.0.1".equals(host)) {
            return replaced;
        }
        // 替换 @connect YOUR_SERVER_HOST 行（允许任意数量的空白）
        replaced = replaced.replaceAll(
                "(//\\s*@connect\\s+)YOUR_SERVER_HOST",
                "$1" + Matcher.quoteReplacement(host)
        );
        // @version 行追加子版本号，让 Tampermonkey 识别为新版本
        replaced = replaced.replaceAll(
                "(//\\s*@version\\s+(\\S+))",
                "$1+host-" + Matcher.quoteReplacement(host)
        );
        return replaced;
    }

    private String buildInstallUrl(String id, HttpServletRequest request) {
        return ServletUriComponentsBuilder.fromRequestUri(request)
                .replacePath(request.getContextPath() + "/api/scripts/" + id + ".user.js")
                .replaceQuery(null)
                .build()
                .toUriString();
    }

    private static String displayNameCode(String id) {
        return "script.meta." + id + ".name";
    }

    private static String descriptionCode(String id) {
        return "script.meta." + id + ".description";
    }
}
