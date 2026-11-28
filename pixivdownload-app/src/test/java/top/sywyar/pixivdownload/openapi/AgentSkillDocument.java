package top.sywyar.pixivdownload.openapi;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 读取仓库根目录 {@code skills/} 下随源码发布的 Agent skill，提取其中的接口、权限与相对链接。
 * <p>
 * 引用写法即测试合同：{@code GET /api/x/{id}} 表示一个操作，{@code /api/x/{id}} 表示一个路径，
 * 以 {@code /} 结尾的 {@code /api/x/} 表示一组接口；权限 ID 写成行内代码 {@code `owner:permission`}。
 */
record AgentSkillDocument(Path directory, List<Source> sources) {

    /** 本仓库发布的 skill 名称，也是其目录名。 */
    static final String NAME = "pixivdownloader";

    private static final Pattern API = Pattern.compile(
            "(?:\\b(GET|POST|PUT|PATCH|DELETE|HEAD)\\s+)?(/api/[A-Za-z0-9._~{}/-]*[A-Za-z0-9_~{}/-])");
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`\\n]+)`");
    private static final Pattern PERMISSION = Pattern.compile("[a-z][a-z0-9-]*:[a-z][a-z0-9.-]*");
    private static final Pattern LINK = Pattern.compile("\\]\\(([^)\\s]+)\\)");
    private static final Pattern HEADING = Pattern.compile("(?m)^#{1,6}\\s+(.+?)\\s*$");

    /** skill 中的一个 Markdown 文件。 */
    record Source(Path file, String text) {}

    /** 一处接口引用；{@code method} 为空时按路径或接口组校验。 */
    record ApiReference(Path file, String method, String path) {
        boolean family() {
            return method == null && path.endsWith("/");
        }

        @Override
        public String toString() {
            return (method == null ? "" : method + " ") + path + " (" + file.getFileName() + ")";
        }
    }

    /** 一处相对链接；{@code target} 保留原始写法，可带锚点。 */
    record Link(Path file, String target) {}

    /** 从 surefire 工作目录（模块目录）定位仓库根目录下的 skill。 */
    static AgentSkillDocument load() throws IOException {
        Path directory = Path.of("..", "skills", NAME).toAbsolutePath().normalize();
        if (!Files.isRegularFile(directory.resolve("SKILL.md"))) {
            throw new IOException("Agent skill not found: " + directory);
        }
        List<Source> sources = new ArrayList<>();
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".md")).sorted().toList()) {
                sources.add(new Source(file, Files.readString(file, StandardCharsets.UTF_8)));
            }
        }
        return new AgentSkillDocument(directory, List.copyOf(sources));
    }

    Source skill() {
        return sources.stream().filter(source -> source.file().equals(directory.resolve("SKILL.md")))
                .findFirst().orElseThrow();
    }

    /** 解析 SKILL.md 开头的 YAML frontmatter；缺失时返回 {@code null}。 */
    Map<String, Object> frontmatter() {
        String text = skill().text().replace("\r\n", "\n");
        if (!text.startsWith("---\n")) return null;
        int end = text.indexOf("\n---\n", 4);
        if (end < 0) return null;
        Object parsed = new Yaml().load(text.substring(4, end));
        if (!(parsed instanceof Map<?, ?> map)) return null;
        @SuppressWarnings("unchecked") Map<String, Object> values = (Map<String, Object>) map;
        return values;
    }

    List<ApiReference> apiReferences() {
        List<ApiReference> references = new ArrayList<>();
        for (Source source : sources) {
            Matcher matcher = API.matcher(source.text());
            while (matcher.find()) references.add(new ApiReference(source.file(), matcher.group(1), matcher.group(2)));
        }
        return references;
    }

    /** 行内代码中形如 {@code owner:permission} 的权限 ID。 */
    Set<String> permissions() {
        Set<String> permissions = new LinkedHashSet<>();
        for (Source source : sources) {
            Matcher matcher = INLINE_CODE.matcher(source.text());
            while (matcher.find()) {
                if (PERMISSION.matcher(matcher.group(1)).matches()) permissions.add(matcher.group(1));
            }
        }
        return permissions;
    }

    /** 指向 skill 内部文件或锚点的链接；外部 URL 不在此列。 */
    List<Link> relativeLinks() {
        List<Link> links = new ArrayList<>();
        for (Source source : sources) {
            Matcher matcher = LINK.matcher(source.text());
            while (matcher.find()) {
                String target = matcher.group(1);
                if (!target.contains("://") && !target.startsWith("mailto:")) links.add(new Link(source.file(), target));
            }
        }
        return links;
    }

    /** 按 GitHub 规则从标题生成的锚点集合。 */
    static Set<String> anchors(String markdown) {
        Set<String> anchors = new LinkedHashSet<>();
        Matcher matcher = HEADING.matcher(markdown.replaceAll("(?ms)^```.*?^```", ""));
        while (matcher.find()) {
            anchors.add(matcher.group(1).toLowerCase(Locale.ROOT)
                    .replaceAll("[^\\p{L}\\p{N} _-]", "").replace(' ', '-'));
        }
        return anchors;
    }

    /** 忽略路径参数名，只比较参数位置。 */
    static String normalize(String path) {
        return path.replaceAll("\\{[^}/]+}", "{}");
    }
}
