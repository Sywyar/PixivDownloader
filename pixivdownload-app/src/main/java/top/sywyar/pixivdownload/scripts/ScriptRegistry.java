package top.sywyar.pixivdownload.scripts;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.scripts.UserscriptRegistry.RegisteredUserscript;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import top.sywyar.pixivdownload.plugin.web.PluginWebContributionRegistrar;

/**
 * 启动时按 {@link UserscriptRegistry} 声明的来源扫描油猴脚本，解析脚本头部元数据，
 * 构建不可变脚本列表。扫描经声明方插件的 ClassLoader 进行，不再做全局 classpath 扫描假设。
 */
@Component
@Slf4j
public class ScriptRegistry {

    private static final Pattern USER_SCRIPT_START = Pattern.compile("^//\\s*==UserScript==\\s*$");
    private static final Pattern USER_SCRIPT_END = Pattern.compile("^//\\s*==/UserScript==\\s*$");
    private static final Pattern NAME_PATTERN = Pattern.compile("^//\\s*@name\\s+(.+?)\\s*$");
    private static final Pattern VERSION_PATTERN = Pattern.compile("^//\\s*@version\\s+(.+?)\\s*$");
    private static final Pattern DESCRIPTION_PATTERN = Pattern.compile("^//\\s*@description\\s+(.+?)\\s*$");

    /**
     * 已知脚本文件名 → 短英文 id 映射，避免中文出现在 URL 路径上。
     */
    private static final Map<String, String> FILENAME_TO_ID = Map.of(
            "Pixiv All-in-One.user.js", "all-in-one",
            "Pixiv 单作品图片下载器(Java后端版).user.js", "artwork-java",
            "Pixiv 单作品图片下载器(Local Download).user.js", "artwork-local",
            "Pixiv User 批量下载器(User Batch).user.js", "user-batch",
            "Pixiv 页面批量下载器(Page Scrape).user.js", "page-batch",
            "Pixiv URL 批量导入单作品下载器(URL Batch).user.js", "import-batch",
            "Pixiv 体验增强工具箱(Toolbox).user.js", "experience-toolbox"
    );

    private final AppMessages messages;
    private final UserscriptRegistry userscriptRegistry;
    /** 脚本列表 + 文件名→资源映射的不可变快照；{@link #refresh()} 整体替换引用（读侧无锁）。 */
    private volatile Snapshot snapshot;

    /** 一份脚本扫描结果：可安装脚本列表与「文件名 → classpath 资源（经声明方 ClassLoader 解析）」。 */
    private record Snapshot(List<ScriptResource> scripts, Map<String, Resource> resourcesByFileName) {
    }

    public ScriptRegistry(AppMessages messages, UserscriptRegistry userscriptRegistry) {
        this.messages = messages;
        this.userscriptRegistry = userscriptRegistry;
        refresh();
    }

    /**
     * 按 {@link UserscriptRegistry} 当前快照重新扫描脚本与内容来源，整体替换不可变快照引用（读侧无锁）。
     * 在外置插件 web 贡献注册 / 注销后由 {@code PluginWebContributionRegistrar} 调用，使某插件的 userscript
     * 来源被注销后脚本层不再残留、再注册后恢复——脚本聚合结果不再是构造期一次性缓存。
     */
    public void refresh() {
        Map<String, Resource> byFileName = new LinkedHashMap<>();
        List<ScriptResource> loaded = List.copyOf(loadScripts(userscriptRegistry, byFileName));
        this.snapshot = new Snapshot(loaded, Map.copyOf(byFileName));
    }

    public List<ScriptResource> getScripts() {
        return snapshot.scripts();
    }

