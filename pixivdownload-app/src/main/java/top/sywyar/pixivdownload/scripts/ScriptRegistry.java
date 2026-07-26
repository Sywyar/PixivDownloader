package top.sywyar.pixivdownload.scripts;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.plugin.api.userscript.UserscriptArtifact;
import top.sywyar.pixivdownload.plugin.api.userscript.UserscriptCatalog;
import top.sywyar.pixivdownload.scripts.UserscriptRegistry.RegisteredUserscript;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 启动时按 {@link UserscriptRegistry} 的显式声明解析油猴脚本头部元数据，
 * 构建不可变脚本列表。资源经声明方插件的 ClassLoader 读取；稳定 id 与精确路径均由插件贡献，
 * 宿主不猜测具体插件的文件名或安装身份。
 */
@Component
@Slf4j
public class ScriptRegistry implements UserscriptCatalog {

    private static final Pattern USER_SCRIPT_START = Pattern.compile("^//\\s*==UserScript==\\s*$");
    private static final Pattern USER_SCRIPT_END = Pattern.compile("^//\\s*==/UserScript==\\s*$");
    private static final Pattern NAME_PATTERN = Pattern.compile("^//\\s*@name\\s+(.+?)\\s*$");
    private static final Pattern VERSION_PATTERN = Pattern.compile("^//\\s*@version\\s+(.+?)\\s*$");
    private static final Pattern DESCRIPTION_PATTERN = Pattern.compile("^//\\s*@description\\s+(.+?)\\s*$");

    private final AppMessages messages;
    private final UserscriptRegistry userscriptRegistry;
    /** 元数据 + 完整 UTF-8 文本的不可变快照；{@link #refresh()} 整体替换引用（读侧无锁）。 */
    private volatile List<UserscriptArtifact> snapshot;

    public ScriptRegistry(AppMessages messages, UserscriptRegistry userscriptRegistry) {
        this.messages = messages;
        this.userscriptRegistry = userscriptRegistry;
        refresh();
    }

    /**
     * 按 {@link UserscriptRegistry} 当前快照重新物化脚本与内容，整体替换不可变快照引用（读侧无锁）。
     * 在外置插件 web 贡献注册 / 注销后由 {@code PluginWebContributionRegistrar} 调用，使某插件的 userscript
     * 来源被注销后脚本层不再残留、再注册后恢复——脚本聚合结果不再是构造期一次性缓存。
     */
    public synchronized void refresh() {
        this.snapshot = List.copyOf(loadScripts(userscriptRegistry));
    }

    @Override
    public List<UserscriptArtifact> scripts() {
        return snapshot;
    }

    private List<UserscriptArtifact> loadScripts(UserscriptRegistry userscriptRegistry) {
        List<UserscriptArtifact> result = new ArrayList<>();
        Map<String, String> fileNameById = new LinkedHashMap<>();
        for (RegisteredUserscript registered : userscriptRegistry.userscripts()) {
            Resource resource = new DefaultResourceLoader(registered.classLoader())
                    .getResource(registered.contribution().classpathResource());
            String fileName = resource.getFilename();
            if (fileName == null) {
                fileName = registered.contribution().classpathResource();
            }
            try {
                String content = readUtf8(resource);
                UserscriptArtifact artifact =
                        parseScript(registered.contribution().id(), fileName, content);
                String existingFileName = fileNameById.putIfAbsent(artifact.id(), fileName);
                if (existingFileName != null) {
                    throw new IllegalStateException("duplicate userscript id: " + artifact.id()
                            + " (" + existingFileName + ", " + fileName + ")");
                }
                result.add(artifact);
                log.debug(message("script.log.registered", artifact.id(), fileName));
            } catch (IOException e) {
                log.warn(message("script.log.parse.failed", fileName), e);
            }
        }
        if (result.isEmpty()) {
            log.warn(message("script.log.scan.empty"));
        } else {
            log.info(message("script.log.loaded", result.size()));
        }
        return result;
    }

    private static String readUtf8(Resource resource) throws IOException {
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static UserscriptArtifact parseScript(String id, String fileName, String content) throws IOException {
        try (BufferedReader reader = new BufferedReader(new StringReader(content))) {
            return parseScriptMetadata(id, fileName, content, reader);
        }
    }

    private static UserscriptArtifact parseScriptMetadata(
            String id,
            String fileName,
            String content,
            BufferedReader reader
    ) throws IOException {
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
        return new UserscriptArtifact(id, name, description, version, content);
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

    private String message(String code, Object... args) {
        return messages.getForLog(code, args);
    }
}
