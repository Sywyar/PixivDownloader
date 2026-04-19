package top.sywyar.pixivdownload.scripts;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 启动时扫描 classpath:/static/userscripts/*.user.js，解析脚本头部元数据，构建不可变脚本列表。
 */
@Getter
@Component
@Slf4j
public class ScriptRegistry {

    /**
     * 已知脚本文件名 → 短英文 id 映射，避免中文出现在 URL 路径上。
     */
    private static final Map<String, String> FILENAME_TO_ID = Map.of(
            "Pixiv All-in-One.user.js", "all-in-one",
            "Pixiv 单作品图片下载器(Java后端版).user.js", "artwork-java",
            "Pixiv 单作品图片下载器(Local download).user.js", "artwork-local",
            "Pixiv User 批量下载器.user.js", "user-batch",
            "Pixiv 页面批量下载器.user.js", "page-batch",
            "Pixiv URL 批量导入作品下载器.user.js", "import-batch"
    );

    private final List<ScriptResource> scripts;

    public ScriptRegistry() {
        this.scripts = List.copyOf(loadScripts());
    }

    private List<ScriptResource> loadScripts() {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        List<ScriptResource> result = new ArrayList<>();
        try {
            Resource[] resources = resolver.getResources("classpath:/static/userscripts/*.user.js");
            for (Resource resource : resources) {
                String fileName = resource.getFilename();
                if (fileName == null) continue;
                try {
                    ScriptResource sr = parseScript(resource, fileName);
                    result.add(sr);
                    log.debug("Registered user script: id={}, file={}", sr.id(), fileName);
                } catch (IOException e) {
                    log.warn("Failed to parse user script: {}", fileName, e);
                }
            }
        } catch (IOException e) {
            log.warn("No user scripts found at classpath:/static/userscripts/", e);
        }
        log.info("Loaded {} user script(s)", result.size());
        return result;
    }

    private ScriptResource parseScript(Resource resource, String fileName) throws IOException {
        String id = FILENAME_TO_ID.getOrDefault(fileName, deriveId(fileName));
        String name = fileName;
        String version = "";
        String description = "";

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            boolean inHeader = false;
            while ((line = reader.readLine()) != null) {
                if ("// ==UserScript==".equals(line.trim())) {
                    inHeader = true;
                    continue;
                }
                if ("// ==/UserScript==".equals(line.trim())) {
                    break;
                }
                if (!inHeader) continue;

                if (line.startsWith("// @name") && !line.startsWith("// @namespace")) {
                    String val = line.replaceFirst("//\\s*@name\\s+", "").trim();
                    if (!val.isEmpty()) name = val;
                } else if (line.startsWith("// @version")) {
                    version = line.replaceFirst("//\\s*@version\\s+", "").trim();
                } else if (line.startsWith("// @description")) {
                    description = line.replaceFirst("//\\s*@description\\s+", "").trim();
                }
            }
        }
        return new ScriptResource(id, name, fileName, description, version);
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
        return scripts.stream().filter(s -> s.id().equals(id)).findFirst();
    }
}