    private List<ScriptResource> loadScripts(UserscriptRegistry userscriptRegistry,
                                             Map<String, Resource> byFileName) {
        List<ScriptResource> result = new ArrayList<>();
        for (RegisteredUserscript registered : userscriptRegistry.userscripts()) {
            PathMatchingResourcePatternResolver resolver =
                    new PathMatchingResourcePatternResolver(registered.classLoader());
            try {
                Resource[] resources = resolver.getResources(registered.contribution().classpathPattern());
                for (Resource resource : resources) {
                    String fileName = resource.getFilename();
                    if (fileName == null) continue;
                    try {
                        ScriptResource sr = parseScript(resource, fileName);
                        result.add(sr);
                        byFileName.put(fileName, resource);
                        log.debug(message("script.log.registered", sr.id(), fileName));
                    } catch (IOException e) {
                        log.warn(message("script.log.parse.failed", fileName), e);
                    }
                }
            } catch (IOException e) {
                log.warn(message("script.log.scan.failed"), e);
            }
        }
        if (result.isEmpty()) {
            log.warn(message("script.log.scan.empty"));
        } else {
            log.info(message("script.log.loaded", result.size()));
        }
        return result;
    }

    private ScriptResource parseScript(Resource resource, String fileName) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            return parseScriptMetadata(fileName, reader);
        }
    }

    static ScriptResource parseScriptMetadata(String fileName, BufferedReader reader) throws IOException {
        String id = FILENAME_TO_ID.getOrDefault(fileName, deriveId(fileName));
        String name = fileName;
        String version = "";
        String description = "";

        String line;
        boolean inHeader = false;
        while ((line = reader.readLine()) != null) {
            String normalizedLine = stripUtf8Bom(line);
            String trimmedLine = normalizedLine.trim();

            if (USER_SCRIPT_START.matcher(trimmedLine).matches()) {
                inHeader = true;
                continue;
            }
            if (USER_SCRIPT_END.matcher(trimmedLine).matches()) {
                break;
            }
            if (!inHeader) {
                continue;
            }

            String value = extractHeaderValue(normalizedLine, NAME_PATTERN);
            if (!value.isEmpty()) {
                name = value;
                continue;
            }

            value = extractHeaderValue(normalizedLine, VERSION_PATTERN);
            if (!value.isEmpty()) {
                version = value;
                continue;
            }

            value = extractHeaderValue(normalizedLine, DESCRIPTION_PATTERN);
            if (!value.isEmpty()) {
                description = value;
            }
        }
        return new ScriptResource(id, name, fileName, description, version);
    }

    private static String stripUtf8Bom(String line) {
        if (!line.isEmpty() && line.charAt(0) == '\uFEFF') {
            return line.substring(1);
        }
        return line;
    }

    private static String extractHeaderValue(String line, Pattern pattern) {
        Matcher matcher = pattern.matcher(line);
        if (!matcher.matches()) {
            return "";
        }
        return matcher.group(1).trim();
    }

    /**
     * 从文件名提取短英文 id（取 ASCII 字母数字部分，连字符分隔）。
     * 用于处理 FILENAME_TO_ID 中未预定义的脚本文件。
     */
    static String deriveId(String fileName) {
        String name = fileName.replace(".user.js", "");
        StringBuilder sb = new StringBuilder();
        for (char c : name.toCharArray()) {
            if (Character.isLetterOrDigit(c) && c < 128) {
                sb.append(Character.toLowerCase(c));
            } else if (!sb.isEmpty() && sb.charAt(sb.length() - 1) != '-') {
                sb.append('-');
            }
        }
        // 去掉末尾连字符
        String id = sb.toString().replaceAll("-+$", "");
        if (id.isEmpty()) {
            // 兜底：用文件名哈希
            return "script-" + Math.abs(fileName.hashCode() % 100000);
        }
        return id;
    }

    public Optional<ScriptResource> findById(String id) {
        return snapshot.scripts().stream().filter(s -> s.id().equals(id)).findFirst();
    }

    /**
     * 按文件名读取脚本内容（UTF-8），经声明方插件的 ClassLoader 解析的资源即时读取。
     * 未知文件名抛 {@link IOException}。
     */
    public String readContent(String fileName) throws IOException {
        Resource resource = snapshot.resourcesByFileName().get(fileName);
        if (resource == null) {
            throw new IOException(message("script.log.content.not-found", fileName));
        }
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String message(String code, Object... args) {
        return messages.getForLog(code, args);
    }
}
