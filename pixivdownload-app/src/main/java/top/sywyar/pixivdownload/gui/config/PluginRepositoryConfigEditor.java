package top.sywyar.pixivdownload.gui.config;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 结构化读写 {@code config.yaml} 里的<b>自定义插件仓库列表</b>（{@code plugin-catalog.repositories}）。这是
 * {@link ConfigFileEditor} 的列表型补充：后者只能逐行替换<b>标量</b>键，无法处理嵌套序列。
 *
 * <h2>为什么不全文 SnakeYAML 回写</h2>
 * 全文 SnakeYAML 往返会丢失 {@code config.yaml} 里精心维护的分组注释 / 空行（{@code ConfigFileEditor} 的存在意义）。
 * 因此本类<b>读用 SnakeYAML 解析</b>（只读、无格式负担），<b>写用「外科手术式整块替换」</b>：只把
 * {@code plugin-catalog.repositories:} 这一<b>块</b>（键行 + 其后的嵌套序列行）替换为 SnakeYAML <b>序列化</b>出来的
 * 新内容，文件其余每一行（注释 / 其它键 / 未知顶层配置）<b>逐字保留</b>。绝不字符串拼接模拟 YAML、绝不全文回写。
 *
 * <h2>往返保证</h2>
 * <ul>
 *   <li>仓库<b>顺序</b>保留（按列表顺序写出、按序列顺序读回）。</li>
 *   <li>本编辑器未暴露的<b>未知仓库字段</b>经 {@link RepositoryConfigEntry#extraFields} 往返保留。</li>
 *   <li>空列表写为单行 {@code plugin-catalog.repositories:}（继承「安全的空列表」语义、不访问任何第三方地址）。</li>
 *   <li>覆盖项为 {@code 0}（继承全局默认）时<b>省略</b>该键、保持简洁；读回缺失即 {@code 0}——语义等价。</li>
 *   <li>反复保存幂等；保存后重启由 Spring 重新绑定为等价的 {@code PluginCatalogProperties.repositories}。</li>
 * </ul>
 *
 * <p>本类管理<b>扁平顶层键</b> {@code plugin-catalog.repositories}（与模板 / {@code AppConfigGenerator} 同形态，
 * Spring relaxed binding 将其展开为 {@code plugin-catalog.repositories[i].*}）。不处理用户手写的嵌套 {@code plugin-catalog:}
 * 块形态。
 */
public final class PluginRepositoryConfigEditor {

    /** 列表所在的扁平顶层键。 */
    static final String LIST_KEY = "plugin-catalog.repositories";

    /** 本编辑器已知（会被映射成 {@link RepositoryConfigEntry} 字段）的仓库子键（kebab 规范形）。 */
    private static final Set<String> KNOWN_KEYS = Set.of(
            "id", "display-name-key", "manifest-url", "enabled", "proxy-policy",
            "allow-redirects", "strict-https", "allow-non-public-addresses", "use-proxy",
            "connect-timeout-ms", "read-timeout-ms", "max-manifest-bytes", "max-package-bytes");

    private final Path configPath;

    public PluginRepositoryConfigEditor(Path configPath) {
        this.configPath = configPath;
    }

    // ── 读 ──────────────────────────────────────────────────────────────────────

    /**
     * 解析 {@code config.yaml}，读出自定义仓库列表（按序）。文件不存在 / 无该键 / 值为空 → 空列表。
     * 文件不是合法 YAML → 抛 {@link IOException}（由调用方提示）。
     */
    @SuppressWarnings("unchecked")
    public List<RepositoryConfigEntry> read() throws IOException {
        if (!Files.exists(configPath)) {
            return List.of();
        }
        String content = Files.readString(configPath, StandardCharsets.UTF_8);
        Object loaded;
        try {
            loaded = new Yaml().load(content);
        } catch (RuntimeException e) {
            throw new IOException("config.yaml 解析失败: " + e.getMessage(), e);
        }
        if (!(loaded instanceof Map<?, ?> root)) {
            return List.of();
        }
        Object reposValue = root.get(LIST_KEY);
        if (!(reposValue instanceof List<?> rawList)) {
            return List.of();
        }
        List<RepositoryConfigEntry> entries = new ArrayList<>();
        for (Object item : rawList) {
            if (item instanceof Map<?, ?> map) {
                entries.add(toEntry((Map<String, Object>) map));
            }
        }
        return entries;
    }

    private static RepositoryConfigEntry toEntry(Map<String, Object> map) {
        String id = "";
        String displayNameKey = "";
        String manifestUrl = "";
        boolean enabled = true;
        String proxyPolicy = "direct-strict";
        boolean allowRedirects = false;
        boolean strictHttps = true;
        boolean allowNonPublicAddresses = false;
        boolean useProxy = false;
        long connectTimeout = 0;
        long readTimeout = 0;
        long maxManifest = 0;
        long maxPackage = 0;
        Map<String, Object> extra = new LinkedHashMap<>();

        for (Map.Entry<String, Object> field : map.entrySet()) {
            String canonical = canonicalKey(field.getKey());
            Object value = field.getValue();
            switch (canonical) {
                case "id" -> id = asString(value);
                case "display-name-key" -> displayNameKey = asString(value);
                case "manifest-url" -> manifestUrl = asString(value);
                case "enabled" -> enabled = asBoolean(value, true);
                case "proxy-policy" -> proxyPolicy = asString(value);
                case "allow-redirects" -> allowRedirects = asBoolean(value, false);
                case "strict-https" -> strictHttps = asBoolean(value, true);
                case "allow-non-public-addresses" -> allowNonPublicAddresses = asBoolean(value, false);
                case "use-proxy" -> useProxy = asBoolean(value, false);
                case "connect-timeout-ms" -> connectTimeout = asLong(value);
                case "read-timeout-ms" -> readTimeout = asLong(value);
                case "max-manifest-bytes" -> maxManifest = asLong(value);
                case "max-package-bytes" -> maxPackage = asLong(value);
                default -> extra.put(field.getKey(), value); // 未知字段：原样键名保留
            }
        }
        return new RepositoryConfigEntry(id, displayNameKey, manifestUrl, enabled, proxyPolicy,
                allowRedirects, strictHttps, allowNonPublicAddresses, useProxy,
                connectTimeout, readTimeout, maxManifest, maxPackage, extra);
    }

    // ── 写 ──────────────────────────────────────────────────────────────────────

    /**
     * 把仓库列表写回 {@code config.yaml} 的 {@code plugin-catalog.repositories} 块，文件其余内容逐字保留。
     * 键已存在 → 整块替换；不存在 → 插入到最后一个 {@code plugin-catalog.*} 行之后（否则追加到文件末尾）。
     */
    public synchronized void write(List<RepositoryConfigEntry> entries) throws IOException {
        List<String> lines = Files.exists(configPath)
                ? new ArrayList<>(Files.readAllLines(configPath, StandardCharsets.UTF_8))
                : new ArrayList<>();
        List<String> block = new ArrayList<>(Arrays.asList(renderBlock(entries)));

        int keyIndex = findKeyLineIndex(lines);
        if (keyIndex < 0) {
            int insertAt = insertionPoint(lines);
            if (insertAt == lines.size() && !lines.isEmpty() && !lines.get(lines.size() - 1).isBlank()) {
                block.add(0, "");
            }
            lines.addAll(insertAt, block);
        } else {
            // 保留键行的行尾注释（与 ConfigFileEditor 同样珍惜注释）。
            String inlineComment = extractInlineComment(lines.get(keyIndex));
            if (!inlineComment.isEmpty()) {
                block.set(0, block.get(0) + "  " + inlineComment);
            }
            int blockEnd = blockEndIndex(lines, keyIndex);
            List<String> rebuilt = new ArrayList<>(lines.subList(0, keyIndex));
            rebuilt.addAll(block);
            rebuilt.addAll(lines.subList(blockEnd, lines.size()));
            lines = rebuilt;
        }
        Files.write(configPath, lines, StandardCharsets.UTF_8);
    }

    /** 用 SnakeYAML 序列化仓库块；空列表写为单行键（继承「安全的空列表」语义）。 */
    static String[] renderBlock(List<RepositoryConfigEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return new String[]{LIST_KEY + ":"};
        }
        List<Map<String, Object>> serialized = new ArrayList<>();
        for (RepositoryConfigEntry entry : entries) {
            serialized.add(toSerializableMap(entry));
        }
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setIndent(2);
        options.setIndicatorIndent(2);           // 序列项的「-」相对父键缩进，使整块都是缩进行
        options.setIndentWithIndicator(true);
        options.setSplitLines(false);            // 长 URL 不折行（避免破坏整块缩进识别）
        String dumped = new Yaml(options).dump(Collections.singletonMap(LIST_KEY, serialized));
        return dumped.stripTrailing().split("\n", -1);
    }

    /** 把条目转为 SnakeYAML 友好的有序 map：已知字段（省略继承项）+ 未知字段原样。 */
    private static Map<String, Object> toSerializableMap(RepositoryConfigEntry entry) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", entry.id());
        if (entry.displayNameKey() != null && !entry.displayNameKey().isBlank()) {
            map.put("display-name-key", entry.displayNameKey());
        }
        map.put("manifest-url", entry.manifestUrl());
        map.put("enabled", entry.enabled());
        map.put("proxy-policy", entry.proxyPolicy());
        if ("custom".equalsIgnoreCase(entry.proxyPolicy())) {
            map.put("allow-redirects", entry.allowRedirects());
            map.put("strict-https", entry.strictHttps());
            map.put("allow-non-public-addresses", entry.allowNonPublicAddresses());
            map.put("use-proxy", entry.useProxy());
        }
        if (entry.connectTimeoutMs() > 0) {
            map.put("connect-timeout-ms", entry.connectTimeoutMs());
        }
        if (entry.readTimeoutMs() > 0) {
            map.put("read-timeout-ms", entry.readTimeoutMs());
        }
        if (entry.maxManifestBytes() > 0) {
            map.put("max-manifest-bytes", entry.maxManifestBytes());
        }
        if (entry.maxPackageBytes() > 0) {
            map.put("max-package-bytes", entry.maxPackageBytes());
        }
        // 未知字段最后回写（不与已知键冲突）。
        for (Map.Entry<String, Object> extra : entry.extraFields().entrySet()) {
            if (!KNOWN_KEYS.contains(canonicalKey(extra.getKey()))) {
                map.putIfAbsent(extra.getKey(), extra.getValue());
            }
        }
        return map;
    }

    // ── 行级定位 ────────────────────────────────────────────────────────────────

    /** 找到活跃（未注释）的 {@code plugin-catalog.repositories:} 键行下标；找不到返回 -1。 */
    private static int findKeyLineIndex(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (isKeyLine(lines.get(i).trim())) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isKeyLine(String trimmed) {
        if (trimmed.startsWith("#") || !trimmed.startsWith(LIST_KEY)) {
            return false;
        }
        String rest = trimmed.substring(LIST_KEY.length()).stripLeading();
        return rest.startsWith(":");
    }

    /**
     * 计算块结束（exclusive）下标：从键行下一行起，把「缩进行」与「序列项行（{@code - ...}）」纳入块，
     * 直至遇到下一个有效<b>顶层键</b>（未缩进、非序列项、非注释）或文件末尾为止。
     *
     * <p><b>空行与顶层注释都做「向后判定」、不提前结束块</b>：二者只「暂记」、是否纳入块由其后是否还有块内容
     * （缩进行 / 序列项）决定——
     * <ul>
     *   <li>顶层注释后<b>仍有缩进 / 序列项</b>（如两个仓库项之间的整行注释）→ 注释属于仓库块，随整块替换。</li>
     *   <li>顶层注释后是<b>顶层键或文件末尾</b>（块尾分隔注释）→ 注释不并入块，作为块外内容原样保留。</li>
     * </ul>
     * 缩进注释（缩进在序列项之内）天然落入「缩进行」判据、属于块。块末尾与下一个顶层键之间的<b>尾随空行 / 注释
     * 不并入块</b>（作为分隔内容原样保留）。用「缩进 / {@code -} 前缀 / {@code #} 顶层注释」判据，对 SnakeYAML
     * 的具体缩进风格不敏感，且不会把项间注释误判为块结束而悬挂后续仓库项。
     */
    private static int blockEndIndex(List<String> lines, int keyIndex) {
        int lastBlockLine = keyIndex; // 最后一行确属本块的内容行（键行本身保底）
        int j = keyIndex + 1;
        while (j < lines.size()) {
            String raw = lines.get(j);
            if (raw.isBlank()) {
                j++; // 空行暂不结束块：仅当其后还有块内容时才会被 lastBlockLine 纳入
                continue;
            }
            String stripped = raw.stripLeading();
            boolean indented = raw.length() != stripped.length();
            boolean sequenceItem = stripped.equals("-") || stripped.startsWith("- ");
            if (indented || sequenceItem) {
                lastBlockLine = j;
                j++;
            } else if (stripped.startsWith("#")) {
                // 顶层注释与空行同样「向后判定」：其后还有块内容才并入块（项间注释），否则作块外分隔保留（块尾注释）。
                j++;
            } else {
                break; // 未缩进、非序列项、非注释 = 下一个顶层键，块到此为止
            }
        }
        return lastBlockLine + 1;
    }

    /** 键不存在时的插入位置：最后一个 {@code plugin-catalog.*} 活跃行之后，否则文件末尾。 */
    private static int insertionPoint(List<String> lines) {
        int lastCatalog = -1;
        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            if (!trimmed.startsWith("#") && trimmed.startsWith("plugin-catalog.")) {
                lastCatalog = i;
            }
        }
        return lastCatalog >= 0 ? lastCatalog + 1 : lines.size();
    }

    /** 提取行尾注释（{@code # ...}），无则空串。键行的值区为空（仅 {@code key:}），故 {@code #} 后即注释。 */
    private static String extractInlineComment(String line) {
        int hash = line.indexOf('#');
        return hash >= 0 ? line.substring(hash).trim() : "";
    }

    // ── 值转换 ──────────────────────────────────────────────────────────────────

    /** 规范化仓库子键：camelCase / snake_case → 小写 kebab-case，便于宽松匹配用户手写形态。 */
    private static String canonicalKey(String key) {
        StringBuilder sb = new StringBuilder(key.length() + 4);
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (Character.isUpperCase(c)) {
                sb.append('-').append(Character.toLowerCase(c));
            } else if (c == '_') {
                sb.append('-');
            } else {
                sb.append(c);
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    private static String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static boolean asBoolean(Object value, boolean fallback) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value == null) {
            return fallback;
        }
        String s = String.valueOf(value).trim();
        if (s.equalsIgnoreCase("true")) {
            return true;
        }
        if (s.equalsIgnoreCase("false")) {
            return false;
        }
        return fallback;
    }

    private static long asLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
